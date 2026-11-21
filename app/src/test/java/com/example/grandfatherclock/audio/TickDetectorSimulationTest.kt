package com.example.grandfatherclock.audio

import org.junit.Test
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

}
