package com.example.grandfatherclock.audio

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Detects tick/tock beats from PCM audio and measures the pendulum period
 * using sample-counting for microsecond-level precision.
 *
 * Robustness features:
 * - Asymmetric noise floor (fast-down, slow-up) resists inflation from
 *   voice or background noise.
 * - Interval validation rejects false triggers that arrive too early
 *   (< 85% of the running median interval).
 * - Missed beat detection: if the interval since the last beat is ~2× the
 *   running median, we infer a beat was missed and adjust the beat counter
 *   to keep tick/tock alignment correct.
 */
class TickDetector(private val sampleRate: Int = AudioCapture.SAMPLE_RATE) {

    data class State(
        val periodMicros: Double = 0.0,
        val uncertaintyMicros: Double = 0.0,
        val tickCount: Int = 0,
        val beatCount: Int = 0,
        val lastBeatIsTick: Boolean = true,
        val elapsedSamples: Long = 0,
        val synced: Boolean = false,
    )

    /** Adaptive noise floor — exponential moving average of amplitude. */
    private var noiseFloor: Double = 200.0

    /** Threshold multiplier above noise floor. */
    private val thresholdMultiplier = 5.0

    /** Minimum absolute threshold to avoid triggering on quiet noise. */
    private val minThreshold = 700

    /** Dead zone in samples after a beat (~600ms). */
    private val deadZoneSamples = (sampleRate * 0.60).toInt()

    /** Samples since last beat trigger (for dead zone). */
    private var samplesSinceLastBeat: Int = deadZoneSamples // start ready

    /** Total samples processed since start (global counter). */
    private var globalSampleIndex: Long = 0

    /** Beat counter — incremented by 2 when a missed beat is detected. */
    private var beatCount: Int = 0

    /** Sample index of the previous beat (for interval calculation). */
    private var prevBeatSample: Long = -1

    /** Sample index of the first tick. */
    private var firstTickSample: Long = -1

    /** Sample index of the most recent tick. */
    private var latestTickSample: Long = -1

    /** Number of ticks detected. */
    private var tickCount: Int = 0

    /** Individual tick-to-tick intervals in microseconds, for stddev calculation. */
    private val tickIntervals = mutableListOf<Double>()

    /** Recent beat-to-beat intervals for median calculation. */
    private val recentIntervals = ArrayDeque<Double>(12)
    private var medianInterval: Double = 0.0

    // For peak detection within a trigger window
    private var inTrigger = false
    private var triggerPeak: Int = 0
    private var triggerPeakSample: Long = 0
    private var triggerCountdown: Int = 0
    private val triggerWindowSamples = (sampleRate * 0.005).toInt() // 5ms window

    fun process(buffer: ShortArray, count: Int): State? {
        var newState: State? = null

        for (i in 0 until count) {
            val sample = abs(buffer[i].toInt())
            samplesSinceLastBeat++

            // Asymmetric noise floor: fast convergence down, slow rise up.
            // This tracks the quiet noise level accurately while resisting
            // inflation from voice or other sustained background sounds.
            if (!inTrigger && samplesSinceLastBeat > deadZoneSamples) {
                noiseFloor = if (sample < noiseFloor) {
                    noiseFloor * 0.999 + sample * 0.001   // fast-down
                } else {
                    noiseFloor * 0.9999 + sample * 0.0001  // 10× slower up
                }
            }

            val threshold = (noiseFloor * thresholdMultiplier).toInt().coerceAtLeast(minThreshold)

            if (!inTrigger && samplesSinceLastBeat >= deadZoneSamples && sample > threshold) {
                inTrigger = true
                triggerPeak = sample
                triggerPeakSample = globalSampleIndex
                triggerCountdown = triggerWindowSamples
            }

            if (inTrigger) {
                if (sample > triggerPeak) {
                    triggerPeak = sample
                    triggerPeakSample = globalSampleIndex
                }
                triggerCountdown--
                if (triggerCountdown <= 0) {
                    // Trigger window ended — validate and register beat
                    inTrigger = false

                    // Interval validation: reject beats arriving too early.
                    // Voice or noise transients often produce peaks between
                    // real beats; rejecting short intervals filters them out.
                    var accept = true
                    var intervalMs = 0.0
                    if (prevBeatSample >= 0) {
                        intervalMs = (triggerPeakSample - prevBeatSample).toDouble() / sampleRate * 1000.0
                        if (medianInterval > 0 && intervalMs < medianInterval * 0.85) {
                            accept = false
                        }
                    }

                    if (accept) {
                        samplesSinceLastBeat = 0

                        // Missed beat detection
                        if (prevBeatSample >= 0) {
                            if (medianInterval > 0 && intervalMs > medianInterval * 1.5) {
                                val missed = (intervalMs / medianInterval).roundToInt() - 1
                                beatCount += missed
                            }

                            // Track interval for median (only normal intervals)
                            if (medianInterval == 0.0 || intervalMs < medianInterval * 1.5) {
                                recentIntervals.addLast(intervalMs)
                                if (recentIntervals.size > 10) recentIntervals.removeFirst()
                                medianInterval = recentIntervals.sorted()[recentIntervals.size / 2]
                            }
                        }
                        prevBeatSample = triggerPeakSample

                        beatCount++
                        val isTick = (beatCount % 2 == 1)

                        if (isTick) {
                            tickCount++
                            if (firstTickSample < 0) {
                                firstTickSample = triggerPeakSample
                            } else {
                                val intervalMicros = (triggerPeakSample - latestTickSample).toDouble() / sampleRate * 1_000_000.0
                                tickIntervals.add(intervalMicros)
                            }
                            latestTickSample = triggerPeakSample
                        }

                        newState = buildState(isTick)
                    }
                }
            }

            globalSampleIndex++
        }

        return newState
    }

    private fun buildState(lastBeatIsTick: Boolean): State {
        val intervals = tickCount - 1
        val periodMicros: Double
        val uncertaintyMicros: Double
        val synced: Boolean

        if (intervals > 0) {
            val totalSamples = latestTickSample - firstTickSample
            periodMicros = totalSamples.toDouble() / intervals / sampleRate * 1_000_000.0

            if (tickIntervals.size >= 2) {
                val mean = tickIntervals.average()
                val variance = tickIntervals.sumOf { (it - mean) * (it - mean) } / (tickIntervals.size - 1)
                uncertaintyMicros = sqrt(variance)
                // Synced when we have enough data and stddev is < 0.1% of period
                synced = tickIntervals.size >= 4 && uncertaintyMicros < periodMicros * 0.001
            } else {
                uncertaintyMicros = 0.0
                synced = false
            }
        } else {
            periodMicros = 0.0
            uncertaintyMicros = 0.0
            synced = false
        }

        return State(
            periodMicros = periodMicros,
            uncertaintyMicros = uncertaintyMicros,
            tickCount = tickCount,
            beatCount = beatCount,
            lastBeatIsTick = lastBeatIsTick,
            elapsedSamples = globalSampleIndex,
            synced = synced,
        )
    }

    fun reset() {
        noiseFloor = 200.0
        samplesSinceLastBeat = deadZoneSamples
        globalSampleIndex = 0
        beatCount = 0
        prevBeatSample = -1
        firstTickSample = -1
        latestTickSample = -1
        tickCount = 0
        recentIntervals.clear()
        tickIntervals.clear()
        medianInterval = 0.0
        inTrigger = false
    }
}
