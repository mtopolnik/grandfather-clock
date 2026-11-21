package com.example.grandfatherclock.audio

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Replays every recording listed in `tools/eval/references.json` through
 * [TickDetector] and reports how the live estimate compares with the offline
 * reference (computed by `tools/reference.py`) as listening time grows.
 *
 * For each checkpoint it reports the actual error, the uncertainty the app would
 * show, and z = error / shown uncertainty. An honest uncertainty gives a z rms
 * near 1 and about 5% of |z| above 2. Long recordings are also replayed as
 * several sessions starting at different offsets, since the clock's own timing
 * pattern makes the result depend on where in its cycle a session starts.
 *
 * Recordings are looked up under EVAL_ROOT (default ~/Desktop/grandfather-clock).
 * The test is skipped when the references file or recordings are missing.
 * The report is printed and written to app/build/reports/evaluation.txt.
 */
class TickDetectorEvaluationTest {

    private data class Reference(
        val path: String,
        val durationSeconds: Double,
        val periodMicros: Double,
        val imbalanceMicros: Double,
        val beatsExpected: Int,
    )

    private data class Checkpoint(
        val seconds: Int,
        val periodMicros: Double,
        val uncertaintyMicros: Double,
        val imbalanceMicros: Double,
        val imbalanceUncertaintyMicros: Double,
        val synced: Boolean,
        val recognised: Boolean,
    )

    private data class Session(
        val ref: Reference,
        val startSeconds: Int,
        val checkpoints: List<Checkpoint>,
        val beatCount: Int,
        val finalState: TickDetector.State?,
    )

    private val checkpointSeconds = listOf(10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 300, 420)
    private val offsetSessionLength = 240
    private val offsetStep = 30

    @Test
    fun evaluateAllRecordings() {
        val projectRoot = System.getProperty("project.root") ?: ""
        val refFile = File(projectRoot, "tools/eval/references.json")
        assumeTrue("No references file at $refFile", refFile.exists())
        val root = File(
            System.getenv("EVAL_ROOT")?.takeIf { it.isNotEmpty() }
                ?: "${System.getProperty("user.home")}/Desktop/grandfather-clock"
        )
        val refs = parseReferences(refFile.readText()).filter { File(root, it.path).exists() }
        assumeTrue("No recordings found under $root", refs.isNotEmpty())

        // EVAL_LOG=<path>@<startSeconds> replays one session with the detector's
        // session log written to app/build/reports/eval-log/session.log.
        System.getenv("EVAL_LOG")?.takeIf { it.isNotEmpty() }?.let { spec ->
            val path = spec.substringBeforeLast('@')
            val start = spec.substringAfterLast('@', "0").toInt()
            val ref = refs.first { it.path == path }
            val samples = readWav(File(root, ref.path))
            val logDir = File(projectRoot, "app/build/reports/eval-log").apply { mkdirs() }
            val logger = SessionLogger(logDir)
            val s = runSession(ref, samples, start, samples.size, logger)
            logger.close()
            println("Logged ${ref.path}@$start: final ${s.finalState?.periodMicros} µs, beats ${s.beatCount}")
            return
        }

        val sessions = mutableListOf<Session>()
        for (ref in refs) {
            val samples = readWav(File(root, ref.path))
            sessions += runSession(ref, samples, 0, samples.size)
            if (ref.durationSeconds >= offsetSessionLength + offsetStep) {
                var start = offsetStep
                while (start + offsetSessionLength <= ref.durationSeconds) {
                    val from = start * SAMPLE_RATE
                    val to = minOf(samples.size, from + offsetSessionLength * SAMPLE_RATE)
                    sessions += runSession(ref, samples, start, to)
                    start += offsetStep
                }
            }
        }

        val report = buildReport(sessions)
        println(report)
        val out = File(projectRoot, "app/build/reports/evaluation.txt")
        out.parentFile?.mkdirs()
        out.writeText(report)
        println("Report: ${out.absolutePath}")
    }

    /**
     * A clock is recognised only when its beats confirm the profile's tooth count,
     * so another clock with the same beat rate but a different escape wheel falls
     * back to the generic model. Checked by giving each long recording profiles
     * with the right and with wrong tooth counts.
     */
    @Test
    fun recognitionNeedsTheRightToothCount() {
        val root = File(System.getenv("EVAL_ROOT")?.takeIf { it.isNotEmpty() }
            ?: "${System.getProperty("user.home")}/Desktop/grandfather-clock")
        val cases = listOf(
            "recordings/2026-11-21_11-37-38_Nattich/clock_recording.wav" to (81 to 30),
            "Nattich_recording.wav" to (81 to 30),
            "recordings/2026-11-21_11-09-54_Medusa/clock_recording.wav" to (104 to 26),
        ).filter { File(root, it.first).exists() }
        assumeTrue("Recordings not found under $root", cases.isNotEmpty())
        val failures = mutableListOf<String>()
        for ((path, clock) in cases) {
            val (bpm, teeth) = clock
            val samples = readWav(File(root, path))
            val end = minOf(samples.size, 300 * SAMPLE_RATE)
            for (tried in listOf(teeth - 4, teeth - 2, teeth - 1, teeth, teeth + 1, teeth + 2, teeth + 4)) {
                val detector = TickDetector(profiles = listOf(ClockProfile("test", bpm, tried, 0.003)))
                var firstRecognised = -1.0
                var offset = 0
                while (offset < end) {
                    val n = minOf(882, end - offset)
                    val s = detector.process(samples, offset, n)
                    if (s?.recognisedClock != null && firstRecognised < 0) firstRecognised = offset.toDouble() / SAMPLE_RATE
                    offset += n
                }
                val ok = if (tried == teeth) firstRecognised > 0 else firstRecognised < 0
                println("%-60s teeth %2d (true %2d): %s".format(path, tried, teeth,
                    if (firstRecognised > 0) "recognised at %.0fs".format(firstRecognised) else "not recognised"))
                if (!ok) failures += "$path with $tried teeth"
            }
        }
        assertTrue("Wrong recognition: $failures", failures.isEmpty())
    }

    /** Room noise with irregular clicks has no rhythm, so the detector must keep listening. */
    @Test
    fun noClockNeverLocks() {
        val rnd = java.util.Random(1)
        val seconds = 60
        val samples = DoubleArray(seconds * SAMPLE_RATE) { rnd.nextGaussian() * 60 }
        repeat(80) {
            val at = rnd.nextInt(samples.size - 400)
            for (j in 0 until 400) samples[at + j] += rnd.nextGaussian() * 1500 * kotlin.math.exp(-j / 80.0)
        }
        val pcm = ShortArray(samples.size) { samples[it].coerceIn(-32768.0, 32767.0).toInt().toShort() }
        val detector = TickDetector()
        var offset = 0
        while (offset < pcm.size) {
            val n = minOf(882, pcm.size - offset)
            val s = detector.process(pcm, offset, n)
            if (s != null) {
                assertTrue(
                    "Locked onto noise at %.1fs (period %.0fµs)".format(offset.toDouble() / SAMPLE_RATE, s.periodMicros),
                    s.listening && s.periodMicros == 0.0,
                )
            }
            offset += n
        }
    }

    private fun runSession(
        ref: Reference, samples: ShortArray, startSeconds: Int, endSample: Int,
        logger: SessionLogger? = null,
    ): Session {
        val detector = TickDetector()
        detector.logger = logger
        val chunk = 882
        var offset = startSeconds * SAMPLE_RATE
        val begin = offset
        var last: TickDetector.State? = null
        val checkpoints = mutableListOf<Checkpoint>()
        var nextCp = 0
        while (offset < endSample) {
            val count = minOf(chunk, endSample - offset)
            detector.process(samples, offset, count)?.let { last = it }
            offset += count
            val elapsed = (offset - begin).toDouble() / SAMPLE_RATE
            while (nextCp < checkpointSeconds.size && elapsed >= checkpointSeconds[nextCp]) {
                val s = last
                checkpoints += Checkpoint(
                    checkpointSeconds[nextCp],
                    s?.periodMicros ?: 0.0, s?.uncertaintyMicros ?: 0.0,
                    s?.imbalanceMicros ?: 0.0, s?.imbalanceUncertaintyMicros ?: 0.0, s?.synced ?: false,
                    s?.recognisedClock != null,
                )
                nextCp++
            }
        }
        return Session(ref, startSeconds, checkpoints, last?.beatCount ?: 0, last)
    }

    private fun buildReport(sessions: List<Session>): String {
        val sb = StringBuilder()
        sb.appendLine("=== Per recording (session from the start) ===")
        sb.appendLine("%-42s %5s %12s %10s %10s %13s".format(
            "recording", "dur", "ref µs", "live err", "live ±", "beats/expect"))
        for (s in sessions.filter { it.startSeconds == 0 }) {
            val f = s.finalState
            val liveErr = if (f != null && f.periodMicros > 0) "%+.1f".format(f.periodMicros - s.ref.periodMicros) else "none"
            val liveUnc = if (f != null && f.periodMicros > 0) "%.1f".format(f.uncertaintyMicros) else "-"
            sb.appendLine("%-42s %5.0f %12.1f %10s %10s %6d/%-6d".format(
                s.ref.path.take(42), s.ref.durationSeconds, s.ref.periodMicros, liveErr, liveUnc,
                s.beatCount, s.ref.beatsExpected))
        }

        periodTable(sb, "all sessions", sessions)
        for (bpm in sessions.map { bpmOf(it.ref) }.distinct().sorted()) {
            val name = ClockProfile.KNOWN.firstOrNull { it.bpm == bpm }?.let { "${it.name} ($bpm BPM)" } ?: "$bpm BPM clock"
            periodTable(sb, name, sessions.filter { bpmOf(it.ref) == bpm })
        }
        val fresh = sessions.filter { it.ref.path.startsWith("recordings/") }
        if (fresh.isNotEmpty()) periodTable(sb, "recordings/ only (made after the calibration)", fresh)

        sb.appendLine()
        sb.appendLine("=== Imbalance (magnitude, since which beat is called tick is arbitrary) ===")
        sb.appendLine("%5s %4s %11s %12s %7s %8s".format("T(s)", "n", "rms err µs", "median ± µs", "z rms", "|z|>2"))
        for (t in checkpointSeconds) {
            val scored = sessions.mapNotNull { s -> s.checkpoints.find { it.seconds == t }?.let { s to it } }
                .filter { (s, c) -> c.periodMicros > 0 && s.ref.durationSeconds >= 2 * t }
            if (scored.isEmpty()) continue
            val errs = scored.map { (s, c) -> abs(c.imbalanceMicros) - abs(s.ref.imbalanceMicros) }
            val zs = scored.filter { it.second.imbalanceUncertaintyMicros > 0 }
                .map { (s, c) -> (abs(c.imbalanceMicros) - abs(s.ref.imbalanceMicros)) / c.imbalanceUncertaintyMicros }
            val uncs = scored.map { it.second.imbalanceUncertaintyMicros }.sorted()
            sb.appendLine("%5d %4d %11.0f %12.0f %7s %8s".format(
                t, scored.size, rms(errs), uncs[uncs.size / 2],
                if (zs.isEmpty()) "-" else "%.2f".format(rms(zs)),
                if (zs.isEmpty()) "-" else "%.0f%%".format(100.0 * zs.count { abs(it) > 2 } / zs.size)))
        }

        sb.appendLine()
        sb.appendLine("=== Gross failures (checkpoints off by more than 0.5%) ===")
        var any = false
        for (s in sessions) {
            val bad = s.checkpoints.filter {
                it.periodMicros > 0 && abs(it.periodMicros - s.ref.periodMicros) > 0.005 * s.ref.periodMicros
            }
            if (bad.isNotEmpty()) {
                any = true
                sb.appendLine("%s @%ds: %s".format(s.ref.path, s.startSeconds,
                    bad.joinToString { "%ds→%.0fµs".format(it.seconds, it.periodMicros) }))
            }
        }
        if (!any) sb.appendLine("none")
        return sb.toString()
    }

    private fun bpmOf(ref: Reference) = (120_000_000.0 / ref.periodMicros).roundToInt()

    private fun periodTable(sb: StringBuilder, title: String, sessions: List<Session>) {
        sb.appendLine()
        sb.appendLine("=== Live estimate vs listening time, $title ===")
        sb.appendLine("Only recordings at least twice as long as the checkpoint count toward error and z.")
        sb.appendLine("gross = estimate off by more than 0.5% (wrong period); none = no estimate yet.")
        sb.appendLine("%5s %4s %6s %6s %11s %12s %7s %8s %7s %6s".format(
            "T(s)", "n", "none", "gross", "rms err µs", "median ± µs", "z rms", "|z|>2", "synced", "recog"))
        for (t in checkpointSeconds) {
            val cps = sessions.mapNotNull { s -> s.checkpoints.find { it.seconds == t }?.let { s to it } }
            if (cps.isEmpty()) continue
            val recog = cps.count { it.second.recognised }
            val none = cps.count { it.second.periodMicros <= 0 }
            val gross = cps.count { (s, c) ->
                c.periodMicros > 0 && abs(c.periodMicros - s.ref.periodMicros) > 0.005 * s.ref.periodMicros
            }
            val scored = cps.filter { (s, c) ->
                c.periodMicros > 0 && s.ref.durationSeconds >= 2 * t &&
                    abs(c.periodMicros - s.ref.periodMicros) <= 0.005 * s.ref.periodMicros
            }
            val errs = scored.map { (s, c) -> c.periodMicros - s.ref.periodMicros }
            val uncs = scored.map { it.second.uncertaintyMicros }
            val zs = scored.filter { it.second.uncertaintyMicros > 0 }
                .map { (s, c) -> (c.periodMicros - s.ref.periodMicros) / c.uncertaintyMicros }
            val synced = cps.count { it.second.synced }
            sb.appendLine("%5d %4d %6d %6d %11s %12s %7s %8s %7d %6d".format(
                t, cps.size, none, gross,
                if (errs.isEmpty()) "-" else "%.1f".format(rms(errs)),
                if (uncs.isEmpty()) "-" else "%.1f".format(uncs.sorted()[uncs.size / 2]),
                if (zs.isEmpty()) "-" else "%.2f".format(rms(zs)),
                if (zs.isEmpty()) "-" else "%.0f%%".format(100.0 * zs.count { abs(it) > 2 } / zs.size),
                synced, recog))
        }
    }

    private fun rms(v: List<Double>) = sqrt(v.sumOf { it * it } / v.size)

    private fun readWav(file: File): ShortArray = RandomAccessFile(file, "r").use { raf ->
        raf.seek(44)
        val bytes = ByteArray((raf.length() - 44).toInt())
        raf.readFully(bytes)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        ShortArray(bytes.size / 2) { buf.short }
    }

    /** Parses the flat `{"path": {"key": number, ...}, ...}` layout written by reference.py. */
    private fun parseReferences(json: String): List<Reference> {
        val entry = Regex("\"([^\"]+)\"\\s*:\\s*\\{([^}]*)\\}")
        val field = Regex("\"(\\w+)\"\\s*:\\s*(-?[0-9.eE+-]+)")
        return entry.findAll(json).map { m ->
            val f = field.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            Reference(
                path = m.groupValues[1],
                durationSeconds = f.getValue("durationSeconds"),
                periodMicros = f.getValue("periodMicros"),
                imbalanceMicros = f.getValue("imbalanceMicros"),
                beatsExpected = f.getValue("beatsExpected").toInt(),
            )
        }.toList()
    }

    companion object {
        private const val SAMPLE_RATE = 44100
    }
}
