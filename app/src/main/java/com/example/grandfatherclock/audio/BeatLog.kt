package com.example.grandfatherclock.audio

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Writes every beat heard to `beats.csv` as it is heard: beat number, beat type
 * (0 = tick, 1 = tock), position in the recording in samples, and loudness over
 * the noise floor. Positions count samples at the nominal rate; dividing by the
 * session's measured sample rate gives seconds since the start.
 *
 * The file allows analysing long sessions without processing their audio again.
 * It is diagnostic, so a write failure never ends the recording.
 */
class BeatLog(outputDir: File) {

    private val writer = BufferedWriter(FileWriter(File(outputDir, "beats.csv")), 8192)

    init {
        writeLine("k,parity,sample,snr")
    }

    fun write(beat: TickDetector.Beat) {
        writeLine("%d,%d,%.2f,%.1f".format(java.util.Locale.US, beat.k, beat.parity, beat.sample, beat.snr))
    }

    fun close() {
        try { writer.close() } catch (_: Exception) {}
    }

    private fun writeLine(line: String) {
        try {
            writer.write(line)
            writer.newLine()
        } catch (_: Exception) {}
    }
}
