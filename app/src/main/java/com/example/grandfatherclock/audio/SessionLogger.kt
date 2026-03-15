package com.example.grandfatherclock.audio

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
/**
 * Writes a log of all significant events during a recording session.
 * Thread-safe: all writes are synchronized.
 */
class SessionLogger(outputDir: File) {

    private val startNanos = System.nanoTime()
    private val logFile = File(outputDir, "session.log")
    private val writer: BufferedWriter

    val filePath: String get() = logFile.absolutePath

    init {
        writer = BufferedWriter(FileWriter(logFile), 8192)
        log("SESSION", "Started")
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0
        writer.write("[%8.3fs] %-18s %s\n".format(elapsed, tag, message))
    }

    @Synchronized
    fun flush() {
        try { writer.flush() } catch (_: Exception) {}
    }

    @Synchronized
    fun close() {
        try {
            log("SESSION", "Stopped")
            writer.flush()
            writer.close()
        } catch (_: Exception) {}
    }
}
