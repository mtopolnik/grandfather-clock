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
        /** Tick-to-tock minus ideal half-period, in µs (positive = tick-tock gap is longer). */
        val imbalanceMicros: Double = 0.0,
        val imbalanceUncertaintyMicros: Double = 0.0,
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
    private val beatSeqIndices = mutableListOf<Int>()  // sequence index per beat (accounts for missed beats)

    /** Frame indices of all detected beats (each frame = [frameSamples] audio samples). */
    val detectedBeatFrames: List<Int> get() = beatFrames

    /** Sequence index for each detected beat. Even = tick, odd = tock. Gaps indicate missed beats. */
    val detectedBeatSequenceIndices: List<Int> get() = beatSeqIndices

    /** Sub-sample-precise positions from WAV refinement, keyed by beat index. */
    var refinedBeatSamples: Map<Int, Double> = emptyMap()
        private set

    // ---- Period estimation ----
    private var periodMicros = 0.0
    private var uncertaintyMicros = 0.0
    private var method = Method.NONE
    private var synced = false

    /** Current best period estimate in microseconds. */
    val currentPeriodMicros: Double get() = periodMicros
    /** Current period uncertainty in microseconds. */
    val currentUncertaintyMicros: Double get() = uncertaintyMicros
    /** Tick-to-tock minus ideal half-period, in µs. */
    var currentImbalanceMicros: Double = 0.0
        private set
    var currentImbalanceUncertaintyMicros: Double = 0.0
        private set

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

        // Clip geometry: enough room for template matching, alignment, and sinc
        val tplWidth = sampleRate / 50                     // 20 ms = 882 samples
        val tplHalf = tplWidth / 2
        val searchRange = frameSamples                     // ±1 frame = ±220 samples
        val sincHalfLen = 8
        val refineRange = 3
        val clipHalfWidth = tplHalf + searchRange + refineRange + sincHalfLen  // 672

        val totalFileSamples = ((wavFile.length() - 44) / 2).toInt()
        if (totalFileSamples < sampleRate * 3) return null

        // Read only small clips around each beat instead of the whole file
        val diffClips = readWav(wavFile, beatFrames, clipHalfWidth, diff = true) ?: return null

        val approxPeriodSamples = periodMicros / 1_000_000.0 * sampleRate
        val approxHalfPeriod = approxPeriodSamples / 2.0

        // Separate beats into two groups (tick-type / tock-type) using pair
        // formation: consecutive beats with a valid half-period gap.
        val halfMin = approxHalfPeriod * 0.85
        val halfMax = approxHalfPeriod * 1.15
        val groupA = mutableListOf<Int>()     // sample positions (for period math)
        val groupB = mutableListOf<Int>()
        val groupAClip = mutableListOf<Int>() // indices into clips array (for data access)
        val groupBClip = mutableListOf<Int>()
        var idx = 0
        while (idx < beatFrames.size - 1) {
            val posA = beatFrames[idx] * frameSamples
            val posB = beatFrames[idx + 1] * frameSamples
            val gap = posB - posA
            if (gap >= halfMin && gap <= halfMax
                && posA >= clipHalfWidth && posB + clipHalfWidth <= totalFileSamples) {
                groupA.add(posA)
                groupB.add(posB)
                groupAClip.add(idx)
                groupBClip.add(idx + 1)
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
            "totalSamples=%d paired_beats: A=%d B=%d".format(totalFileSamples, groupA.size, groupB.size))

        // --- Phase 2: Build templates with normalized correlation ---
        // Align each clip to the running template using normalized cross-
        // correlation (immune to amplitude differences), then average.
        // Two iterations: first builds an initial template, second rebuilds
        // from scratch using the improved template for alignment.

        fun normalizedCorrelation(tpl: DoubleArray, clip: ShortArray, off: Int): Double {
            var dot = 0.0
            var energy = 1e-12
            for (j in 0 until tplWidth) {
                val p = clipHalfWidth - tplHalf + off + j
                val s = if (p in clip.indices) clip[p].toDouble() else 0.0
                dot += tpl[j] * s
                energy += s * s
            }
            return dot / sqrt(energy)
        }

        fun alignBeatNorm(clip: ShortArray, tpl: DoubleArray): Int {
            var bestOff = 0
            var bestScore = Double.NEGATIVE_INFINITY
            for (off in -searchRange..searchRange) {
                val score = normalizedCorrelation(tpl, clip, off)
                if (score > bestScore) { bestScore = score; bestOff = off }
            }
            return bestOff
        }

        fun buildTemplate(groupClipIdx: List<Int>, alignWith: DoubleArray?): DoubleArray {
            val offsets = IntArray(groupClipIdx.size)
            if (alignWith != null) {
                // Align each clip to the provided template
                for (k in groupClipIdx.indices) {
                    offsets[k] = alignBeatNorm(diffClips[groupClipIdx[k]], alignWith)
                }
            }
            // Average all clips (energy-normalized) to build template
            val tpl = DoubleArray(tplWidth)
            for (k in groupClipIdx.indices) {
                val clip = diffClips[groupClipIdx[k]]
                val off = offsets[k]
                var energy = 0.0
                val window = DoubleArray(tplWidth)
                for (j in 0 until tplWidth) {
                    val p = clipHalfWidth - tplHalf + off + j
                    val s = if (p in clip.indices) clip[p].toDouble() else 0.0
                    window[j] = s
                    energy += s * s
                }
                val scale = if (energy > 1e-12) 1.0 / sqrt(energy) else 0.0
                for (j in 0 until tplWidth) {
                    tpl[j] += window[j] * scale
                }
            }
            // Normalize the final template
            var tplEnergy = 0.0
            for (j in 0 until tplWidth) tplEnergy += tpl[j] * tpl[j]
            if (tplEnergy > 1e-12) {
                val scale = 1.0 / sqrt(tplEnergy)
                for (j in 0 until tplWidth) tpl[j] *= scale
            }
            return tpl
        }

        // Iteration 1: build initial template by aligning to clip[0]
        fun seedTemplate(groupClipIdx: List<Int>): DoubleArray {
            val clip0 = diffClips[groupClipIdx[0]]
            val seed = DoubleArray(tplWidth)
            for (j in 0 until tplWidth) {
                val p = clipHalfWidth - tplHalf + j
                if (p in clip0.indices) seed[j] = clip0[p].toDouble()
            }
            return seed
        }

        var templateA = buildTemplate(groupAClip, seedTemplate(groupAClip))
        var templateB = buildTemplate(groupBClip, seedTemplate(groupBClip))
        // Iteration 2: rebuild using the improved templates for alignment
        templateA = buildTemplate(groupAClip, templateA)
        templateB = buildTemplate(groupBClip, templateB)

        // --- Phase 3: Final-pass alignment with sub-sample precision ---
        // Normalized correlation for coarse offset, then parabolic
        // interpolation for fractional-sample refinement.

        fun alignBeatPrecise(clipIdx: Int, tpl: DoubleArray): Double {
            val clip = diffClips[clipIdx]
            val bestOff = alignBeatNorm(clip, tpl)
            // Parabolic interpolation around the best integer offset
            val left = normalizedCorrelation(tpl, clip, bestOff - 1)
            val center = normalizedCorrelation(tpl, clip, bestOff)
            val right = normalizedCorrelation(tpl, clip, bestOff + 1)
            val denom = left - 2.0 * center + right
            val frac = if (abs(denom) > 1e-10) (0.5 * (left - right) / denom).coerceIn(-0.5, 0.5) else 0.0
            return bestOff + frac
        }

        val alignOffsA = DoubleArray(groupA.size) { alignBeatPrecise(groupAClip[it], templateA) }
        val alignOffsB = DoubleArray(groupB.size) { alignBeatPrecise(groupBClip[it], templateB) }
        val preciseA = DoubleArray(groupA.size) { groupA[it] + alignOffsA[it] }
        val preciseB = DoubleArray(groupB.size) { groupB[it] + alignOffsB[it] }

        // Expose refined positions keyed by beat index
        val refined = mutableMapOf<Int, Double>()
        for (k in groupAClip.indices) refined[groupAClip[k]] = preciseA[k]
        for (k in groupBClip.indices) refined[groupBClip[k]] = preciseB[k]
        refinedBeatSamples = refined

        // --- Phase 4: Separate tick/tock regression with outlier rejection ---
        // Fit A (tick) and B (tock) positions independently on a full-period
        // grid, each with its own intercept. This avoids the tick-tock
        // asymmetry inflating the residuals. Combine the two slope estimates
        // weighted by inverse variance. Outlier rejection via MAD.
        val m = minOf(preciseA.size, preciseB.size)
        if (m < 4) return null

        // Assign period-grid indices (full-period spacing).
        // For group X: index[k] = round((X[k] - X[0]) / approxPeriod)
        fun assignIndices(positions: DoubleArray): IntArray {
            val idx = IntArray(positions.size)
            for (j in 1 until positions.size) {
                idx[j] = ((positions[j] - positions[0]) / approxPeriodSamples).roundToInt()
            }
            return idx
        }

        // Linear regression: position[j] = t0 + index[j] * period
        // Returns (slope, slopeVariance, inlierCount) or null.
        // Two passes: first fits all, computes MAD, removes outliers; second refits.
        data class LineFit(val slope: Double, val slopeVar: Double, val inliers: Int, val rms: Double)

        fun fitLine(positions: DoubleArray, indices: IntArray): LineFit? {
            var pos = positions.toMutableList()
            var idx = indices.toMutableList()
            var slope = 0.0
            var slopeVar = 0.0
            var rms = 0.0
            for (pass in 0..1) {
                val n = pos.size
                if (n < 3) return null
                var sk = 0.0; var skk = 0.0; var sp = 0.0; var skp = 0.0
                for (j in 0 until n) {
                    val k = idx[j].toDouble()
                    sk += k; skk += k * k; sp += pos[j]; skp += k * pos[j]
                }
                val det = n.toDouble() * skk - sk * sk
                if (abs(det) < 1e-12) return null
                slope = (n.toDouble() * skp - sk * sp) / det
                if (slope <= 0) return null
                val t0 = (skk * sp - sk * skp) / det
                val residuals = DoubleArray(n) { pos[it] - (t0 + idx[it] * slope) }

                if (pass == 0) {
                    val sortedAbs = residuals.map { abs(it) }.sorted()
                    val medAbsDev = sortedAbs[sortedAbs.size / 2]
                    val robustScale = maxOf(1.0, 1.4826 * medAbsDev)
                    val cutoff = 3.5 * robustScale
                    val newPos = mutableListOf<Double>()
                    val newIdx = mutableListOf<Int>()
                    for (j in 0 until n) {
                        if (abs(residuals[j]) <= cutoff) {
                            newPos.add(pos[j])
                            newIdx.add(idx[j])
                        }
                    }
                    if (newPos.size < n) {
                        logger?.log("WAV_OUTLIER",
                            "removed %d/%d beats (cutoff=%.1f samples)"
                                .format(n - newPos.size, n, cutoff))
                    }
                    pos = newPos
                    idx = newIdx
                } else {
                    var sumResidualSq = 0.0
                    for (r in residuals) sumResidualSq += r * r
                    val sigma2 = if (n > 2) sumResidualSq / (n - 2) else 0.0
                    slopeVar = if (abs(det) > 1e-20 && n > 2) n.toDouble() / det * sigma2 else 0.0
                    rms = if (n > 2) sqrt(sigma2) else 0.0
                }
            }
            return LineFit(slope, slopeVar, pos.size, rms)
        }

        val idxA = assignIndices(preciseA)
        val idxB = assignIndices(preciseB)
        val fitA = fitLine(preciseA, idxA)
        val fitB = fitLine(preciseB, idxB)

        if (fitA == null && fitB == null) return null

        // Combine slopes weighted by inverse variance
        val period: Double
        val periodVar: Double
        val rmsResidual: Double
        val inlierCount: Int
        if (fitA != null && fitB != null) {
            if (fitA.slopeVar > 0 && fitB.slopeVar > 0) {
                val wA = 1.0 / fitA.slopeVar
                val wB = 1.0 / fitB.slopeVar
                period = (fitA.slope * wA + fitB.slope * wB) / (wA + wB)
                periodVar = 1.0 / (wA + wB)
            } else {
                period = (fitA.slope + fitB.slope) / 2.0
                periodVar = 0.0
            }
            rmsResidual = (fitA.rms + fitB.rms) / 2.0
            inlierCount = fitA.inliers + fitB.inliers
        } else {
            val fit = fitA ?: fitB!!
            period = fit.slope
            periodVar = fit.slopeVar
            rmsResidual = fit.rms
            inlierCount = fit.inliers
        }

        val wavPeriod = period / sampleRate * 1_000_000.0
        val wavUncertainty = if (periodVar > 0) sqrt(periodVar) / sampleRate * 1_000_000.0 else 0.0

        logger?.log("WAV_RESULT",
            "period=%.3f samples (%.1fµs ±%.1fµs) inliers=%d rms=%.2f"
                .format(period, wavPeriod, wavUncertainty, inlierCount, rmsResidual))

        periodMicros = wavPeriod
        uncertaintyMicros = wavUncertainty
        method = Method.WAV_REFINED
        synced = true

        // --- Phase 5: Imbalance (tick-to-tock asymmetry) ---
        val tickToTockGaps = DoubleArray(m) { preciseB[it] - preciseA[it] }
        val avgGap = tickToTockGaps.average()
        val imbalanceSamples = avgGap - period / 2.0
        currentImbalanceMicros = imbalanceSamples / sampleRate * 1_000_000.0
        currentImbalanceUncertaintyMicros = if (m > 1) {
            var sumSq = 0.0
            for (g in tickToTockGaps) { val d = g - avgGap; sumSq += d * d }
            val se = sqrt(sumSq / (m * (m - 1).toDouble()))
            se / sampleRate * 1_000_000.0
        } else 0.0
        logger?.log("WAV_IMBALANCE",
            "%.0f ± %.0f µs (%d pairs)"
                .format(currentImbalanceMicros, currentImbalanceUncertaintyMicros, m))

        // --- Phase 6: Idealized WAV ---
        writeIdealizedFromWav(wavFile, preciseA, preciseB, period, totalFileSamples)

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
            imbalanceMicros = currentImbalanceMicros,
            imbalanceUncertaintyMicros = currentImbalanceUncertaintyMicros,
        )
    }

    private fun writeIdealizedFromWav(
        wavFile: File,
        tickPositions: DoubleArray,
        tockPositions: DoubleArray,
        periodSamples: Double,
        totalFileSamples: Int,
    ) {
        val tplWidth = sampleRate / 50              // 20ms = 882 samples
        val tplHalf = tplWidth / 2
        val amplitudeThreshold = 0.9 * 32767

        fun buildTemplate(positions: DoubleArray): ShortArray {
            val tpl = DoubleArray(tplWidth)
            var count = 0
            RandomAccessFile(wavFile, "r").use { raf ->
                for (center in positions) {
                    val centerInt = center.roundToInt()
                    if (centerInt < tplHalf || centerInt + tplHalf >= totalFileSamples) continue
                    val clipStart = centerInt - tplHalf
                    raf.seek(44L + clipStart.toLong() * 2L)
                    val bytes = ByteArray(tplWidth * 2)
                    raf.readFully(bytes)
                    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

                    var energy = 0.0
                    val window = DoubleArray(tplWidth)
                    for (j in 0 until tplWidth) {
                        val s = bb.short.toDouble()
                        window[j] = s
                        energy += s * s
                    }
                    if (energy <= 1e-12) continue
                    val scale = 1.0 / sqrt(energy)
                    for (j in 0 until tplWidth) tpl[j] += window[j] * scale
                    count++
                }
            }
            if (count < 3) return ShortArray(tplWidth)
            val peak = tpl.maxOf { abs(it) }
            val scale = if (peak > 0.0) amplitudeThreshold / peak else 1.0
            return ShortArray(tplWidth) { (tpl[it] * scale).roundToInt().coerceIn(-32768, 32767).toShort() }
        }

        val tickTemplate = buildTemplate(tickPositions)
        val tockTemplate = buildTemplate(tockPositions)

        val tickToTockSamples = if (currentImbalanceMicros != 0.0) {
            periodSamples / 2.0 + currentImbalanceMicros / 1_000_000.0 * sampleRate
        } else periodSamples / 2.0
        val tockToTickSamples = periodSamples - tickToTockSamples
        val silenceAfterTick = maxOf(0, (tickToTockSamples - tplWidth).roundToInt())
        val silenceAfterTock = maxOf(0, (tockToTickSamples - tplWidth).roundToInt())

        val idealizedFile = File(wavFile.parent, "clock_idealized.wav")
        writeIdealizedWav(idealizedFile, tickTemplate, tockTemplate,
            silenceAfterTick, silenceAfterTock)

        logger?.log("IDEALIZED_WAV",
            "tick=%d tock=%d silenceAfterTick=%d silenceAfterTock=%d"
                .format(tickTemplate.size, tockTemplate.size, silenceAfterTick, silenceAfterTock))
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
        beatSeqIndices.clear()
        refinedBeatSamples = emptyMap()
        minBeatGapFrames = initialMinBeatGapFrames
        periodMicros = 0.0
        uncertaintyMicros = 0.0
        method = Method.NONE
        synced = false
        currentImbalanceMicros = 0.0
        currentImbalanceUncertaintyMicros = 0.0
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

        // Detect missed beat: if we have a stable period and the gap since the
        // last beat is ~2x the expected half-period, a beat was missed. Increment
        // beatCount by 2 so tick/tock assignment stays correct.
        val prevBeatFrame = lastBeatFrame
        val gap = globalFrame - prevBeatFrame
        var missed = false
        if (prevBeatFrame >= 0 && periodMicros > 0 && beatCount >= 10) {
            val halfPeriodFrames = periodMicros / 1_000_000.0 * sampleRate / frameSamples / 2.0
            // Gap is ~2x half-period (i.e., ~1 full period) → missed one beat
            if (gap > halfPeriodFrames * 1.6 && gap < halfPeriodFrames * 2.5) {
                beatCount++  // extra increment for the missed beat
                if ((beatCount % 2 == 1)) tickCount++ // missed beat was a tick
                missed = true
                logger?.log("MISSED_BEAT",
                    "gap=%d frames (%.0fms) expected_half=%.0f frames, incrementing beatCount"
                        .format(gap, gap * frameSamples * 1000.0 / sampleRate, halfPeriodFrames))
            }
        }

        beatCount++
        lastBeatIsTick = (beatCount % 2 == 1)
        if (lastBeatIsTick) tickCount++
        lastBeatFrame = globalFrame

        beatFrames.add(globalFrame)
        beatSeqIndices.add(beatCount - 1)  // 0-based: even=tick, odd=tock

        val beatType = if (lastBeatIsTick) "TICK" else "TOCK"
        val gapFromPrev = if (prevBeatFrame >= 0) {
            val gapMs = (globalFrame - prevBeatFrame) * frameSamples * 1000.0 / sampleRate
            "gap=%.0fms".format(gapMs)
        } else "gap=N/A"
        logger?.log("BEAT",
            "#%d %s t=%.3fs peak=%.0f median=%.0f ratio=%.1f streak=%d %s global_frame=%d%s"
                .format(beatCount, beatType, audioTime, peak, median,
                    peak / maxOf(median, 1.0), streakLen, gapFromPrev, globalFrame,
                    if (missed) " (after missed beat)" else ""))

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

        // Determine if we have 1 or 2 peaks.
        // Reject any second peak with fewer than 80% of the top peak's count —
        // genuine half-period peaks have roughly equal counts, while artifacts
        // (e.g. missed-beat gaps) are far rarer.
        val c1 = clusterCenter(clusters[0])
        val c2 = if (clusters.size >= 2) clusterCenter(clusters[1]) else 0.0
        val twoPeaks = clusters.size >= 2
            && clusters[1].count >= clusters[0].count * 0.8
        val histPeriodFrames: Double
        val halfPeriodCenters: List<Double>

        if (twoPeaks) {
            // Two peaks visible — need >= 3 items in each before reporting
            if (clusters[0].count < 3 || clusters[1].count < 3) return
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

    /**
     * Read clips from a WAV file centered on each beat position.
     * Each clip is 2 * [clipHalfWidth] samples wide, centered on the
     * beat's sample position (beats[i] * [frameSamples]).
     * When [diff] is true, returns sample-to-sample differences
     * clipped to 16-bit range (first element of each clip is 0).
     */
    private fun readWav(
        file: File,
        beats: List<Int>,
        clipHalfWidth: Int,
        diff: Boolean = false,
    ): Array<ShortArray>? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val totalFileSamples = ((raf.length() - 44) / 2).toInt()
                val clipSize = 2 * clipHalfWidth

                Array(beats.size) { i ->
                    val centerSample = beats[i] * frameSamples
                    val clipStart = centerSample - clipHalfWidth
                    val clip = ShortArray(clipSize)

                    val readStart = maxOf(0, clipStart)
                    val readEnd = minOf(totalFileSamples, clipStart + clipSize)
                    if (readEnd <= readStart) return@Array clip

                    val clipOffset = readStart - clipStart
                    val samplesToRead = readEnd - readStart

                    raf.seek(44L + readStart.toLong() * 2L)
                    val bytes = ByteArray(samplesToRead * 2)
                    raf.readFully(bytes)
                    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

                    if (diff) {
                        var prev: Short = 0
                        for (j in 0 until samplesToRead) {
                            val s = bb.short
                            if (j == 0) {
                                clip[clipOffset] = 0
                            } else {
                                clip[clipOffset + j] =
                                    (s - prev).coerceIn(-32768, 32767).toShort()
                            }
                            prev = s
                        }
                    } else {
                        for (j in 0 until samplesToRead) {
                            clip[clipOffset + j] = bb.short
                        }
                    }

                    clip
                }
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
