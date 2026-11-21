package com.example.grandfatherclock.audio

import kotlin.math.roundToInt

/**
 * What is known about a particular clock's timing beyond its rate, measured from
 * long recordings with tools/reference.py and TickDetectorEvaluationTest.
 *
 * A clock is recognised by its beat rate, and its escape-wheel tooth count is
 * then checked against the beats themselves before the profile is used (see
 * [TickDetector]), so a different clock with the same beat rate falls back to
 * [UNKNOWN].
 */
data class ClockProfile(
    val name: String,
    /** Beats per minute, counting ticks and tocks. */
    val bpm: Int,
    /**
     * Teeth on the escape wheel. Each tooth shifts its beats by a repeatable
     * amount, so the timing pattern repeats every this many periods. 0 = unknown.
     */
    val escapeWheelTeeth: Int,
    /** Typical amplitude of slow beat-timing wander over minutes, in seconds. */
    val wanderSeconds: Double,
) {
    companion object {
        /** A clock without a profile: no tooth pattern assumed, cautious wander. */
        val UNKNOWN = ClockProfile("unknown clock", 0, 0, 0.006)

        /**
         * The two clocks this app was tuned on: Nattich (81 BPM) and Medusa
         * (104 BPM). Tooth counts come from the beat timing's autocorrelation;
         * wander amplitudes from how much the period measured over 2-minute
         * windows varies across 15-minute recordings (made 2026-03-15 and
         * 2026-11-21).
         */
        val KNOWN = listOf(
            ClockProfile("Nattich", bpm = 81, escapeWheelTeeth = 30, wanderSeconds = 0.0035),
            ClockProfile("Medusa", bpm = 104, escapeWheelTeeth = 26, wanderSeconds = 0.0065),
        )

        /** The name of the known clock with this beat rate, or e.g. "81 BPM" for another clock. */
        fun nameForBpm(bpm: Int): String = KNOWN.firstOrNull { it.bpm == bpm }?.name ?: "$bpm BPM"

        fun forPeriod(periodSeconds: Double, profiles: List<ClockProfile> = KNOWN): ClockProfile {
            val bpm = (120.0 / periodSeconds).roundToInt()
            return profiles.firstOrNull { it.bpm == bpm } ?: UNKNOWN
        }
    }
}
