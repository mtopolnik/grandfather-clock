package com.example.grandfatherclock.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

data class SessionRecord(
    val id: Long,
    val startTimeMillis: Long,
    val durationSeconds: Double,
    val periodMicros: Double,
    val uncertaintyMicros: Double,
    val bpmClass: Int,
)

class SessionStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "session_history"
        private const val KEY_SESSIONS = "sessions"
        fun classifyBpm(periodMicros: Double): Int {
            val halfPeriodMicros = periodMicros / 2.0
            val bpm = 60_000_000.0 / halfPeriodMicros
            return bpm.roundToInt()
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(record: SessionRecord) {
        val all = loadAll().toMutableList()
        all.add(record)
        persist(all)
    }

    fun delete(id: Long) {
        val all = loadAll().filter { it.id != id }
        persist(all)
    }

    fun loadAll(): List<SessionRecord> {
        val json = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            SessionRecord(
                id = obj.getLong("id"),
                startTimeMillis = obj.getLong("startTimeMillis"),
                durationSeconds = obj.optDouble("durationSeconds", 0.0),
                periodMicros = obj.getDouble("periodMicros"),
                uncertaintyMicros = obj.getDouble("uncertaintyMicros"),
                bpmClass = obj.getInt("bpmClass"),
            )
        }
    }

    private fun persist(records: List<SessionRecord>) {
        val arr = JSONArray()
        for (r in records) {
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("startTimeMillis", r.startTimeMillis)
                put("durationSeconds", r.durationSeconds)
                put("periodMicros", r.periodMicros)
                put("uncertaintyMicros", r.uncertaintyMicros)
                put("bpmClass", r.bpmClass)
            })
        }
        prefs.edit().putString(KEY_SESSIONS, arr.toString()).apply()
    }
}
