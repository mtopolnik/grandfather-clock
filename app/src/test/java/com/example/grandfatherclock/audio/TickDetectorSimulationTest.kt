package com.example.grandfatherclock.audio

import org.junit.Test
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

        // WAV refinement pass
        val refined = detector.analyzeWavFile(wavFile)

        logger.close()

        // Write beat positions for the HTML visualization tool
        writeBeatsJson(detector, outputDir)

        println("\n=== Real-time result ===")
        if (lastState != null) {
            println("Period:      %.1f µs".format(lastState.periodMicros))
            println("Uncertainty: ±%.1f µs".format(lastState.uncertaintyMicros))
            println("Beats: ${lastState.beatCount}  Ticks: ${lastState.tickCount}")
            println("Method: ${lastState.method}  Synced: ${lastState.synced}")
        }
        if (refined != null) {
            println("\n=== WAV-refined result (template match) ===")
            println("Period:      %.1f µs".format(refined.periodMicros))
            println("Uncertainty: ±%.1f µs".format(refined.uncertaintyMicros))
            if (refined.imbalanceMicros != 0.0 || refined.imbalanceUncertaintyMicros != 0.0) {
                val sign = if (refined.imbalanceMicros >= 0) "+" else "−"
                println("Imbalance:   %s%.0f ± %.0f µs".format(
                    sign, kotlin.math.abs(refined.imbalanceMicros), refined.imbalanceUncertaintyMicros))
            }
        }
        println("\nLog: ${outputDir.absolutePath}/session.log")

        // Basic sanity: period should be in a plausible grandfather-clock range (0.5–3s)
        val period = (refined ?: lastState)?.periodMicros ?: 0.0
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
        val frameSamples = sampleRate / 200  // 220 samples per 5ms frame
        val beatFrames = detector.detectedBeatFrames
        val seqIndices = detector.detectedBeatSequenceIndices
        val refined = detector.refinedBeatSamples

        val outFile = File(outputDir, "beats.json")
        FileWriter(outFile).use { w ->
            w.write("{\"sampleRate\":$sampleRate,\"frameSamples\":$frameSamples,\"beats\":[")
            for ((i, frame) in beatFrames.withIndex()) {
                val samplePos = frame * frameSamples
                val timeSec = samplePos.toDouble() / sampleRate
                val seqIdx = if (i < seqIndices.size) seqIndices[i] else i
                val isTick = (seqIdx % 2 == 0)

                val timeSincePrev = if (i > 0) {
                    val prevSample = beatFrames[i - 1] * frameSamples
                    (samplePos - prevSample).toDouble() / sampleRate
                } else 0.0

                val avgPeriod = if (i > 0) {
                    val firstSample = beatFrames[0] * frameSamples
                    (samplePos - firstSample).toDouble() / sampleRate / i
                } else 0.0

                if (i > 0) w.write(",")
                w.write("{\"sample\":$samplePos,\"time\":%.6f,\"isTick\":$isTick,\"seqIndex\":$seqIdx".format(timeSec))
                w.write(",\"sincePrev\":%.6f,\"avgPeriod\":%.6f,\"index\":$i".format(timeSincePrev, avgPeriod))

                val refPos = refined[i]
                if (refPos != null) {
                    val refTime = refPos / sampleRate
                    val refSincePrev = if (i > 0) {
                        val prevRef = refined[i - 1]
                        val prevSamp = prevRef ?: (beatFrames[i - 1] * frameSamples).toDouble()
                        (refPos - prevSamp) / sampleRate
                    } else 0.0
                    // Average period using only refined beats from the first refined beat
                    val firstRefIdx = refined.keys.min()
                    val firstRefPos = refined[firstRefIdx]!!
                    val refAvgPeriod = if (i > firstRefIdx) {
                        (refPos - firstRefPos) / sampleRate / (i - firstRefIdx)
                    } else 0.0
                    w.write(",\"refined\":%.2f,\"refTime\":%.6f,\"refSincePrev\":%.6f,\"refAvgPeriod\":%.6f"
                        .format(refPos, refTime, refSincePrev, refAvgPeriod))
                }
                w.write("}")
            }
            w.write("]}")
        }
        println("Beats JSON: ${outFile.absolutePath} (${refined.size} refined)")
    }

}
