package com.example.grandfatherclock.data

import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Keeps every recording session in its own folder under `recordings/`, so a new
 * session never replaces an earlier one.
 *
 * A session folder is named after its local start time, e.g. `2026-11-21_14-30-05`.
 * When the session ends, the folder name gains a label: the
 * clock's name for a known clock (`2026-11-21_14-30-05_Nattich`), otherwise its
 * beat rate (`2026-11-21_14-30-05_81bpm`).
 */
object RecordingArchive {

    private val FOLDER_TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault())

    fun newSessionDir(rootDir: File, startTimeMillis: Long): File {
        val name = FOLDER_TIME_FORMAT.format(Instant.ofEpochMilli(startTimeMillis))
        return File(File(rootDir, "recordings"), name).also { it.mkdirs() }
    }

    /**
     * Writes [info] to `info.json` in [sessionDir] and, when [label] is known,
     * appends it to the folder name. Returns where the session folder ends up.
     */
    fun finish(sessionDir: File, label: String?, info: JSONObject): File {
        File(sessionDir, "info.json").writeText(info.toString(2))
        if (label == null) return sessionDir
        val labeled = File(sessionDir.parentFile, "${sessionDir.name}_$label")
        return if (sessionDir.renameTo(labeled)) labeled else sessionDir
    }
}
