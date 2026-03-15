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
 * Two-phase approach:
 * 1. Real-time: sliding 200ms window with 140ms advance, 5ms FFT frames.
 *    Each frame is analysed via FFT and the high-frequency band energy
 *    (≥ 2 kHz) is used for beat detection. Clock ticks are broadband
 *    transients that light up the full spectrum, while background noise
 *    (voice, handling) is concentrated below 2 kHz, giving a typical
 *    tick/noise ratio of 15–30× (vs 2–3× with plain RMS).
 *
 *    Period estimation from detected beats:
 *    a. Histogram of consecutive beat-frame deltas finds 1 or 2 peaks
 *       (tick-to-tock and tock-to-tick intervals). The sum of the peak
 *       centres gives the full tick-to-tick period. Requires ≥ 6 total
 *       items in the identified peak buckets (≥ 3 per peak if two peaks).
 *    b. Best-fit: models beat[i] = t0 + (i%2)*T1 + (i/2)*P and solves
 *       for P via least squares across all detected beats. Exploits the
 *       long baseline to recover sub-frame precision from accumulated
 *       drift (the true period is not an exact multiple of 5 ms).
 *
 * 2. WAV refinement: sample-level template matching on the full recording,
 *    searching ±20 ms around the approximate period for maximum precision.
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
        /** True only on the window where a new beat was detected. */
        val newBeat: Boolean = false,
        val elapsedSamples: Long = 0,
        val synced: Boolean = false,
        val method: Method = Method.NONE,
    )

    // ---- Frame / window geometry ----
    private val frameSamples = sampleRate / 200              // 5 ms = 220 @ 44 100
    private val windowFrames = 40                            // 200 ms
    private val slideFrames = 28                             // 140 ms
    private val overlapFrames = windowFrames - slideFrames   // 12 frames = 60 ms
    private val windowSamples = frameSamples * windowFrames
    private val slideSamples = frameSamples * slideFrames
    private val minBeatGapFrames = 80                        // 400 ms between beats

    // ---- FFT for high-frequency energy ----
    private val fftSize = 256                                // next power-of-2 ≥ frameSamples
    private val hannWindow = DoubleArray(frameSamples) {
        0.5 * (1 - cos(2.0 * PI * it / (frameSamples - 1)))
    }
    private val fftBuf = DoubleArray(fftSize * 2)            // interleaved [re, im]
    private val hfStartBin = (2000.0 * fftSize / sampleRate).toInt() + 1  // first bin ≥ 2 kHz
    private val hfBinCount = fftSize / 2 + 1 - hfStartBin

    // ---- Sample accumulation ----
    private val sampleBuffer = ShortArray(windowSamples)
    private var samplesInBuffer = 0
    private var totalSamples: Long = 0

    // ---- Frame counter (for global frame indexing) ----
    private var energyCount = 0
    private var isFirstWindow = true

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
    private val estimateInterval = 6  // ~once per second (6 windows × 140ms ≈ 840ms)

    // ================================================================
    //  Public API
    // ================================================================

    fun process(buffer: ShortArray, count: Int): State? {
        var offset = 0
        var result: State? = null

        while (offset < count) {
            val toCopy = minOf(windowSamples - samplesInBuffer, count - offset)
            System.arraycopy(buffer, offset, sampleBuffer, samplesInBuffer, toCopy)
            samplesInBuffer += toCopy
            offset += toCopy
            totalSamples += toCopy

            if (samplesInBuffer >= windowSamples) {
                result = analyzeWindow()
                val keep = windowSamples - slideSamples
                System.arraycopy(sampleBuffer, slideSamples, sampleBuffer, 0, keep)
                samplesInBuffer = keep
            }
        }
        return result
    }

    fun analyzeWavFile(wavFile: File): State? {
        if (periodMicros <= 0.0) return null

        val rawSamples = readWav(wavFile) ?: return null
        val n = rawSamples.size
        if (n < sampleRate * 3) return null

        // --- Step 1: find coarse beat positions via HF energy at 5 ms ---
        val wavFrame = sampleRate / 200                    // 220 samples = 5 ms
        val wavFftSize = 256
        val wavHfStart = (2000.0 * wavFftSize / sampleRate).toInt() + 1
        val wavHfCount = wavFftSize / 2 + 1 - wavHfStart
        val wavHann = DoubleArray(wavFrame) {
            0.5 * (1 - cos(2.0 * PI * it / (wavFrame - 1)))
        }
        val numFrames = n / wavFrame
        val hfEnergy = DoubleArray(numFrames)
        val localBuf = DoubleArray(wavFftSize * 2)
        for (f in 0 until numFrames) {
            for (i in localBuf.indices) localBuf[i] = 0.0
            val base = f * wavFrame
            for (i in 0 until wavFrame) {
                localBuf[2 * i] = rawSamples[base + i].toDouble() * wavHann[i]
            }
            fft(localBuf, wavFftSize)
            var sum = 0.0
            for (k in wavHfStart..wavFftSize / 2) {
                val re = localBuf[2 * k]; val im = localBuf[2 * k + 1]
                sum += re * re + im * im
            }
            hfEnergy[f] = sqrt(sum / wavHfCount)
        }

        // Find beats: HF-energy peaks above threshold with dead zone
        val sorted = hfEnergy.copyOf().also { it.sort() }
        val median = sorted[numFrames / 2]
        val beatThresh = maxOf(median * 3.0, 1000.0)
        val deadZone = sampleRate / (wavFrame * 5)         // ~200 ms in 5 ms frames
        val beats = mutableListOf<Int>()                    // frame indices
        var lastBeatF = -deadZone
        for (f in 0 until numFrames) {
            if (hfEnergy[f] > beatThresh && f - lastBeatF >= deadZone) {
                beats.add(f)
                lastBeatF = f
            }
        }
        if (beats.size < 6) {
            logger?.log("WAV_ANALYSIS", "Too few beats: ${beats.size} (need 6)")
            return null
        }
        logger?.log("WAV_ANALYSIS",
            "samples=%d frames=%d beats=%d threshold=%.0f median=%.0f"
                .format(n, numFrames, beats.size, beatThresh, median))

        // --- Step 2: diff-filter the raw signal for sharp cross-correlation ---
        // y[n] = x[n] − x[n−1] removes low-frequency content that would
        // broaden the correlation peak. The tick transient's sharp edges
        // become the dominant feature, giving sub-sample matching precision.
        val diff = DoubleArray(n)
        for (i in 1 until n) diff[i] = rawSamples[i].toDouble() - rawSamples[i - 1].toDouble()

        // --- Step 3: template-match an early beat with a late beat ---
        // Use dead reckoning (approximate period × N) to determine how many
        // tick-to-tick periods fit, then cross-correlate to find the precise
        // sample offset. Period = span / N.
        val tplLen = frameSamples                           // 220 samples = 5 ms
        val tplHalf = tplLen / 2
        val tplWindow = DoubleArray(tplLen) {
            0.5 * (1 - cos(2.0 * PI * it / (tplLen - 1)))
        }
        val approxTtSamples = periodMicros / 1_000_000.0 * sampleRate
        val searchHalf = wavFrame * 4                       // ±20 ms in samples

        // Single template matched at several distances (N, N−2, N−4, …
        // tick-to-tick periods — all same beat type). We keep the two
        // strongest-correlation matches for period + uncertainty.
        if (beats.size < 5) return null
        val earlyIdx = 2
        val earlyCenter = beats[earlyIdx] * wavFrame + wavFrame / 2
        val available = n - earlyCenter - tplLen
        val maxN = (available / approxTtSamples).toInt()
        if (maxN < 4) return null

        // Template from early beat (windowed diff signal)
        val tpl = DoubleArray(tplLen) { i ->
            val pos = earlyCenter - tplHalf + i
            if (pos in 0 until n) diff[pos] * tplWindow[i] else 0.0
        }

        fun xcDiff(c: Int): Double {
            var s = 0.0
            for (i in 0 until tplLen) {
                val pos = c - tplHalf + i
                if (pos in 0 until n) s += tpl[i] * diff[pos]
            }
            return s
        }

        // Try N, N−2, N−4, … (same beat type), keep the two with
        // the strongest cross-correlation peaks.
        data class MatchResult(val periodMicros: Double, val peakCorr: Double)
        val matches = mutableListOf<MatchResult>()
        for (numPeriods in maxN downTo maxOf(maxN - 12, 2) step 2) {
            val predicted = earlyCenter + (numPeriods * approxTtSamples).toInt()
            if (predicted + tplHalf >= n || predicted - tplHalf < 0) continue

            var bestOff = 0; var bestVal = Double.NEGATIVE_INFINITY
            for (off in -searchHalf..searchHalf) {
                val c = xcDiff(predicted + off)
                if (c > bestVal) { bestVal = c; bestOff = off }
            }
            // Skip if the peak hit the search boundary (unreliable)
            if (abs(bestOff) >= searchHalf - 5) continue

            val pc = predicted + bestOff
            val cm = xcDiff(pc - 1); val cp = xcDiff(pc + 1)
            val denom = 2.0 * (2.0 * bestVal - cp - cm)
            val delta = if (denom != 0.0) (cp - cm) / denom else 0.0
            val span = (pc + delta) - earlyCenter.toDouble()
            matches.add(MatchResult(span / numPeriods / sampleRate * 1_000_000.0, bestVal))
        }
        if (matches.isEmpty()) return null

        // Sort by correlation strength, take the best two
        matches.sortByDescending { it.peakCorr }
        val periods = matches.take(2).map { it.periodMicros }

        val wavPeriod = periods.average()
        val wavUncertainty = if (periods.size >= 2) {
            abs(periods[0] - periods[1]) * 1.5
        } else 0.0

        logger?.log("WAV_RESULT",
            "period=%.1fµs ±%.1fµs matches=%d best_periods=%s"
                .format(wavPeriod, wavUncertainty, matches.size,
                    periods.map { "%.1f".format(it) }))

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
        samplesInBuffer = 0
        totalSamples = 0
        energyCount = 0
        isFirstWindow = true
        beatCount = 0
        tickCount = 0
        lastBeatIsTick = true
        lastBeatFrame = -100
        beatFrames.clear()
        periodMicros = 0.0
        uncertaintyMicros = 0.0
        method = Method.NONE
        synced = false
        estimateCounter = 0
    }

    // ================================================================
    //  Window analysis
    // ================================================================

    private fun analyzeWindow(): State {
        // Compute high-frequency energy for each 5 ms frame via FFT
        val frameEnergy = DoubleArray(windowFrames) { f ->
            computeHfEnergy(f * frameSamples)
        }

        // Advance global frame counter
        energyCount += if (isFirstWindow) windowFrames else slideFrames
        isFirstWindow = false

        val newBeat = detectSpike(frameEnergy)

        estimateCounter++
        if (estimateCounter >= estimateInterval && beatFrames.size >= 4) {
            estimateCounter = 0
            estimatePeriod()
        }

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

    /**
     * Compute the RMS energy of frequency bins ≥ 2 kHz for one frame.
     * [frameOffset] is the index into [sampleBuffer].
     */
    private fun computeHfEnergy(frameOffset: Int): Double {
        // Zero the buffer, then copy windowed samples into real part
        for (i in fftBuf.indices) fftBuf[i] = 0.0
        for (i in 0 until frameSamples) {
            fftBuf[2 * i] = sampleBuffer[frameOffset + i].toDouble() * hannWindow[i]
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

    private fun detectSpike(energy: DoubleArray): Boolean {
        val sorted = energy.copyOf().also { it.sort() }
        val median = sorted[windowFrames / 2]
        val peak = energy.max()

        // HF energy gives 15–30× tick/noise ratio; 3× is a safe pre-filter
        if (peak < median * 3.0 || peak < 1000.0) {
            if (peak > median * 1.5 && peak > 500.0) {
                val audioTime = totalSamples.toDouble() / sampleRate
                logger?.log("SPIKE_BELOW_THR",
                    "t=%.3fs peak=%.0f median=%.0f ratio=%.1f (need 3.0×)"
                        .format(audioTime, peak, median, peak / maxOf(median, 1.0)))
            }
            return false
        }

        var peakFrame = 0
        for (f in 1 until windowFrames) {
            if (energy[f] > energy[peakFrame]) peakFrame = f
        }

        val threshold = median + (peak - median) * 0.5

        // Expand outward from peak to find the contiguous streak of loud frames
        var spikeStart = peakFrame
        var spikeEnd = peakFrame
        while (spikeStart > 0 && energy[spikeStart - 1] > threshold) spikeStart--
        while (spikeEnd < windowFrames - 1 && energy[spikeEnd + 1] > threshold) spikeEnd++

        val streakLen = spikeEnd - spikeStart + 1
        val globalFrame = energyCount - windowFrames + peakFrame
        val audioTime = totalSamples.toDouble() / sampleRate

        if (streakLen > 5) {
            logger?.log("SPIKE_TOO_WIDE",
                "t=%.3fs peak=%.0f streak=%d frames (%dms) global_frame=%d"
                    .format(audioTime, peak, streakLen, streakLen * 5, globalFrame))
            return false
        }

        // Require quiet frames on both sides of the streak (12-frame overlap guarantees interior)
        if (spikeStart < 1 || spikeEnd > windowFrames - 2) {
            logger?.log("SPIKE_AT_EDGE",
                "t=%.3fs peak=%.0f spikeStart=%d spikeEnd=%d global_frame=%d"
                    .format(audioTime, peak, spikeStart, spikeEnd, globalFrame))
            return false
        }
        if (energy[spikeStart - 1] >= threshold || energy[spikeEnd + 1] >= threshold) {
            logger?.log("SPIKE_NOT_ISOLATED",
                "t=%.3fs peak=%.0f threshold=%.0f left=%.0f right=%.0f global_frame=%d"
                    .format(audioTime, peak, threshold, energy[spikeStart - 1], energy[spikeEnd + 1], globalFrame))
            return false
        }

        if (globalFrame - lastBeatFrame < minBeatGapFrames) {
            val gapMs = (globalFrame - lastBeatFrame) * frameSamples * 1000 / sampleRate
            logger?.log("SPIKE_DEAD_ZONE",
                "t=%.3fs peak=%.0f gap=%dms (need %dms) global_frame=%d"
                    .format(audioTime, peak, gapMs,
                        minBeatGapFrames * frameSamples * 1000 / sampleRate, globalFrame))
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
            // Two peaks visible — need ≥ 3 items in each before reporting
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
            // One peak — need ≥ 6 items
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
     * a known half-period (from the histogram). Each pair represents one
     * tick-tock or tock-tick unit. The period is how often these pairs
     * repeat. This is robust to missed beats: a missed beat simply means
     * a pair doesn't form, without corrupting the indices of other pairs.
     *
     * Uses the histogram's approximate period to assign integer indices
     * to each pair, then fits pairPos[j] = t0 + index[j] * P via linear
     * regression. The long baseline between early and late pairs recovers
     * sub-frame precision from accumulated drift.
     */
    private fun bestFitPeriod(
        approxPeriodFrames: Double,
        halfPeriodMin: Double,
        halfPeriodMax: Double,
    ) {
        val n = beatFrames.size

        // Step 1: form pairs — consecutive beats with a valid half-period gap
        val pairPositions = mutableListOf<Int>()
        var i = 0
        while (i < n - 1) {
            val delta = beatFrames[i + 1] - beatFrames[i]
            if (delta >= halfPeriodMin && delta <= halfPeriodMax) {
                pairPositions.add(beatFrames[i])
                i += 2
            } else {
                i += 1
            }
        }

        if (pairPositions.size < 4) return

        // Step 2: assign period indices using approximate period
        val m = pairPositions.size
        val indices = IntArray(m)
        for (j in 1 until m) {
            indices[j] = ((pairPositions[j] - pairPositions[0]).toDouble()
                / approxPeriodFrames).roundToInt()
        }

        // Step 3: linear regression — pairPos[j] = t0 + indices[j] * P
        var sk = 0.0; var skk = 0.0; var sp = 0.0; var skp = 0.0
        for (j in 0 until m) {
            val k = indices[j].toDouble()
            val p = pairPositions[j].toDouble()
            sk += k; skk += k * k; sp += p; skp += k * p
        }

        val det = m.toDouble() * skk - sk * sk
        if (abs(det) < 1e-12) return

        val t0 = (skk * sp - sk * skp) / det
        val period = (m.toDouble() * skp - sk * sp) / det
        if (period <= 0) return

        // Residuals and uncertainty
        var sumResidualSq = 0.0
        for (j in 0 until m) {
            val expected = t0 + indices[j] * period
            val residual = pairPositions[j].toDouble() - expected
            sumResidualSq += residual * residual
        }

        val sigma2 = if (m > 2) sumResidualSq / (m - 2) else 0.0
        // var(P) = σ² · N / det  where det = N·Σk² − (Σk)²
        val periodVariance = if (abs(det) > 1e-20 && m > 2) m.toDouble() / det * sigma2 else 0.0

        val framesToMicros = frameSamples.toDouble() / sampleRate * 1_000_000.0
        periodMicros = period * framesToMicros
        uncertaintyMicros = if (periodVariance > 0) sqrt(periodVariance) * framesToMicros else 0.0
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

    private fun readWav(file: File): ShortArray? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(44)
                val dataBytes = (raf.length() - 44).toInt()
                val maxBytes = sampleRate * 300 * 2
                val bytesToRead = minOf(dataBytes, maxBytes)
                if (dataBytes > maxBytes) raf.seek(44L + dataBytes - maxBytes)
                val bytes = ByteArray(bytesToRead)
                raf.readFully(bytes)
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                ShortArray(bytesToRead / 2) { buf.short }
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
