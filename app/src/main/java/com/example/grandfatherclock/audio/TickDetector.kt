package com.example.grandfatherclock.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Detects tick/tock beats from PCM audio and measures the pendulum period.
 *
 * 1. Band-pass 2–10 kHz, where ticks stand far above room noise, and compute the
 *    RMS envelope in ~1 ms hops.
 * 2. Acquire: once a few seconds are buffered, autocorrelate the onset strength
 *    to get a candidate period, then fold the onsets at half, equal and double
 *    that candidate. The full period is the shortest candidate whose fold shows
 *    exactly two beats (tick and tock) roughly half a period apart. The fold
 *    also gives each beat type's phase.
 * 3. Track: visit predicted beat slots in time order and take the strongest
 *    onset in a window around each prediction. A slot with nothing loud enough
 *    is a missed beat, and sounds outside the windows are ignored. Beat number
 *    and tick/tock identity come from the slot.
 * 4. Estimate: fit t = a[type] + P·k by robust least squares, with separate
 *    offsets for the two beat types and a shared period P.
 * 5. Uncertainty: the beats wander around the line in a correlated way (the
 *    escape wheel's per-tooth pattern, slow drift over minutes), so the formal
 *    least-squares error is combined with a slow-wander term estimated from the
 *    smoothed residuals.
 */
class TickDetector(
    private val sampleRate: Int = AudioCapture.SAMPLE_RATE,
    /** Clocks to recognise by beat rate and escape-wheel pattern. */
    private val profiles: List<ClockProfile> = ClockProfile.KNOWN,
) {

    var logger: SessionLogger? = null

    /** One beat for the timing trace: its offset from the fitted straight line. */
    data class TracePoint(
        val timeSeconds: Float,
        val residualMillis: Float,
        val isTick: Boolean,
        val inlier: Boolean,
    )

    /** A tracked beat. [k] counts full periods; [parity] 0 = tick, 1 = tock. */
    data class Beat(val k: Int, val parity: Int, val sample: Double, val snr: Double)

    data class State(
        val periodMicros: Double = 0.0,
        val uncertaintyMicros: Double = 0.0,
        val tickCount: Int = 0,
        /** Beats heard. */
        val beatCount: Int = 0,
        val lastBeatIsTick: Boolean = true,
        /** True only on the update where a new beat was detected. */
        val newBeat: Boolean = false,
        val elapsedSamples: Long = 0,
        val synced: Boolean = false,
        /** Tick-to-tock gap minus half the period, in µs. */
        val imbalanceMicros: Double = 0.0,
        val imbalanceUncertaintyMicros: Double = 0.0,
        /** True until the detector has found the clock's rhythm. */
        val listening: Boolean = true,
        /** Beat slots examined since the rhythm was found. */
        val beatsExpected: Int = 0,
        /** Fraction of the most recent beat slots in which a beat was heard. */
        val recentHitRate: Double = 0.0,
        val trace: List<TracePoint> = emptyList(),
        /** The known clock whose escape-wheel pattern the beats confirmed, or null. */
        val recognisedClock: ClockProfile? = null,
    )

    // ---- Envelope ----
    private val hop = sampleRate / 1000                       // 44 samples ≈ 1 ms
    private val ringSize = 1 shl 15                           // ~33 s of envelope
    private val envRing = FloatArray(ringSize)
    private var hopCount = 0
    private var hopSumSq = 0.0
    private var hopFill = 0
    private var totalSamples = 0L
    private val bandPass = arrayOf(
        Biquad.highPass(2000.0, 0.5412, sampleRate),
        Biquad.highPass(2000.0, 1.3066, sampleRate),
        Biquad.lowPass(10000.0, 0.7071, sampleRate),
    )

    private fun env(j: Int): Float = envRing[j and (ringSize - 1)]
    private fun hopsFor(seconds: Double) = (seconds * sampleRate / hop).roundToInt()
    private fun samplesFor(seconds: Double) = seconds * sampleRate
    private fun hopCenter(j: Double) = j * hop + hop / 2.0

    // ---- Noise floor: median envelope over the last 1.5 s ----
    private var noiseFloor = 1.0
    private val noiseWindowHops = hopsFor(1.5)
    private val noiseUpdateHops = 250

    // ---- Acquisition ----
    private var locked = false
    /** The previous lock attempt's result (a0, a1, P in samples), awaiting confirmation. */
    private var lastCandidate: DoubleArray? = null
    private var acqStartHop = 0
    private var lastLockAttemptHop = 0
    private val acqMinHops = hopsFor(6.0)
    private val acqMaxHops = hopsFor(20.0)
    private val minPeriodSeconds = 0.6
    private val maxPeriodSeconds = 3.2

    // ---- Onset flash before the rhythm is known ----
    private var lastFlashHop = -1_000_000
    private var flashParity = 1

    // ---- Tracking ----
    private val model = DoubleArray(3)                        // a0, a1 (samples), P (samples)
    private val nextK = IntArray(2)
    private var windowSamples = samplesFor(0.060)
    private val minWindowSamples = samplesFor(0.025)
    private val maxWindowSamples = samplesFor(0.060)
    private val acceptSnr = 3.5
    private val onsetFraction = 0.25
    private val beats = ArrayList<Beat>()
    private var slotsExamined = 0
    private val recentOutcomes = BooleanArray(20)
    private var recentCount = 0
    private var lastBeatParity = 0
    private var tickCount = 0
    private var consecutiveMisses = 0
    private val recentResiduals = Array(2) { DoubleArray(4) }
    private val recentResidualCount = IntArray(2)

    /** All tracked beats, in time order. */
    val detectedBeats: List<Beat> get() = beats

    // ---- Published estimate ----
    private var fit: Fit? = null
    private var periodMicros = 0.0
    private var uncertaintyMicros = 0.0
    private var imbalanceMicros = 0.0
    private var imbalanceUncertaintyMicros = 0.0
    private var trace: List<TracePoint> = emptyList()
    private var recognisedClock: ClockProfile? = null
    /** The recognised clock's tooth count has been confirmed by this session's beats. */
    private var toothConfirmed = false
    private var synced = false
    private val stateIntervalHops = 140

    // ================================================================
    //  Public API
    // ================================================================

    fun process(buffer: ShortArray, count: Int): State? = process(buffer, 0, count)

    fun process(buffer: ShortArray, bufferOffset: Int, count: Int): State? {
        var result: State? = null
        var beatInBuffer = false
        for (i in bufferOffset until bufferOffset + count) {
            var y = buffer[i].toDouble()
            for (f in bandPass) y = f.process(y)
            hopSumSq += y * y
            totalSamples++
            if (++hopFill == hop) {
                envRing[hopCount and (ringSize - 1)] = sqrt(hopSumSq / hop).toFloat()
                hopCount++
                hopSumSq = 0.0
                hopFill = 0
                val s = processHop()
                if (s != null) {
                    if (s.newBeat) beatInBuffer = true
                    result = s
                }
            }
        }
        // A beat in an earlier hop must still reach the UI if a later update
        // in the same buffer replaced it.
        if (beatInBuffer && result != null && !result.newBeat) result = result.copy(newBeat = true)
        return result
    }

    fun reset() {
        hopCount = 0; hopSumSq = 0.0; hopFill = 0; totalSamples = 0
        bandPass.forEach { it.reset() }
        noiseFloor = 1.0
        lastFlashHop = -1_000_000; flashParity = 1
        tickCount = 0
        restartAcquisition()
    }

    // ================================================================
    //  Per-hop processing
    // ================================================================

    private fun processHop(): State? {
        if (hopCount % noiseUpdateHops == 0 || hopCount == 50) updateNoiseFloor()

        var newBeat = false
        if (!locked) {
            newBeat = detectFlash()
            if (hopCount - acqStartHop >= acqMinHops && hopCount - lastLockAttemptHop >= hopsFor(1.0)) {
                lastLockAttemptHop = hopCount
                tryLock()
            }
        }
        var updated = false
        while (locked && nextSlotReady()) {
            if (processNextSlot()) { newBeat = true; updated = true }
            if (!locked) break
        }
        if (newBeat || updated || hopCount % stateIntervalHops == 0) return currentState(newBeat)
        return null
    }

    private fun currentState(newBeat: Boolean) = State(
        periodMicros = periodMicros,
        uncertaintyMicros = uncertaintyMicros,
        tickCount = tickCount,
        beatCount = beats.size,
        lastBeatIsTick = lastBeatParity == 0,
        newBeat = newBeat,
        elapsedSamples = totalSamples,
        synced = synced,
        imbalanceMicros = imbalanceMicros,
        imbalanceUncertaintyMicros = imbalanceUncertaintyMicros,
        listening = !locked,
        beatsExpected = slotsExamined,
        recentHitRate = recentHitRate(),
        trace = trace,
        recognisedClock = recognisedClock,
    )

    private fun updateNoiseFloor() {
        val n = minOf(noiseWindowHops, hopCount, ringSize)
        if (n < 20) return
        val v = FloatArray(n) { env(hopCount - n + it) }
        v.sort()
        noiseFloor = maxOf(1.0, v[n / 2].toDouble())
    }

    /** Flashes on loud onsets while the rhythm is still unknown, so the user sees the app hearing something. */
    private fun detectFlash(): Boolean {
        val j = hopCount - 2
        // The noise floor needs a moment of audio before it means anything.
        if (hopCount < 300) return false
        val e = env(j)
        if (e > 6 * noiseFloor && e >= env(j - 1) && e > env(j + 1) && j - lastFlashHop >= hopsFor(0.25)) {
            lastFlashHop = j
            flashParity = 1 - flashParity
            lastBeatParity = flashParity
            return true
        }
        return false
    }

    // ================================================================
    //  Acquisition: period and phases
    // ================================================================

    private fun restartAcquisition() {
        locked = false
        lastCandidate = null
        acqStartHop = hopCount
        lastLockAttemptHop = hopCount
        beats.clear()
        slotsExamined = 0
        recentCount = 0
        consecutiveMisses = 0
        recentResidualCount.fill(0)
        windowSamples = maxWindowSamples
        fit = null
        periodMicros = 0.0; uncertaintyMicros = 0.0
        imbalanceMicros = 0.0; imbalanceUncertaintyMicros = 0.0
        trace = emptyList()
        recognisedClock = null
        toothConfirmed = false
        synced = false
    }

    private fun tryLock() {
        val end = hopCount
        val start = maxOf(acqStartHop, end - acqMaxHops, end - ringSize + 1)
        val n = end - start
        // Onset strength: rises in log envelope, lightly smoothed.
        val raw = DoubleArray(n)
        var prev = ln(env(start) + 1.0)
        for (i in 1 until n) {
            val cur = ln(env(start + i) + 1.0)
            raw[i] = maxOf(0.0, cur - prev)
            prev = cur
        }
        val ons = DoubleArray(n) { i ->
            raw[i] + (if (i > 0) raw[i - 1] else 0.0) + (if (i < n - 1) raw[i + 1] else 0.0)
        }

        val ac = autocorrelation(ons)
        val lo = hopsFor(0.45)
        val hi = minOf(hopsFor(maxPeriodSeconds), n / 3)
        if (hi <= lo + 2) return
        var best = lo
        for (l in lo..hi) if (ac[l] > ac[best]) best = l
        val confidence = ac[best] / ac[0]
        val l = best + parabolicOffset(ac[best - 1], ac[best], ac[best + 1])

        for (cand in doubleArrayOf(l / 2, l, 2 * l)) {
            if (cand < hopsFor(minPeriodSeconds) || cand > hopsFor(maxPeriodSeconds) || n < 4 * cand) continue
            val (first, second) = foldPeaks(ons, cand, start) ?: continue
            val p = cand * hop
            val a0 = hopCenter(start + first)
            val a1 = hopCenter(start + second)
            // Lock only when two attempts a second apart agree, so a single
            // coincidence in the buffer can't set the rhythm.
            val prev = lastCandidate
            lastCandidate = doubleArrayOf(a0, a1, p)
            val agrees = prev != null && abs(prev[2] - p) < 0.005 * p &&
                abs(phaseDiff(prev[0], a0, p)) < samplesFor(0.015) &&
                abs(phaseDiff(prev[1], a1, p)) < samplesFor(0.015)
            logger?.log(if (agrees) "LOCK" else "LOCK_CANDIDATE",
                "period=%.1fms phases=%.1f/%.1fms after %.1fs (ac peak %.1fms conf=%.2f)"
                    .format(p / sampleRate * 1000, first * hop * 1000.0 / sampleRate,
                        second * hop * 1000.0 / sampleRate, n * hop.toDouble() / sampleRate,
                        l * hop * 1000.0 / sampleRate, confidence))
            if (!agrees) return
            model[0] = a0; model[1] = a1; model[2] = p
            nextK[0] = 0; nextK[1] = 0
            locked = true
            return
        }
        lastCandidate = null
        logger?.log("LOCK_WAIT", "no tick/tock pair in the fold yet (ac peak %.1fms conf=%.2f, %.1fs buffered)"
            .format(l * hop * 1000.0 / sampleRate, confidence, n * hop.toDouble() / sampleRate))
    }

    /** Difference between two phases modulo [period], in (-period/2, period/2]. */
    private fun phaseDiff(a: Double, b: Double, period: Double): Double {
        var d = (b - a) % period
        if (d > period / 2) d -= period
        if (d <= -period / 2) d += period
        return d
    }

    /**
     * Folds [ons] at [period] (in hops) and returns the two beats' phases (in hops,
     * earlier first) when the fold shows exactly two clear peaks between a quarter
     * and three quarters of a period apart; otherwise null.
     */
    private fun foldPeaks(ons: DoubleArray, period: Double, start: Int): Pair<Double, Double>? {
        val nbins = (period / 2).roundToInt()                 // 2 ms bins
        if (nbins < 8) return null
        val h = DoubleArray(nbins)
        for (i in ons.indices) {
            val b = ((i % period) / period * nbins).toInt().coerceIn(0, nbins - 1)
            h[b] += ons[i]
        }
        val sm = DoubleArray(nbins) { b ->
            var s = 0.0
            for (d in -2..2) s += h[(b + d + nbins) % nbins]
            s / 5
        }
        val sorted = sm.sortedArray()
        val med = sorted[nbins / 2]
        val sigma = 1.4826 * sm.map { abs(it - med) }.sorted()[nbins / 2] + 1e-12
        val max = sorted[nbins - 1]
        // A peak counts as a beat when it stands well clear of the fold's own
        // noise, so a quiet tock still counts next to a loud tick.
        val threshold = med + maxOf(6 * sigma, 0.15 * (max - med))

        val maxima = (0 until nbins).filter { b ->
            sm[b] >= sm[(b - 1 + nbins) % nbins] && sm[b] >= sm[(b + 1) % nbins]
        }.sortedByDescending { sm[it] }
        val minSep = 0.15 * nbins
        val peaks = mutableListOf<Int>()
        for (b in maxima) {
            if (peaks.all { circularDistance(it, b, nbins) >= minSep }) peaks += b
        }
        val strong = peaks.filter { sm[it] > threshold }.sorted()
        // Tick and tock sit half a period apart, give or take the clock's beat
        // error; a clock more than 10% of a period out of beat would stop. The
        // strongest such pair is taken, so other repeating sounds (such as a
        // secondary click after each beat) don't count as beats.
        val binHops = period / nbins
        var bestPair: Pair<Int, Int>? = null
        var bestWeaker = 0.0
        for (i in strong.indices) for (j in i + 1 until strong.size) {
            val sep = (strong[j] - strong[i]).toDouble() / nbins
            if (sep < 0.40 || sep > 0.60) continue
            val weaker = minOf(sm[strong[i]], sm[strong[j]])
            if (weaker <= bestWeaker) continue
            // A real beat sounds in most periods; a few loud sounds that happen
            // to line up do not.
            if (coverage(start, ons.size, (strong[i] + 0.5) * binHops, period) < MIN_LOCK_COVERAGE ||
                coverage(start, ons.size, (strong[j] + 0.5) * binHops, period) < MIN_LOCK_COVERAGE) continue
            bestWeaker = weaker
            bestPair = strong[i] to strong[j]
        }
        val (b0, b1) = bestPair ?: return null
        return Pair((b0 + 0.5) * binHops, (b1 + 0.5) * binHops)
    }

    /**
     * Fraction of periods in the buffer that have a loud onset within ±15 ms of
     * [phase] (in hops from [start]).
     */
    private fun coverage(start: Int, n: Int, phase: Double, period: Double): Double {
        val w = hopsFor(0.015)
        var periods = 0
        var heard = 0
        var m = 0
        while (true) {
            val c = (phase + m * period).roundToInt()
            if (c + w >= n) break
            if (c - w >= 0) {
                periods++
                var max = 0f
                for (j in c - w..c + w) max = maxOf(max, env(start + j))
                if (max >= 4 * noiseFloor) heard++
            }
            m++
        }
        return if (periods >= 3) heard.toDouble() / periods else 0.0
    }

    private fun circularDistance(a: Int, b: Int, n: Int): Int {
        val d = abs(a - b)
        return minOf(d, n - d)
    }

    private fun autocorrelation(x: DoubleArray): DoubleArray {
        val n = x.size
        val mean = x.average()
        var m = 1
        while (m < 2 * n) m = m shl 1
        val buf = DoubleArray(2 * m)
        for (i in 0 until n) buf[2 * i] = x[i] - mean
        fft(buf, m)
        for (i in 0 until m) {
            val re = buf[2 * i]; val im = buf[2 * i + 1]
            buf[2 * i] = re * re + im * im
            buf[2 * i + 1] = 0.0
        }
        // The power spectrum is real and even, so a forward FFT gives the
        // autocorrelation up to the factor m.
        fft(buf, m)
        return DoubleArray(n) { buf[2 * it] / m }
    }

    // ================================================================
    //  Tracking
    // ================================================================

    private fun nextParity(): Int =
        if (model[0] + nextK[0] * model[2] <= model[1] + nextK[1] * model[2]) 0 else 1

    private fun predicted(parity: Int, k: Int) = model[parity] + k * model[2]

    private fun nextSlotReady(): Boolean {
        val p = nextParity()
        return predicted(p, nextK[p]) + windowSamples + samplesFor(0.005) <= hopCount.toDouble() * hop
    }

    /** Examines the next beat slot; returns true when a beat was heard there. */
    private fun processNextSlot(): Boolean {
        val parity = nextParity()
        val k = nextK[parity]
        nextK[parity] = k + 1
        // The window follows where this beat type has recently landed relative
        // to the line, so excursions of the beat timing stay inside it. After a
        // run of empty slots it opens fully to find the beats again.
        val linePred = predicted(parity, k)
        val pred = linePred + localOffset(parity)
        val w = if (consecutiveMisses >= 3) maxWindowSamples else windowSamples
        val oldest = maxOf(1, hopCount - ringSize + 32)
        val j0 = maxOf(oldest, ((pred - w - hop / 2.0) / hop).toInt())
        val j1 = minOf(hopCount - 1, ((pred + w - hop / 2.0) / hop).toInt() + 1)
        if (j1 <= j0 + 2) return false

        var bestJ = j0
        var bestScore = -1.0
        for (j in j0..j1) {
            val rel = (hopCenter(j.toDouble()) - pred) / w
            val score = env(j) * (1 - 0.5 * rel * rel)
            if (score > bestScore) { bestScore = score; bestJ = j }
        }
        val peak = env(bestJ).toDouble()
        val snr = peak / noiseFloor
        slotsExamined++
        val heard = snr >= acceptSnr
        recordOutcome(heard)

        if (heard) {
            val thr = noiseFloor + onsetFraction * (peak - noiseFloor)
            var i = bestJ
            while (i > bestJ - 30 && i > oldest && env(i - 1) > thr) i--
            val e0 = env(i - 1).toDouble(); val e1 = env(i).toDouble()
            val frac = if (e1 > e0) ((thr - e0) / (e1 - e0)).coerceIn(0.0, 1.0) else 0.0
            val onset = hopCenter(i - 1 + frac)
            beats += Beat(k, parity, onset, snr)
            if (parity == 0) tickCount++
            lastBeatParity = parity
            consecutiveMisses = 0
            recentResiduals[parity][recentResidualCount[parity] % recentResiduals[parity].size] = onset - linePred
            recentResidualCount[parity]++
            refit()
            logger?.log("BEAT", "k=%d %s t=%.4fs snr=%.1f err=%+.1fms window=±%.0fms".format(
                k, if (parity == 0) "TICK" else "TOCK", onset / sampleRate, snr,
                (onset - pred) * 1000 / sampleRate, w * 1000 / sampleRate))
        } else {
            consecutiveMisses++
            logger?.log("MISS", "k=%d %s t=%.4fs best snr=%.1f".format(
                k, if (parity == 0) "TICK" else "TOCK", pred / sampleRate, snr))
        }

        // Many silent slots soon after locking mean the rhythm was found wrongly.
        // Later on, missed beats only lower the hit rate the user sees, because
        // an established session is worth keeping through a noisy stretch.
        if (beats.size < 30 && recentCount >= recentOutcomes.size && recentHitRate() < 0.4) {
            logger?.log("RELOCK", "heard %.0f%% of the last %d beat slots"
                .format(recentHitRate() * 100, recentOutcomes.size))
            restartAcquisition()
        }
        return heard
    }

    /** Median offset of this beat type's last few beats from the line at the time they were heard. */
    private fun localOffset(parity: Int): Double {
        val n = minOf(recentResidualCount[parity], recentResiduals[parity].size)
        if (n == 0) return 0.0
        val v = recentResiduals[parity].copyOf(n)
        v.sort()
        return v[n / 2].coerceIn(-maxWindowSamples, maxWindowSamples)
    }

    private fun recordOutcome(heard: Boolean) {
        recentOutcomes[recentCount % recentOutcomes.size] = heard
        recentCount++
    }

    private fun recentHitRate(): Double {
        val n = minOf(recentCount, recentOutcomes.size)
        if (n == 0) return 0.0
        var hits = 0
        for (i in 0 until n) if (recentOutcomes[i]) hits++
        return hits.toDouble() / n
    }

    private fun refit() {
        val profile = ClockProfile.forPeriod(model[2] / sampleRate, profiles)
        val f = fitBeats(beats.map { it.k }, beats.map { it.parity }, beats.map { it.sample }, profile,
            toothConfirmed) ?: return
        fit = f
        if (f.recognised != null && !toothConfirmed) {
            toothConfirmed = true
            logger?.log("CLOCK", "recognised the %s: its %d-tooth escape-wheel pattern matches the beats"
                .format(profile.name, profile.escapeWheelTeeth))
        }
        model[0] = f.a0; model[1] = f.a1; model[2] = f.linePeriod
        if (f.inliers >= 16) {
            windowSamples = (4 * f.residualSigma).coerceIn(minWindowSamples, maxWindowSamples)
        }
        val toMicros = 1e6 / sampleRate
        if (f.n0 >= 3 && f.n1 >= 3 && f.spanSamples >= 2 * f.period) {
            periodMicros = f.period * toMicros
            uncertaintyMicros = f.periodSigma * toMicros
            imbalanceMicros = f.imbalance * toMicros
            imbalanceUncertaintyMicros = f.imbalanceSigma * toMicros
            recognisedClock = f.recognised
            val secPerWeek = uncertaintyMicros / periodMicros * 604_800.0
            synced = secPerWeek <= SYNCED_SEC_PER_WEEK && recentHitRate() >= 0.8
        }
        trace = beats.mapIndexed { i, b ->
            TracePoint(
                timeSeconds = (b.sample / sampleRate).toFloat(),
                residualMillis = ((b.sample - (model[b.parity] + b.k * model[2])) * 1000 / sampleRate).toFloat(),
                isTick = b.parity == 0,
                inlier = f.inlier[i],
            )
        }
        if (beats.size % 20 == 0) {
            logger?.log("FIT", "period=%.1fµs ±%.1fµs imbalance=%.0f±%.0fµs rms=%.1fms beats=%d/%d window=±%.0fms clock=%s"
                .format(periodMicros, uncertaintyMicros, imbalanceMicros, imbalanceUncertaintyMicros,
                    f.residualSigma * 1000 / sampleRate, beats.size, slotsExamined,
                    windowSamples * 1000 / sampleRate, f.recognised?.name ?: "not recognised"))
        }
    }

    // ================================================================
    //  Fit: t = a[parity] + P·k
    // ================================================================

    private class Fit(
        /** The straight lines' offsets and shared period, used for predicting beats. */
        val a0: Double, val a1: Double, val linePeriod: Double,
        /** The best period estimate: per-tooth when the clock is recognised, else [linePeriod]. */
        val period: Double,
        /** The known clock whose tooth pattern the beats confirmed, or null. */
        val recognised: ClockProfile?,
        val inlier: BooleanArray, val inliers: Int,
        val n0: Int, val n1: Int,
        /** Robust scatter of the beats around the line, in samples. */
        val residualSigma: Double,
        /** Period uncertainty including slow wander, in samples. */
        val periodSigma: Double,
        val imbalance: Double, val imbalanceSigma: Double,
        val spanSamples: Double,
    )

    /** Beats must be in time order. Returns null until each beat type has at least two beats. */
    private fun fitBeats(
        k: List<Int>, par: List<Int>, t: List<Double>, profile: ClockProfile, toothConfirmed: Boolean,
    ): Fit? {
        val n = t.size
        if (n < 5) return null
        var inl = BooleanArray(n) { true }
        var line = lineFit(k, par, t, inl) ?: return null
        val floor = samplesFor(0.002)
        repeat(3) {
            val r = DoubleArray(n) { t[it] - (line[par[it]] + line[2] * k[it]) }
            val rIn = r.filterIndexed { i, _ -> inl[i] }
            val med = median(rIn)
            val sigma = 1.4826 * median(rIn.map { abs(it - med) })
            val cutoff = maxOf(5 * sigma, floor)
            val next = BooleanArray(n) { abs(r[it]) <= cutoff }
            line = lineFit(k, par, t, next) ?: return null
            inl = next
        }
        val a0 = line[0]; val a1 = line[1]; val p = line[2]
        val r = DoubleArray(n) { t[it] - (line[par[it]] + p * k[it]) }

        var n0 = 0; var n1 = 0; var ss = 0.0
        val kMean = DoubleArray(2); val cnt = IntArray(2)
        for (i in 0 until n) if (inl[i]) {
            if (par[i] == 0) n0++ else n1++
            ss += r[i] * r[i]
            kMean[par[i]] += k[i].toDouble(); cnt[par[i]]++
        }
        val m = n0 + n1
        if (n0 < 2 || n1 < 2 || m < 5) return null
        for (g in 0..1) kMean[g] /= cnt[g]
        var sxx = 0.0
        for (i in 0 until n) if (inl[i]) { val d = k[i] - kMean[par[i]]; sxx += d * d }
        val formal = sqrt(ss / (m - 3) / sxx)

        val rr = r.filterIndexed { i, _ -> inl[i] }
        val tIn = t.filterIndexed { i, _ -> inl[i] }
        val span = tIn.last() - tIn.first()

        // With a known clock whose tooth count the beats confirm, each tooth gets
        // its own offset, which cancels the escape-wheel pattern exactly. That
        // needs some teeth heard twice, so it starts after more than one turn.
        var period = p
        var periodFormal = formal
        var periodResiduals = rr
        var wanderPriorSeconds = ClockProfile.UNKNOWN.wanderSeconds
        var recognised: ClockProfile? = null
        val teeth = profile.escapeWheelTeeth
        if (teeth > 0 && span >= MIN_TOOTH_TURNS * teeth * p) {
            toothFit(k, par, t, inl, r, teeth, requireConfirmation = !toothConfirmed)?.let { tf ->
                period = tf.period
                periodFormal = tf.formal
                periodResiduals = tf.residuals
                wanderPriorSeconds = profile.wanderSeconds
                recognised = profile
            }
        }

        // Wander slower than the span looks like a straight trend, so it hides in
        // the slope and can't be seen in the residuals. A prior amplitude for such
        // wander, taken from how real clocks behave, covers it.
        val wander = slowWanderTerm(periodResiduals, span, period)
        val wanderPrior = samplesFor(wanderPriorSeconds) / span * period
        val periodSigma = UNCERTAINTY_SCALE *
            sqrt(periodFormal * periodFormal + wander * wander + wanderPrior * wanderPrior)

        // Imbalance: tick→tock gap minus half the period. Its error is dominated by
        // the tock-minus-tick difference of the escape wheel's repeating pattern.
        // Averaging a bounded repeating pattern over n beats leaves an error of
        // about amplitude × (pattern length / 2π) / n, i.e. a few times σ/n for a
        // ~30-beat pattern; a short window sees only part of the pattern, so the
        // factor is set higher. Beat-to-beat jitter adds the usual σ/√n, and the
        // floor covers where exactly a tick's sound counts as starting.
        val tickRes = HashMap<Int, Double>()
        for (i in 0 until n) if (inl[i] && par[i] == 0) tickRes[k[i]] = r[i]
        val d = ArrayList<Double>()
        for (i in 0 until n) if (inl[i] && par[i] == 1) tickRes[k[i]]?.let { d += r[i] - it }
        val imbalanceSigma = if (d.size >= 3) {
            val nd = d.size.toDouble()
            val pattern = IMBALANCE_PATTERN_FACTOR * std(d) / nd
            val jitter = std(List(d.size - 1) { d[it + 1] - d[it] }) / sqrt(2.0)
            val floor = samplesFor(IMBALANCE_FLOOR_SECONDS)
            sqrt(pattern * pattern + jitter * jitter / nd + floor * floor)
        } else 2 * std(rr)

        val rrMedian = median(rr)
        return Fit(
            a0, a1, p, period, recognised, inl, m, n0, n1,
            residualSigma = 1.4826 * median(rr.map { abs(it - rrMedian) }),
            periodSigma = periodSigma,
            imbalance = (a1 - a0) - p / 2,
            imbalanceSigma = imbalanceSigma,
            spanSamples = span,
        )
    }

    /**
     * Period error from slow wander: the change in the time-ordered residuals,
     * smoothed over ~20 s, across the span, divided by the span.
     */
    private fun slowWanderTerm(residuals: List<Double>, span: Double, period: Double): Double {
        val m = residuals.size
        if (m < 3 || span <= 0) return 0.0
        val w = maxOf(3, (samplesFor(LF_SMOOTH_SECONDS) / (period / 2)).roundToInt())
        val slow = if (m > 2 * w) {
            val sm = DoubleArray(m - w + 1)
            var s = 0.0
            for (i in 0 until m) {
                s += residuals[i]
                if (i >= w) s -= residuals[i - w]
                if (i >= w - 1) sm[i - w + 1] = s / w
            }
            std(sm.toList())
        } else {
            std(residuals) / sqrt(maxOf(1.0, m.toDouble() / w))
        }
        return sqrt(2.0) * slow / span * period
    }

    private class ToothFit(
        val period: Double, val formal: Double, val residuals: List<Double>,
        /** Scatter of beats around their tooth's line, and around the straight line, for the same beats. */
        val sigmaTooth: Double, val sigmaLine: Double,
    )

    /**
     * Fits t = a[beat type, tooth] + P·k with tooth = k mod [teeth]. Only teeth
     * heard at least twice inform P, so the per-tooth pattern cancels. With
     * [requireConfirmation], returns null unless the beats confirm the tooth
     * count. Beats of the same tooth must agree far better than beats around the
     * straight line do, and clearly better than with one tooth more or fewer:
     * the pattern changes smoothly from tooth to tooth, so a count that is off
     * by one also cancels much of it. The check is reliable over the first few
     * turns of the escape wheel; later, slow wander spreads same-tooth beats
     * apart too, so a confirmation holds for the rest of the session.
     */
    private fun toothFit(
        k: List<Int>, par: List<Int>, t: List<Double>, use: BooleanArray,
        lineResiduals: DoubleArray, teeth: Int, requireConfirmation: Boolean,
    ): ToothFit? {
        val fit = toothFitFor(k, par, t, use, lineResiduals, teeth) ?: return null
        if (requireConfirmation) {
            val lower = toothFitFor(k, par, t, use, lineResiduals, teeth - 1)
            val upper = toothFitFor(k, par, t, use, lineResiduals, teeth + 1)
            logger?.log("TOOTH_CHECK", "teeth=%d sigma=%.2fms line=%.2fms neighbours=%.2f/%.2fms beats=%d".format(
                teeth, fit.sigmaTooth * 1000 / sampleRate, fit.sigmaLine * 1000 / sampleRate,
                (lower?.sigmaTooth ?: Double.NaN) * 1000 / sampleRate,
                (upper?.sigmaTooth ?: Double.NaN) * 1000 / sampleRate, t.size))
            if (fit.sigmaTooth > TOOTH_CONFIRM_RATIO * fit.sigmaLine) return null
            for (alt in listOfNotNull(lower, upper)) {
                if (fit.sigmaTooth > TOOTH_NEIGHBOUR_RATIO * alt.sigmaTooth) return null
            }
        }
        return fit
    }

    private fun toothFitFor(
        k: List<Int>, par: List<Int>, t: List<Double>, use: BooleanArray,
        lineResiduals: DoubleArray, teeth: Int,
    ): ToothFit? {
        if (teeth < 2) return null
        val groups = 2 * teeth
        val cnt = IntArray(groups); val sk = DoubleArray(groups); val st = DoubleArray(groups)
        val g = IntArray(t.size) { par[it] * teeth + k[it] % teeth }
        for (i in t.indices) if (use[i]) { cnt[g[i]]++; sk[g[i]] += k[i].toDouble(); st[g[i]] += t[i] }
        var sxy = 0.0; var sxx = 0.0
        for (i in t.indices) if (use[i] && cnt[g[i]] >= 2) {
            val dk = k[i] - sk[g[i]] / cnt[g[i]]
            sxy += dk * (t[i] - st[g[i]] / cnt[g[i]]); sxx += dk * dk
        }
        if (sxx <= 0) return null
        val p = sxy / sxx
        val res = ArrayList<Double>()
        var ssTooth = 0.0; var ssLine = 0.0
        for (i in t.indices) if (use[i] && cnt[g[i]] >= 2) {
            val r = t[i] - (st[g[i]] / cnt[g[i]] + p * (k[i] - sk[g[i]] / cnt[g[i]]))
            res += r
            ssTooth += r * r
            ssLine += lineResiduals[i] * lineResiduals[i]
        }
        val repeated = (0 until groups).count { cnt[it] >= 2 }
        val dof = res.size - repeated - 1
        if (dof < 5) return null
        return ToothFit(p, sqrt(ssTooth / dof / sxx), res, sqrt(ssTooth / dof), sqrt(ssLine / res.size))
    }

    /** Least squares with one offset per beat type and a shared slope; returns [a0, a1, P]. */
    private fun lineFit(k: List<Int>, par: List<Int>, t: List<Double>, use: BooleanArray): DoubleArray? {
        val sk = DoubleArray(2); val st = DoubleArray(2); val c = IntArray(2)
        for (i in t.indices) if (use[i]) { sk[par[i]] += k[i].toDouble(); st[par[i]] += t[i]; c[par[i]]++ }
        if (c[0] < 1 || c[1] < 1) return null
        val km = DoubleArray(2) { sk[it] / c[it] }
        val tm = DoubleArray(2) { st[it] / c[it] }
        var sxy = 0.0; var sxx = 0.0
        for (i in t.indices) if (use[i]) {
            val dk = k[i] - km[par[i]]
            sxy += dk * (t[i] - tm[par[i]]); sxx += dk * dk
        }
        if (sxx <= 0) return null
        val p = sxy / sxx
        if (p <= 0) return null
        return doubleArrayOf(tm[0] - p * km[0], tm[1] - p * km[1], p)
    }

    // ================================================================
    //  Helpers
    // ================================================================

    private fun parabolicOffset(y0: Double, y1: Double, y2: Double): Double {
        val den = y0 - 2 * y1 + y2
        return if (abs(den) > 1e-12) (0.5 * (y0 - y2) / den).coerceIn(-0.5, 0.5) else 0.0
    }

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        return s[s.size / 2]
    }

    private fun std(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
    }

    private fun std(v: DoubleArray) = std(v.toList())

    /** Second-order IIR section, transposed direct form II. */
    private class Biquad(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double,
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        fun reset() { z1 = 0.0; z2 = 0.0 }

        companion object {
            fun highPass(f: Double, q: Double, sr: Int): Biquad {
                val w = 2 * PI * f / sr; val c = cos(w); val alpha = sin(w) / (2 * q); val a0 = 1 + alpha
                return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }

            fun lowPass(f: Double, q: Double, sr: Int): Biquad {
                val w = 2 * PI * f / sr; val c = cos(w); val alpha = sin(w) / (2 * q); val a0 = 1 + alpha
                return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }
        }
    }

    // ================================================================
    //  In-place radix-2 Cooley–Tukey FFT
    //  [data] is interleaved [re0, im0, re1, im1, …] of length 2·n.
    // ================================================================

    private fun fft(data: DoubleArray, n: Int) {
        var j = 0
        for (i in 0 until n) {
            if (j > i) {
                val tr = data[2 * j]; val ti = data[2 * j + 1]
                data[2 * j] = data[2 * i]; data[2 * j + 1] = data[2 * i + 1]
                data[2 * i] = tr; data[2 * i + 1] = ti
            }
            var m = n shr 1
            while (m >= 1 && j >= m) { j -= m; m = m shr 1 }
            j += m
        }
        var halfSize = 1
        while (halfSize < n) {
            val step = halfSize shl 1
            val angle = -PI / halfSize
            val wpr = cos(angle); val wpi = sin(angle)
            var wr = 1.0; var wi = 0.0
            for (m in 0 until halfSize) {
                var i = m
                while (i < n) {
                    val k = i + halfSize
                    val tr = wr * data[2 * k] - wi * data[2 * k + 1]
                    val ti = wr * data[2 * k + 1] + wi * data[2 * k]
                    data[2 * k] = data[2 * i] - tr; data[2 * k + 1] = data[2 * i + 1] - ti
                    data[2 * i] += tr; data[2 * i + 1] += ti
                    i += step
                }
                val tmp = wr * wpr - wi * wpi
                wi = wr * wpi + wi * wpr
                wr = tmp
            }
            halfSize = step
        }
    }

    companion object {
        /** The result counts as settled once its uncertainty is within this many seconds per week. */
        const val SYNCED_SEC_PER_WEEK = 10.0
        /** Share of periods in which each beat must be heard before the rhythm counts as found. */
        private const val MIN_LOCK_COVERAGE = 0.6
        /**
         * A clock's tooth count counts as confirmed when beats of the same tooth
         * scatter less than this fraction of the scatter around the straight line.
         */
        private const val TOOTH_CONFIRM_RATIO = 0.5
        /** ...and less than this fraction of the scatter with one tooth more or fewer. */
        private const val TOOTH_NEIGHBOUR_RATIO = 0.8
        /** Escape-wheel turns heard before the per-tooth model is tried (some teeth must be heard twice). */
        private const val MIN_TOOTH_TURNS = 1.2
        /** Smoothing span for estimating slow wander of the beats around the fitted line. */
        private const val LF_SMOOTH_SECONDS = 20.0
        /**
         * Calibration factor for the period uncertainty, tuned with
         * TickDetectorEvaluationTest so that errors against the offline references
         * come out at about one shown uncertainty (rms).
         */
        private const val UNCERTAINTY_SCALE = 1.0
        /**
         * Typical amplitude of slow beat-timing wander over minutes. Measured on
         * 15-minute recordings: about 3 ms for Nattich (81 BPM), 6.7 ms for
         * Medusa (104 BPM); the larger value is used so the uncertainty errs on the
         * safe side for a clock the app hasn't seen.
         */
        private const val WANDER_PRIOR_SECONDS = 0.006
        /** Error of the imbalance from its repeating pattern, in units of the pattern's scatter over beats heard. */
        private const val IMBALANCE_PATTERN_FACTOR = 8.0
        /** Ambiguity in where a beat's sound counts as starting, which limits the imbalance. */
        private const val IMBALANCE_FLOOR_SECONDS = 0.002
    }
}
