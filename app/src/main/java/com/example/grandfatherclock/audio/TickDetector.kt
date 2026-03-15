package com.example.grandfatherclock.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Detects tick/tock beats from PCM audio and measures the pendulum period.
 *
 * Processes audio one 5 ms frame at a time:
 * 1. Compute each frame's high-frequency energy (>= 2 kHz via FFT) and
 *    store it in a 40-frame (200 ms) ring buffer.
 * 2. After every new frame, examine the ring buffer for an isolated
 *    energy spike (beat). This gives ~5-10 ms detection latency.
 *
 *    Period estimation from detected beats:
 *    a. Histogram of consecutive beat-frame deltas finds 1 or 2 peaks
 *       (tick-to-tock and tock-to-tick intervals). The sum of the peak
 *       centres gives the full tick-to-tick period. Requires >= 6 total
 *       items in the identified peak buckets (>= 3 per peak if two peaks).
 *    b. Best-fit: models beat[i] = t0 + (i%2)*T1 + (i/2)*P and solves
 *       for P via least squares across all detected beats. Exploits the
 *       long baseline to recover sub-frame precision from accumulated
 *       drift (the true period is not an exact multiple of 5 ms).
 *
 * 3. WAV refinement: sample-level template matching on the full recording,
 *    searching +/-20 ms around the approximate period for maximum precision.
 *
 * Clock 1: 1481.56 ms
 * Clock 2: 1153.5 ms
 */
class TickDetector(private val sampleRate: Int = AudioCapture.SAMPLE_RATE) {

    var logger: SessionLogger? = null

    enum class Method { NONE, HISTOGRAM, BEST_FIT, WAV_REFINED }

    data class State(
        val periodMicros: Double = 0.0,
        val uncertaintyMicros: Double = 0.0,
        val tickCount: Int = 0,
        val beatCount: Int = 0,
        val lastBeatIsTick: Boolean = true,
        /** True only on the frame where we detected a new beat. */
        val newBeat: Boolean = false,
        val elapsedSamples: Long = 0,
        val synced: Boolean = false,
        val method: Method = Method.NONE,
    )

    // ---- Frame geometry ----
    private val frameSamples = sampleRate / 200              // 5 ms = 220 @ 44 100
    private val windowFrames = 40                            // 200 ms energy window
    private val initialMinBeatGapFrames = 80                 // 400 ms between beats
    private var minBeatGapFrames = initialMinBeatGapFrames

    // ---- FFT for high-frequency energy ----
    private val fftSize = 256                                // next power-of-2 >= frameSamples
    private val hannWindow = DoubleArray(frameSamples) {
        0.5 * (1 - cos(2.0 * PI * it / (frameSamples - 1)))
    }
    private val fftBuf = DoubleArray(fftSize * 2)            // interleaved [re, im]
    private val hfStartBin = (2000.0 * fftSize / sampleRate).toInt() + 1  // first bin >= 2 kHz
    private val hfBinCount = fftSize / 2 + 1 - hfStartBin

    // ---- Sample accumulation (one frame only) ----
    private val frameBuffer = ShortArray(frameSamples)
    private var samplesInFrame = 0
    private var totalSamples: Long = 0

    // ---- Energy ring buffer ----
    private val energyRing = DoubleArray(windowFrames)
    private var energyHead = 0       // next write position
    private var energyFilled = 0     // valid entries (grows to windowFrames)

    // ---- Frame counter (global) ----
    private var energyCount = 0

    // ---- Beat detection ----
    private var beatCount = 0
    private var tickCount = 0
    private var lastBeatIsTick = true
    private var lastBeatFrame = -100

    // ---- Beat frame positions for period estimation ----
    private val beatFrames = mutableListOf<Int>()

    // ---- Period estimation ----
    private var periodMicros = 0.0
    private var uncertaintyMicros = 0.0
    private var method = Method.NONE
    private var synced = false

    private var estimateCounter = 0
    private val estimateInterval = 168  // ~once per second (168 x 5 ms = 840 ms)
    private val stateInterval = 28      // periodic State for UI (~140 ms)

    // ================================================================
    //  Public API
    // ================================================================

    fun process(buffer: ShortArray, count: Int): State? = process(buffer, 0, count)

    fun process(buffer: ShortArray, bufferOffset: Int, count: Int): State? {
        var pos = bufferOffset
        val end = bufferOffset + count
        var result: State? = null
        var beatInBuffer = false

        while (pos < end) {
            val toCopy = minOf(frameSamples - samplesInFrame, end - pos)
            System.arraycopy(buffer, pos, frameBuffer, samplesInFrame, toCopy)
            samplesInFrame += toCopy
            pos += toCopy
            totalSamples += toCopy

            if (samplesInFrame >= frameSamples) {
                val s = processFrame()
                if (s != null) {
                    if (s.newBeat) beatInBuffer = true
                    result = s
                }
                samplesInFrame = 0
            }
        }
        // If we detected a beat in an earlier frame but a later periodic State
        // overwrote it, re-set newBeat so the UI doesn't miss the flash.
        if (beatInBuffer && result != null && !result.newBeat) {
            result = result.copy(newBeat = true)
        }
        return result
    }

    fun analyzeWavFile(wavFile: File): State? {
        if (periodMicros <= 0.0 || beatFrames.size < 8) return null

        val (rawSamples, skipSamples) = readWav(wavFile) ?: return null
        val n = rawSamples.size
        if (n < sampleRate * 3) return null

        val approxPeriodSamples = periodMicros / 1_000_000.0 * sampleRate
        val approxHalfPeriod = approxPeriodSamples / 2.0

        // --- Preparation: diff-filter and group beats ---

        val diff = DoubleArray(n)
        for (i in 1 until n) diff[i] = rawSamples[i].toDouble() - rawSamples[i - 1].toDouble()

        // Separate beats into two groups (tick-type / tock-type) using pair
        // formation: consecutive beats with a valid half-period gap.
        val halfMin = approxHalfPeriod * 0.85
        val halfMax = approxHalfPeriod * 1.15
        val groupA = mutableListOf<Int>()   // approximate sample positions
        val groupB = mutableListOf<Int>()
        var idx = 0
        while (idx < beatFrames.size - 1) {
            val posA = beatFrames[idx] * frameSamples - skipSamples
            val posB = beatFrames[idx + 1] * frameSamples - skipSamples
            val gap = posB - posA
            if (gap >= halfMin && gap <= halfMax && posA >= 0 && posB < n) {
                groupA.add(posA)
                groupB.add(posB)
                idx += 2
            } else {
                idx += 1
            }
        }

        if (groupA.size < 4 || groupB.size < 4) {
            logger?.log("WAV_ANALYSIS", "Too few paired beats: A=${groupA.size} B=${groupB.size}")
            return null
        }
        logger?.log("WAV_ANALYSIS",
            "samples=%d paired_beats: A=%d B=%d".format(n, groupA.size, groupB.size))

        // --- Phase 2: Build templates by iterative align-and-average ---

        val tplWidth = sampleRate / 50                     // 20 ms = 882 samples
        val tplHalf = tplWidth / 2
        val searchRange = frameSamples                     // ±1 frame = ±220 samples

        fun buildTemplate(group: List<Int>): DoubleArray {
            // Seed with first beat
            val tpl = DoubleArray(tplWidth)
            val c0 = group[0]
            for (j in 0 until tplWidth) {
                val pos = c0 - tplHalf + j
                if (pos in 0 until n) tpl[j] = diff[pos]
            }

            // Iteratively align and average subsequent beats
            for (k in 1 until group.size) {
                val center = group[k]
                var bestOff = 0
                var bestDot = Double.NEGATIVE_INFINITY
                for (off in -searchRange..searchRange) {
                    var dot = 0.0
                    for (j in 0 until tplWidth) {
                        val pos = center - tplHalf + off + j
                        if (pos in 0 until n) dot += tpl[j] * diff[pos]
                    }
                    if (dot > bestDot) { bestDot = dot; bestOff = off }
                }

                // Average aligned window into template
                for (j in 0 until tplWidth) {
                    val pos = center - tplHalf + bestOff + j
                    val s = if (pos in 0 until n) diff[pos] else 0.0
                    tpl[j] = (tpl[j] * k + s) / (k + 1)
                }
            }
            return tpl
        }

        val templateA = buildTemplate(groupA)
        val templateB = buildTemplate(groupB)

        // --- Phase 3: Final-pass alignment against clean templates ---

        fun alignBeat(center: Int, tpl: DoubleArray): Int {
            var bestOff = 0
            var bestDot = Double.NEGATIVE_INFINITY
            for (off in -searchRange..searchRange) {
                var dot = 0.0
                for (j in 0 until tplWidth) {
                    val pos = center - tplHalf + off + j
                    if (pos in 0 until n) dot += tpl[j] * diff[pos]
                }
                if (dot > bestDot) { bestDot = dot; bestOff = off }
            }
            return bestOff
        }

        val preciseA = DoubleArray(groupA.size) { (groupA[it] + alignBeat(groupA[it], templateA)).toDouble() }
        val preciseB = DoubleArray(groupB.size) { (groupB[it] + alignBeat(groupB[it], templateB)).toDouble() }

        // --- Phase 4: Midpoint regression at sample level ---
        // Same approach as the real-time best-fit (half-period grid on pair
        // midpoints) but using precise sample-level positions.
        val m = minOf(preciseA.size, preciseB.size)
        if (m < 4) return null

        val midpoints = DoubleArray(m) { (preciseA[it] + preciseB[it]) / 2.0 }
        val approxHalfPeriodSamples = approxPeriodSamples / 2.0

        val indices = IntArray(m)
        for (j in 1 until m) {
            indices[j] = ((midpoints[j] - midpoints[0]) / approxHalfPeriodSamples).roundToInt()
        }

        // Linear regression: midpoints[j] = t0 + indices[j] * halfP
        var sk = 0.0; var skk = 0.0; var sp = 0.0; var skp = 0.0
        for (j in 0 until m) {
            val k = indices[j].toDouble()
            val p = midpoints[j]
            sk += k; skk += k * k; sp += p; skp += k * p
        }

        val det = m.toDouble() * skk - sk * sk
        if (abs(det) < 1e-12) return null

        val halfP = (m.toDouble() * skp - sk * sp) / det
        if (halfP <= 0) return null

        val period = halfP * 2.0

        var sumResidualSq = 0.0
        val t0 = (skk * sp - sk * skp) / det
        for (j in 0 until m) {
            val residual = midpoints[j] - (t0 + indices[j] * halfP)
            sumResidualSq += residual * residual
        }
        val sigma2 = if (m > 2) sumResidualSq / (m - 2) else 0.0
        val halfPVar = if (abs(det) > 1e-20 && m > 2) m.toDouble() / det * sigma2 else 0.0
        val rmsResidual = if (m > 2) sqrt(sigma2) else 0.0

        val wavPeriod = period / sampleRate * 1_000_000.0
        val wavUncertainty = if (halfPVar > 0) 2.0 * sqrt(halfPVar) / sampleRate * 1_000_000.0 else 0.0

        logger?.log("WAV_RESULT",
            "period=%.3f samples (%.1fµs ±%.1fµs) pairs=%d rms=%.2f"
                .format(period, wavPeriod, wavUncertainty, m, rmsResidual))

        // --- Phase 5: Write idealized tick-tock WAV ---

        // Build raw-signal templates by summing all sub-sample-aligned windows.
        // Alignment uses the diff-domain template (sharpest features) with
        // parabolic interpolation for fractional-sample precision, then
        // windowed sinc interpolation to extract raw samples at the exact
        // fractional position.  The DoubleArray accumulator has no clipping
        // risk; normalize the final result to 90% of 16-bit max.
        val amplitudeThreshold = 0.9 * 32767
        val sincHalfLen = 8  // 8-tap half-width for windowed sinc kernel
        val refineRange = 3  // ±3 samples for sub-sample refinement

        fun buildRawTemplate(positions: DoubleArray, diffTpl: DoubleArray): ShortArray {
            val tpl = DoubleArray(tplWidth)
            for (k in positions.indices) {
                val center = positions[k].roundToInt()

                // Cross-correlate diff signal with diff template over ±refineRange
                val xcorr = DoubleArray(2 * refineRange + 1)
                for (off in -refineRange..refineRange) {
                    var dot = 0.0
                    for (j in 0 until tplWidth) {
                        val pos = center - tplHalf + off + j
                        if (pos in 0 until n) dot += diffTpl[j] * diff[pos]
                    }
                    xcorr[off + refineRange] = dot
                }

                // Find integer peak
                var bestIdx = refineRange
                for (i in xcorr.indices) {
                    if (xcorr[i] > xcorr[bestIdx]) bestIdx = i
                }
                val bestOff = bestIdx - refineRange

                // Parabolic interpolation for fractional offset
                val fracOff = if (bestIdx in 1 until xcorr.size - 1) {
                    val a = xcorr[bestIdx - 1]
                    val b = xcorr[bestIdx]
                    val c = xcorr[bestIdx + 1]
                    val d = a - 2.0 * b + c
                    if (abs(d) > 1e-10) (0.5 * (a - c) / d).coerceIn(-0.5, 0.5) else 0.0
                } else 0.0

                // Extract raw samples with windowed sinc interpolation
                for (j in 0 until tplWidth) {
                    var sample = 0.0
                    val intPos = center - tplHalf + bestOff + j
                    for (t in -sincHalfLen..sincHalfLen) {
                        val pos = intPos + t
                        if (pos in 0 until n) {
                            val x = t.toDouble() - fracOff
                            val sincVal = if (abs(x) < 1e-10) 1.0
                                else sin(PI * x) / (PI * x)
                            val win = 0.5 * (1.0 + cos(PI * x / sincHalfLen))
                            sample += rawSamples[pos].toDouble() * sincVal * win
                        }
                    }
                    tpl[j] += sample
                }
            }
            // Normalize to 90% of max to avoid clipping
            val peak = tpl.maxOf { abs(it) }
            val scale = if (peak > 0.0) amplitudeThreshold / peak else 1.0
            return ShortArray(tplWidth) { (tpl[it] * scale).roundToInt().coerceIn(-32768, 32767).toShort() }
        }

        val rawTickTemplate = buildRawTemplate(preciseA, templateA)
        val rawTockTemplate = buildRawTemplate(preciseB, templateB)

        // Silence durations: beat-to-beat gap minus template width
        val tickToTockSamples = (0 until m).map { preciseB[it] - preciseA[it] }.average()
        val tockToTickSamples = period - tickToTockSamples
        val silenceAfterTick = maxOf(0, (tickToTockSamples - tplWidth).roundToInt())
        val silenceAfterTock = maxOf(0, (tockToTickSamples - tplWidth).roundToInt())

        val idealizedFile = File(wavFile.parent, "clock_idealized.wav")
        writeIdealizedWav(idealizedFile, rawTickTemplate, rawTockTemplate,
            silenceAfterTick, silenceAfterTock)

        logger?.log("IDEALIZED_WAV",
            "written to %s tick=%d tock=%d silenceAfterTick=%d silenceAfterTock=%d total=%d samples"
                .format(idealizedFile.absolutePath, rawTickTemplate.size, rawTockTemplate.size,
                    silenceAfterTick, silenceAfterTock,
                    rawTickTemplate.size + silenceAfterTick + rawTockTemplate.size + silenceAfterTock))

        periodMicros = wavPeriod
        uncertaintyMicros = wavUncertainty
        method = Method.WAV_REFINED
        synced = true

        return State(
            periodMicros = wavPeriod,
            uncertaintyMicros = wavUncertainty,
            tickCount = tickCount,
            beatCount = beatCount,
            lastBeatIsTick = lastBeatIsTick,
            newBeat = false,
            elapsedSamples = totalSamples,
            synced = true,
            method = Method.WAV_REFINED,
        )
    }

    fun reset() {
        samplesInFrame = 0
        totalSamples = 0
        energyCount = 0
        energyHead = 0
        energyFilled = 0
        beatCount = 0
        tickCount = 0
        lastBeatIsTick = true
        lastBeatFrame = -100
        beatFrames.clear()
        minBeatGapFrames = initialMinBeatGapFrames
        periodMicros = 0.0
        uncertaintyMicros = 0.0
        method = Method.NONE
        synced = false
        estimateCounter = 0
    }

    // ================================================================
    //  Per-frame processing
    // ================================================================

    private fun processFrame(): State? {
        // Compute HF energy for this single 5 ms frame
        val energy = computeHfEnergy()

        // Store in ring buffer
        energyRing[energyHead] = energy
        energyHead = (energyHead + 1) % windowFrames
        if (energyFilled < windowFrames) energyFilled++
        energyCount++

        // Beat detection once ring buffer is full
        var newBeat = false
        if (energyFilled >= windowFrames) {
            newBeat = detectSpike()
        }

        // Period estimation (~once per second)
        var periodUpdated = false
        estimateCounter++
        if (estimateCounter >= estimateInterval && beatFrames.size >= 4) {
            estimateCounter = 0
            estimatePeriod()
            periodUpdated = true
        }

        // Return State on beat, period update, or periodically for elapsed time
        if (newBeat || periodUpdated || energyCount % stateInterval == 0) {
            return State(
                periodMicros = periodMicros,
                uncertaintyMicros = uncertaintyMicros,
                tickCount = tickCount,
                beatCount = beatCount,
                lastBeatIsTick = lastBeatIsTick,
                newBeat = newBeat,
                elapsedSamples = totalSamples,
                synced = synced,
                method = method,
            )
        }
        return null
    }

    /**
     * Compute the RMS energy of frequency bins >= 2 kHz for the current frame
     * in [frameBuffer].
     */
    private fun computeHfEnergy(): Double {
        // Zero the buffer, then copy windowed samples into real part
        for (i in fftBuf.indices) fftBuf[i] = 0.0
        for (i in 0 until frameSamples) {
            fftBuf[2 * i] = frameBuffer[i].toDouble() * hannWindow[i]
        }
        fft(fftBuf, fftSize)

        var sum = 0.0
        for (k in hfStartBin..fftSize / 2) {
            val re = fftBuf[2 * k]
            val im = fftBuf[2 * k + 1]
            sum += re * re + im * im
        }
        return sqrt(sum / hfBinCount)
    }

    // ================================================================
    //  Spike (beat) detection
    // ================================================================

    private fun detectSpike(): Boolean {
        // Linearize ring buffer: oldest at index 0, newest at windowFrames-1
        val energy = DoubleArray(windowFrames) { i ->
            energyRing[(energyHead + i) % windowFrames]
        }

        val sorted = energy.copyOf().also { it.sort() }
        val median = sorted[windowFrames / 2]
        val peak = energy.max()

        // HF energy gives 15-30x tick/noise ratio; 3x is a safe pre-filter
        if (peak < median * 3.0 || peak < 1000.0) {
            if (peak > median * 1.5 && peak > 500.0 && energy[windowFrames - 1] == peak) {
                val audioTime = totalSamples.toDouble() / sampleRate
                logger?.log("SPIKE_BELOW_THR",
                    "t=%.3fs peak=%.0f median=%.0f ratio=%.1f (need 3.0x)"
                        .format(audioTime, peak, median, peak / maxOf(median, 1.0)))
            }
            return false
        }

        var peakFrame = 0
        for (f in 1 until windowFrames) {
            if (energy[f] > energy[peakFrame]) peakFrame = f
        }

        val threshold = median + (peak - median) * 0.8

        // Expand outward from peak to find the contiguous streak of loud frames
        var spikeStart = peakFrame
        var spikeEnd = peakFrame
        while (spikeStart > 0 && energy[spikeStart - 1] > threshold) spikeStart--
        while (spikeEnd < windowFrames - 1 && energy[spikeEnd + 1] > threshold) spikeEnd++

        val streakLen = spikeEnd - spikeStart + 1
        val globalFrame = energyCount - windowFrames + peakFrame
        val audioTime = totalSamples.toDouble() / sampleRate

        if (streakLen > 5) {
            if (energy[windowFrames - 1] == peak) {
                logger?.log("SPIKE_TOO_WIDE",
                    "t=%.3fs peak=%.0f streak=%d frames (%dms) global_frame=%d"
                        .format(audioTime, peak, streakLen, streakLen * 5, globalFrame))
            }
            return false
        }

        // Require quiet frames on both sides of the streak
        if (spikeStart < 1 || spikeEnd > windowFrames - 2) {
            return false
        }
        if (energy[spikeStart - 1] >= threshold || energy[spikeEnd + 1] >= threshold) {
            return false
        }

        if (globalFrame - lastBeatFrame < minBeatGapFrames) {
            return false
        }

        beatCount++
        lastBeatIsTick = (beatCount % 2 == 1)
        if (lastBeatIsTick) tickCount++
        val prevBeatFrame = lastBeatFrame
        lastBeatFrame = globalFrame

        beatFrames.add(globalFrame)

        val beatType = if (lastBeatIsTick) "TICK" else "TOCK"
        val gapFromPrev = if (prevBeatFrame >= 0) {
            val gapMs = (globalFrame - prevBeatFrame) * frameSamples * 1000.0 / sampleRate
            "gap=%.0fms".format(gapMs)
        } else "gap=N/A"
        logger?.log("BEAT",
            "#%d %s t=%.3fs peak=%.0f median=%.0f ratio=%.1f streak=%d %s global_frame=%d"
                .format(beatCount, beatType, audioTime, peak, median,
                    peak / maxOf(median, 1.0), streakLen, gapFromPrev, globalFrame))

        return true
    }

    // ================================================================
    //  Period estimation: histogram of deltas + least-squares best fit
    // ================================================================

    private fun estimatePeriod() {
        val n = beatFrames.size
        if (n < 4) return

        // Step 1: consecutive beat-to-beat deltas (in 5ms frames)
        val deltas = IntArray(n - 1) { beatFrames[it + 1] - beatFrames[it] }

        // Step 2: histogram — count occurrences of each delta value
        val histogram = mutableMapOf<Int, Int>()
        for (d in deltas) {
            histogram[d] = (histogram[d] ?: 0) + 1
        }

        // Group adjacent delta values (within 2 frames = 10ms) into clusters
        val sortedKeys = histogram.keys.sorted()
        if (sortedKeys.isEmpty()) return

        data class Cluster(val keys: MutableList<Int> = mutableListOf(), var count: Int = 0)
        val clusters = mutableListOf<Cluster>()
        var cur = Cluster()
        for (key in sortedKeys) {
            if (cur.keys.isNotEmpty() && key - cur.keys.last() > 2) {
                clusters.add(cur)
                cur = Cluster()
            }
            cur.keys.add(key)
            cur.count += histogram[key]!!
        }
        clusters.add(cur)
        clusters.sortByDescending { it.count }

        // Weighted centre of a cluster
        fun clusterCenter(c: Cluster): Double {
            var sum = 0.0; var cnt = 0
            for (k in c.keys) {
                val w = histogram[k]!!
                sum += k.toDouble() * w
                cnt += w
            }
            return sum / cnt
        }

        // Determine if we have 1 or 2 peaks
        val twoPeaks = clusters.size >= 2 && clusters[1].count >= 2
        val c1 = clusterCenter(clusters[0])
        val histPeriodFrames: Double
        val halfPeriodCenters: List<Double>

        if (twoPeaks) {
            // Two peaks visible — need >= 3 items in each before reporting
            if (clusters[0].count < 3 || clusters[1].count < 3) return
            val c2 = clusterCenter(clusters[1])
            histPeriodFrames = c1 + c2
            halfPeriodCenters = listOf(c1, c2)

            logger?.log("HISTOGRAM",
                "deltas=%d 2-peak period=%.1f frames (%.0fµs) p1=%.1f(%d) p2=%.1f(%d)"
                    .format(deltas.size, histPeriodFrames,
                        histPeriodFrames * frameSamples.toDouble() / sampleRate * 1_000_000.0,
                        c1, clusters[0].count, c2, clusters[1].count))
        } else {
            // One peak — need >= 6 items
            if (clusters[0].count < 6) return
            histPeriodFrames = c1 * 2.0
            halfPeriodCenters = listOf(c1)

            logger?.log("HISTOGRAM",
                "deltas=%d 1-peak period=%.1f frames (%.0fµs) center=%.1f(%d)"
                    .format(deltas.size, histPeriodFrames,
                        histPeriodFrames * frameSamples.toDouble() / sampleRate * 1_000_000.0,
                        c1, clusters[0].count))
        }

        val framesToMicros = frameSamples.toDouble() / sampleRate * 1_000_000.0
        periodMicros = histPeriodFrames * framesToMicros
        method = Method.HISTOGRAM
        uncertaintyMicros = 0.0
        synced = false

        // Adapt dead zone: prevent false beats from echoes within the half-period
        val adaptiveGap = (halfPeriodCenters.min() - 10).toInt()
        if (adaptiveGap > minBeatGapFrames) {
            minBeatGapFrames = adaptiveGap
            logger?.log("DEAD_ZONE_ADAPT", "minBeatGapFrames=%d (%dms)"
                .format(minBeatGapFrames, minBeatGapFrames * frameSamples * 1000 / sampleRate))
        }

        // Step 3: pair-based best-fit for sub-frame precision
        if (n >= 8) {
            val halfMin = halfPeriodCenters.min() - 10
            val halfMax = halfPeriodCenters.max() + 10
            bestFitPeriod(histPeriodFrames, halfMin, halfMax)
        }
    }

    /**
     * Pair-based least-squares fit for the full tick-to-tick period.
     *
     * Groups consecutive detected beats into pairs where the gap matches
     * a known half-period (from the histogram). Uses the **midpoint** of
     * each pair as its position. Midpoints of tick-tock and tock-tick
     * pairs both lie on a uniform half-period grid, so if a missed beat
     * flips the tick/tock labeling, the midpoints still align — the fit
     * is immune to beat-type flips.
     *
     * Assigns indices on the half-period grid, fits
     *   midpoint[j] = t0 + index[j] * halfP
     * via linear regression, and reports the full period as 2 * halfP.
     */
    private fun bestFitPeriod(
        approxPeriodFrames: Double,
        halfPeriodMin: Double,
        halfPeriodMax: Double,
    ) {
        val n = beatFrames.size

        // Step 1: form pairs — consecutive beats with a valid half-period gap.
        // Record the midpoint of each pair.
        val pairMidpoints = mutableListOf<Double>()
        var i = 0
        while (i < n - 1) {
            val delta = beatFrames[i + 1] - beatFrames[i]
            if (delta >= halfPeriodMin && delta <= halfPeriodMax) {
                pairMidpoints.add((beatFrames[i] + beatFrames[i + 1]) / 2.0)
                i += 2
            } else {
                i += 1
            }
        }

        if (pairMidpoints.size < 4) return

        // Step 2: assign indices on the half-period grid
        val approxHalfPeriod = approxPeriodFrames / 2.0
        val m = pairMidpoints.size
        val indices = IntArray(m)
        for (j in 1 until m) {
            indices[j] = ((pairMidpoints[j] - pairMidpoints[0])
                / approxHalfPeriod).roundToInt()
        }

        // Step 3: linear regression — midpoint[j] = t0 + indices[j] * halfP
        var sk = 0.0; var skk = 0.0; var sp = 0.0; var skp = 0.0
        for (j in 0 until m) {
            val k = indices[j].toDouble()
            val p = pairMidpoints[j]
            sk += k; skk += k * k; sp += p; skp += k * p
        }

        val det = m.toDouble() * skk - sk * sk
        if (abs(det) < 1e-12) return

        val t0 = (skk * sp - sk * skp) / det
        val halfP = (m.toDouble() * skp - sk * sp) / det
        if (halfP <= 0) return

        val period = halfP * 2.0  // full tick-to-tick period

        // Residuals and uncertainty
        var sumResidualSq = 0.0
        for (j in 0 until m) {
            val expected = t0 + indices[j] * halfP
            val residual = pairMidpoints[j] - expected
            sumResidualSq += residual * residual
        }

        val sigma2 = if (m > 2) sumResidualSq / (m - 2) else 0.0
        // var(halfP) = σ² · N / det; var(period) = 4 · var(halfP)
        val halfPVar = if (abs(det) > 1e-20 && m > 2) m.toDouble() / det * sigma2 else 0.0

        val framesToMicros = frameSamples.toDouble() / sampleRate * 1_000_000.0
        periodMicros = period * framesToMicros
        uncertaintyMicros = if (halfPVar > 0) 2.0 * sqrt(halfPVar) * framesToMicros else 0.0
        method = Method.BEST_FIT

        val rmsResidual = if (m > 2) sqrt(sigma2) else 0.0
        synced = uncertaintyMicros > 0 && uncertaintyMicros < periodMicros * 0.001

        logger?.log("BEST_FIT",
            "period=%.2f frames (%.1fµs ±%.1fµs) pairs=%d rms=%.2f synced=%s"
                .format(period, periodMicros, uncertaintyMicros, m, rmsResidual, synced))
    }

    // ================================================================
    //  Helpers
    // ================================================================

    private fun writeIdealizedWav(
        file: File,
        tick: ShortArray,
        tock: ShortArray,
        silenceAfterTick: Int,
        silenceAfterTock: Int,
    ) {
        val repeats = 10
        val cycleSamples = tick.size + silenceAfterTick + tock.size + silenceAfterTock
        val totalSamples = cycleSamples * repeats
        val samples = ShortArray(totalSamples)
        for (r in 0 until repeats) {
            var pos = r * cycleSamples
            tick.copyInto(samples, pos); pos += tick.size
            pos += silenceAfterTick  // silence: already zero
            tock.copyInto(samples, pos)
        }

        val dataSize = totalSamples * 2
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)              // subchunk1 size
            header.putShort(1)             // PCM format
            header.putShort(1)             // mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)  // byte rate
            header.putShort(2)             // block align
            header.putShort(16)            // bits per sample
            header.put("data".toByteArray())
            header.putInt(dataSize)
            raf.write(header.array())

            val data = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) data.putShort(s)
            raf.write(data.array())
        }
    }

    private data class WavData(val samples: ShortArray, val skipSamples: Int)

    private fun readWav(file: File): WavData? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(44)
                val dataBytes = (raf.length() - 44).toInt()
                val maxBytes = sampleRate * 300 * 2
                val bytesToRead = minOf(dataBytes, maxBytes)
                val skipSamples = if (dataBytes > maxBytes) (dataBytes - maxBytes) / 2 else 0
                if (skipSamples > 0) raf.seek(44L + skipSamples * 2L)
                val bytes = ByteArray(bytesToRead)
                raf.readFully(bytes)
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                WavData(ShortArray(bytesToRead / 2) { buf.short }, skipSamples)
            }
        } catch (_: Exception) {
            null
        }
    }

    // ================================================================
    //  In-place radix-2 Cooley–Tukey FFT
    //  [data] is interleaved [re0, im0, re1, im1, …] of length 2·n.
    // ================================================================

    private fun fft(data: DoubleArray, n: Int) {
        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n) {
            if (j > i) {
                val tr = data[2 * j];     val ti = data[2 * j + 1]
                data[2 * j] = data[2 * i]; data[2 * j + 1] = data[2 * i + 1]
                data[2 * i] = tr;          data[2 * i + 1] = ti
            }
            var m = n shr 1
            while (m >= 1 && j >= m) { j -= m; m = m shr 1 }
            j += m
        }

        // Butterfly passes
        var halfSize = 1
        while (halfSize < n) {
            val step = halfSize shl 1
            val angle = -PI / halfSize
            val wpr = cos(angle)
            val wpi = sin(angle)
            var wr = 1.0
            var wi = 0.0
            for (m in 0 until halfSize) {
                var i = m
                while (i < n) {
                    val k = i + halfSize
                    val tr = wr * data[2 * k] - wi * data[2 * k + 1]
                    val ti = wr * data[2 * k + 1] + wi * data[2 * k]
                    data[2 * k]     = data[2 * i] - tr
                    data[2 * k + 1] = data[2 * i + 1] - ti
                    data[2 * i]     += tr
                    data[2 * i + 1] += ti
                    i += step
                }
                val tmp = wr * wpr - wi * wpi
                wi = wr * wpi + wi * wpr
                wr = tmp
            }
            halfSize = step
        }
    }
}
