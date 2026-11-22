package com.example.grandfatherclock.audio

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

class TickDetectorSimulationTest {

    @Test
    fun simulateFromWav() {
        val envPath = System.getenv("WAV_FILE")?.takeIf { it.isNotEmpty() }
        val projectRoot = System.getProperty("project.root") ?: ""
        val home = System.getProperty("user.home") ?: ""
        val candidates = listOfNotNull(
            envPath,
            "$projectRoot/clock_recording.wav".takeIf { projectRoot.isNotEmpty() },
            "$home/Desktop/clock_recording.wav".takeIf { home.isNotEmpty() },
        )
        val wavFile = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("WAV file not found, tried: $candidates")
        println("Using WAV: ${wavFile.absolutePath}")

        val outputDir = wavFile.parentFile!!
        val logger = SessionLogger(outputDir)
        val detector = TickDetector()
        detector.logger = logger

        // Read WAV samples (skip 44-byte header, little-endian 16-bit mono)
        val samples = RandomAccessFile(wavFile, "r").use { raf ->
            raf.seek(44)
            val dataBytes = (raf.length() - 44).toInt()
            val bytes = ByteArray(dataBytes)
            raf.readFully(bytes)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            ShortArray(dataBytes / 2) { buf.short }
        }
        println("Loaded ${samples.size} samples (%.1fs)".format(samples.size / 44100.0))

        // Feed in chunks matching AudioCapture's read size (~20ms)
        val chunkSize = 882
        var offset = 0
        var lastState: TickDetector.State? = null
        while (offset < samples.size) {
            val count = minOf(chunkSize, samples.size - offset)
            val state = detector.process(samples, offset, count)
            if (state != null) lastState = state
            offset += count
        }

        logger.close()

        // Write beat positions for the HTML visualization tool
        writeBeatsJson(detector, outputDir)

        println("\n=== Result ===")
        if (lastState != null) {
            println("Period:      %.1f µs".format(lastState.periodMicros))
            println("Uncertainty: ±%.1f µs".format(lastState.uncertaintyMicros))
            println("Beats: ${lastState.beatCount}  Ticks: ${lastState.tickCount}  Synced: ${lastState.synced}")
            if (lastState.imbalanceMicros != 0.0 || lastState.imbalanceUncertaintyMicros != 0.0) {
                val sign = if (lastState.imbalanceMicros >= 0) "+" else "−"
                println("Imbalance:   %s%.0f ± %.0f µs".format(
                    sign, kotlin.math.abs(lastState.imbalanceMicros), lastState.imbalanceUncertaintyMicros))
            }
        }
        println("\nLog: ${outputDir.absolutePath}/session.log")

        // Basic sanity: period should be in a plausible grandfather-clock range (0.5–3s)
        val period = lastState?.periodMicros ?: 0.0
        assert(period in 500_000.0..3_000_000.0) {
            "Period %.0f µs is outside plausible range".format(period)
        }
    }

    /**
     * Writes a small JSON file with beat positions and metadata.
     * The HTML visualization tool loads this alongside the original WAV file.
     */
    private fun writeBeatsJson(detector: TickDetector, outputDir: File) {
        val sampleRate = 44100
        val beats = detector.detectedBeats

        val outFile = File(outputDir, "beats.json")
        FileWriter(outFile).use { w ->
            w.write("{\"sampleRate\":$sampleRate,\"beats\":[")
            for ((i, beat) in beats.withIndex()) {
                val samplePos = beat.sample.roundToInt()
                val sincePrev = if (i > 0) (beat.sample - beats[i - 1].sample) / sampleRate else 0.0
                if (i > 0) w.write(",")
                w.write("{\"sample\":$samplePos,\"time\":%.6f,\"isTick\":%s,\"seqIndex\":%d"
                    .format(beat.sample / sampleRate, beat.parity == 0, 2 * beat.k + beat.parity))
                w.write(",\"sincePrev\":%.6f,\"snr\":%.1f,\"index\":$i".format(sincePrev, beat.snr))
                w.write("}")
            }
            w.write("]}")
        }
        println("Beats JSON: ${outFile.absolutePath}")
    }

    /**
     * Streams a long recording (hours; too large to load whole) through the
     * detector and checks that tracking keeps hearing the beats all the way
     * through, including when the clock's rate drifts. Runs only when LONG_WAV
     * names the recording:
     *
     *     LONG_WAV=/path/to/clock_recording.wav ./gradlew testDebugUnitTest \
     *       --tests "*TickDetectorSimulationTest.longRecordingKeepsTrack"
     */
    @Test
    fun longRecordingKeepsTrack() {
        val path = System.getenv("LONG_WAV")?.takeIf { it.isNotEmpty() }
        assumeTrue("LONG_WAV not set", path != null)
        val wav = File(path!!)
        val sampleRate = 44100
        val detector = TickDetector()
        val heardPerHour = HashMap<Int, Int>()
        detector.onBeat = { b -> heardPerHour.merge((b.sample / sampleRate / 3600).toInt(), 1, Int::plus) }
        var last: TickDetector.State? = null
        var total = 0L
        var nextReport = 3600L * sampleRate
        BufferedInputStream(FileInputStream(wav), 1 shl 20).use { input ->
            input.skip(44)
            val bytes = ByteArray(882 * 2)
            val samples = ShortArray(882)
            while (true) {
                val n = input.readNBytes(bytes, 0, bytes.size)
                if (n < 2) break
                val count = n / 2
                ByteBuffer.wrap(bytes, 0, count * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples, 0, count)
                detector.process(samples, 0, count)?.let { last = it }
                total += count
                if (total >= nextReport) {
                    nextReport += 3600L * sampleRate
                    val st = last
                    val trend = st?.rateTrend?.let { "rate trend %.1f -> %.1f µs over %.1f h".format(
                        it.startPeriodMicros, it.endPeriodMicros, it.spanSeconds / 3600) } ?: "no rate trend"
                    println("  after %.0f h: period %.1f ± %.1f µs, %s".format(
                        total.toDouble() / sampleRate / 3600, st?.periodMicros ?: 0.0, st?.uncertaintyMicros ?: 0.0, trend))
                }
            }
        }
        val s = last ?: error("no result")
        val periodSeconds = s.periodMicros / 1e6
        println("Duration %.0f s, period %.1f ± %.1f µs, beats heard %d".format(
            total.toDouble() / sampleRate, s.periodMicros, s.uncertaintyMicros, s.beatCount))
        val hours = (total / sampleRate / 3600).toInt()
        var worst = 1.0
        for (h in 0..hours) {
            val span = minOf(3600.0, total.toDouble() / sampleRate - h * 3600)
            if (span < 600) continue
            val expected = 2 * span / periodSeconds
            val share = (heardPerHour[h] ?: 0) / expected
            worst = minOf(worst, share)
            println("  hour %2d: heard %5d of ~%5.0f (%.1f%%)".format(h, heardPerHour[h] ?: 0, expected, share * 100))
        }
        assertTrue("Tracking lost the beats: worst hour heard %.1f%%".format(worst * 100), worst > 0.95)
    }
}
