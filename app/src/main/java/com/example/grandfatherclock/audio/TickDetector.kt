package com.example.grandfatherclock.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Detects tick/tock beats from PCM audio and measures the pendulum period.
 *
 * Two-phase approach:
 * 1. Real-time: sliding 200ms window with 140ms advance, 20ms FFT frames.
 *    Each frame is analysed via FFT and the high-frequency band energy
 *    (≥ 2 kHz) is used for beat detection. Clock ticks are broadband
 *    transients that light up the full spectrum, while background noise
 *    (voice, handling) is concentrated below 2 kHz, giving a typical
 *    tick/noise ratio of 15–30× (vs 2–3× with plain RMS).
 * 2. WAV refinement: sample-level autocorrelation on the full recording,
 *    searching ±50 ms around the approximate period for maximum precision.
 *
 * The autocorrelation operates on a "beat signal" — the HF energy at
 * detected beat frames with all other frames zeroed, capped to prevent
 * loud transients from dominating.
 *
 * Tick-to-tick alignment: autocorrelation naturally produces a stronger
 * peak at the full tick-to-tick period than at the half-period
 * (beat-to-beat), because tick and tock have different amplitudes.
 */
class TickDetector(private val sampleRate: Int = AudioCapture.SAMPLE_RATE) {

    enum class Method { NONE, AUTOCORRELATION, WAV_REFINED }

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
    private val frameSamples = sampleRate / 50              // 20 ms = 882 @ 44 100
    private val windowFrames = 10                            // 200 ms
    private val slideFrames = 7                              // 140 ms
    private val overlapFrames = windowFrames - slideFrames   // 3 frames = 60 ms
    private val windowSamples = frameSamples * windowFrames
    private val slideSamples = frameSamples * slideFrames
    private val minBeatGapFrames = 20                        // 400 ms between beats

    // ---- FFT for high-frequency energy ----
    private val fftSize = 1024                               // next power-of-2 ≥ frameSamples
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

    // ---- HF-energy history (growable primitive array) ----
    private var energyData = DoubleArray(2000)
    private var energyCount = 0
    private var isFirstWindow = true

    // ---- Beat signal for ACF (parallel to energyData, non-zero only at beats) ----
    private var beatSignal = DoubleArray(2000)
    private val energyCap = 20_000.0

    // ---- Beat detection ----
    private var beatCount = 0
    private var tickCount = 0
    private var lastBeatIsTick = true
    private var lastBeatFrame = -100

    // ---- Period estimation ----
    private var periodMicros = 0.0
    private var uncertaintyMicros = 0.0
    private var method = Method.NONE
    private var synced = false

    private var acCounter = 0
    private val acInterval = 6

    private val recentPeriods = ArrayDeque<Double>(25)
    private val maxRecentPeriods = 20

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
        if (beats.size < 6) return null

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
        val tplLen = frameSamples                           // 882 samples = 20 ms
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
        energyData = DoubleArray(2000)
        beatSignal = DoubleArray(2000)
        energyCount = 0
        isFirstWindow = true
        beatCount = 0
        tickCount = 0
        lastBeatIsTick = true
        lastBeatFrame = -100
        periodMicros = 0.0
        uncertaintyMicros = 0.0
        method = Method.NONE
        synced = false
        acCounter = 0
        recentPeriods.clear()
    }

    // ================================================================
    //  Window analysis
    // ================================================================

    private fun analyzeWindow(): State {
        // Compute high-frequency energy for each 20 ms frame via FFT
        val frameEnergy = DoubleArray(windowFrames) { f ->
            computeHfEnergy(f * frameSamples)
        }

        // Append new frames to history
        val startFrame = if (isFirstWindow) 0 else overlapFrames
        for (f in startFrame until windowFrames) {
            if (energyCount >= energyData.size) {
                energyData = energyData.copyOf(energyData.size * 2)
                beatSignal = beatSignal.copyOf(beatSignal.size * 2)
            }
            energyData[energyCount] = frameEnergy[f]
            beatSignal[energyCount] = 0.0
            energyCount++
        }
        isFirstWindow = false

        val newBeat = detectSpike(frameEnergy)

        acCounter++
        if (acCounter >= acInterval && energyCount >= 100) {
            acCounter = 0
            estimatePeriodFromAC()
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
        if (peak < median * 3.0 || peak < 1000.0) return false

        var peakFrame = 0
        for (f in 1 until windowFrames) {
            if (energy[f] > energy[peakFrame]) peakFrame = f
        }

        val threshold = median + (peak - median) * 0.3

        var loudCount = 0
        for (f in 0 until windowFrames) {
            if (energy[f] > threshold) loudCount++
        }
        if (loudCount > 2) return false

        var spikeStart = peakFrame
        var spikeEnd = peakFrame
        if (peakFrame > 0 && energy[peakFrame - 1] > threshold) spikeStart = peakFrame - 1
        if (peakFrame < windowFrames - 1 && energy[peakFrame + 1] > threshold) spikeEnd = peakFrame + 1

        // Strict silence on both sides (3-frame overlap guarantees interior)
        if (spikeStart < 1 || spikeEnd > windowFrames - 2) return false
        if (energy[spikeStart - 1] >= threshold) return false
        if (energy[spikeEnd + 1] >= threshold) return false

        val globalFrame = energyCount - windowFrames + peakFrame
        if (globalFrame - lastBeatFrame < minBeatGapFrames) return false

        beatCount++
        lastBeatIsTick = (beatCount % 2 == 1)
        if (lastBeatIsTick) tickCount++
        lastBeatFrame = globalFrame

        val windowBase = energyCount - windowFrames
        for (f in spikeStart..spikeEnd) {
            val gf = windowBase + f
            if (gf in 0 until energyCount) {
                beatSignal[gf] = minOf(energyData[gf], energyCap)
            }
        }
        return true
    }

    // ================================================================
    //  Autocorrelation period estimation
    // ================================================================

    private fun estimatePeriodFromAC() {
        val n = energyCount
        val acMinLag = 10
        val searchMinLag = 20
        val maxLag = minOf(n / 3, 200)
        if (maxLag <= searchMinLag + 2) return

        var mean = 0.0
        for (i in 0 until n) mean += beatSignal[i]
        mean /= n

        val acf = DoubleArray(maxLag + 1)
        for (lag in acMinLag..maxLag) {
            var sum = 0.0
            for (i in 0 until n - lag) {
                sum += (beatSignal[i] - mean) * (beatSignal[i + lag] - mean)
            }
            acf[lag] = sum / (n - lag)
        }

        // Find the maximum ACF value for the significance threshold
        var maxAcf = 0.0
        for (lag in searchMinLag..maxLag) {
            if (acf[lag] > maxAcf) maxAcf = acf[lag]
        }
        if (maxAcf <= 0) return
        val peakThreshold = maxAcf * 0.1

        // Find the FIRST significant peak. This is the fundamental
        // (beat-to-beat) period. Using "global max" is wrong because
        // higher harmonics of the beat pattern can have stronger ACF
        // values due to unbiased normalization and edge effects.
        var firstPeak = -1
        for (lag in searchMinLag + 1 until maxLag) {
            if (acf[lag] > acf[lag - 1] && acf[lag] >= acf[lag + 1] && acf[lag] > peakThreshold) {
                firstPeak = lag
                break
            }
        }
        if (firstPeak < 0) return

        // Tick-to-tick = 2× beat-to-beat. Find the actual ACF peak
        // nearest to 2× the fundamental for parabolic refinement.
        val dblTarget = firstPeak * 2
        val tickToTickLag: Int
        if (dblTarget + 1 <= maxLag) {
            val lo = maxOf(searchMinLag, dblTarget - 5)
            val hi = minOf(maxLag, dblTarget + 5)
            var best = lo
            for (lag in lo..hi) if (acf[lag] > acf[best]) best = lag
            tickToTickLag = best
        } else {
            // 2× out of range — refine the fundamental and double it
            val refined = parabolicRefineACF(acf, firstPeak, acMinLag, maxLag) * 2.0
            updatePeriod(refined * frameSamples.toDouble() / sampleRate * 1_000_000.0)
            return
        }

        val refined = parabolicRefineACF(acf, tickToTickLag, acMinLag, maxLag)
        updatePeriod(refined * frameSamples.toDouble() / sampleRate * 1_000_000.0)
    }

    private fun updatePeriod(newPeriodMicros: Double) {
        periodMicros = newPeriodMicros
        method = Method.AUTOCORRELATION

        recentPeriods.addLast(newPeriodMicros)
        if (recentPeriods.size > maxRecentPeriods) recentPeriods.removeFirst()

        if (recentPeriods.size >= 5) {
            val avg = recentPeriods.average()
            var varSum = 0.0
            for (p in recentPeriods) varSum += (p - avg) * (p - avg)
            val std = sqrt(varSum / (recentPeriods.size - 1))
            uncertaintyMicros = 3.0 * std / sqrt(recentPeriods.size.toDouble())
            synced = std < periodMicros * 0.001
        }
    }

    // ================================================================
    //  Helpers
    // ================================================================

    private fun parabolicRefineACF(acf: DoubleArray, peak: Int, minIdx: Int, maxIdx: Int): Double {
        if (peak <= minIdx || peak >= maxIdx) return peak.toDouble()
        val ym1 = acf[peak - 1]; val y0 = acf[peak]; val yp1 = acf[peak + 1]
        val denom = 2.0 * (2.0 * y0 - yp1 - ym1)
        return if (denom != 0.0) peak + (yp1 - ym1) / denom else peak.toDouble()
    }

    private fun parabolicRefineArray(arr: DoubleArray, peak: Int): Double {
        if (peak <= 0 || peak >= arr.size - 1) return peak.toDouble()
        val ym1 = arr[peak - 1]; val y0 = arr[peak]; val yp1 = arr[peak + 1]
        val denom = 2.0 * (2.0 * y0 - yp1 - ym1)
        return if (denom != 0.0) peak + (yp1 - ym1) / denom else peak.toDouble()
    }

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
