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
    /**
     * Steady waves in the beat timing, each with a fixed period. Sessions of
     * [TickDetector.WAVE_FIT_SECONDS] or longer measure each wave's size and
     * phase from their beats and take the waves out of the beat times before
     * fitting the rate; shorter sessions count them in the uncertainty as waves
     * of unknown phase.
     */
    val waves: List<Wave> = emptyList(),
    /**
     * Irregular slow wander, which can't be taken out, counted in the
     * uncertainty as a wave of unknown phase. Shorter-term wander is covered by
     * [wanderSeconds]; whichever of the two terms is larger counts.
     */
    val irregularWander: Wave? = null,
) {
    /** A wave in the beat timing: beats run up to [amplitudeSeconds] late, then early, once per [periodSeconds]. */
    data class Wave(val periodSeconds: Double, val amplitudeSeconds: Double)

    companion object {
        /** A clock without a profile: no tooth pattern assumed, cautious wander. */
        val UNKNOWN = ClockProfile("unknown clock", 0, 0, 0.006)

        /**
         * The two clocks this app was tuned on: Nattich (81 BPM) and Medusa
         * (104 BPM). Tooth counts come from the beat timing's autocorrelation;
         * wander amplitudes from how much the period measured over 2-minute
         * windows varies across 15-minute recordings (made 2026-03-15 and
         * 2026-11-21). Medusa's waves come from its overnight recording of
         * 2026-11-21/22: one locked to the hour (15 ms) and one of 48.75 minutes
         * (17 ms), both steady in phase all night, and one of 7.5 minutes, also
         * locked to the hour, 3–10 ms in sessions on 2026-11-21 and that night.
         * What remains wanders like a 40-minute wave of 9 ms.
         */
        val KNOWN = listOf(
            ClockProfile("Nattich", bpm = 81, escapeWheelTeeth = 30, wanderSeconds = 0.0035),
            ClockProfile(
                "Medusa", bpm = 104, escapeWheelTeeth = 26, wanderSeconds = 0.0065,
                waves = listOf(Wave(3600.0, 0.015), Wave(2925.0, 0.017), Wave(450.0, 0.007)),
                irregularWander = Wave(2400.0, 0.009),
            ),
        )

        /** The name of the known clock with this beat rate, or e.g. "81 BPM" for another clock. */
        fun nameForBpm(bpm: Int): String = KNOWN.firstOrNull { it.bpm == bpm }?.name ?: "$bpm BPM"

        fun forPeriod(periodSeconds: Double, profiles: List<ClockProfile> = KNOWN): ClockProfile {
            val bpm = (120.0 / periodSeconds).roundToInt()
            return profiles.firstOrNull { it.bpm == bpm } ?: UNKNOWN
        }
    }
}
