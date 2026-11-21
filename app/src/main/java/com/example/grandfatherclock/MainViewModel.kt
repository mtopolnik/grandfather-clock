package com.example.grandfatherclock

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.grandfatherclock.audio.AudioCapture
import com.example.grandfatherclock.audio.ClockProfile
import com.example.grandfatherclock.audio.SessionLogger
import com.example.grandfatherclock.audio.TickDetector
import com.example.grandfatherclock.data.RecordingArchive
import com.example.grandfatherclock.data.SessionRecord
import com.example.grandfatherclock.data.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.abs

private const val KEY_RECORDING_MINUTES = "recording_minutes"

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("clock_prefs", Context.MODE_PRIVATE)
    val sessionStore = SessionStore(application)
    val phoneSilencer = PhoneSilencer(application, prefs)

    /** The live estimate at one moment, for the progress chart. */
    data class HistoryPoint(val elapsedSeconds: Float, val periodMicros: Double, val uncertaintyMicros: Double)

    data class UiState(
        val running: Boolean = false,
        /** Live period from the straight-line fit over all beats so far. */
        val periodMicros: Double = 0.0,
        val uncertaintyMicros: Double = 0.0,
        val tickCount: Int = 0,
        /** Beats heard. */
        val beatCount: Int = 0,
        /** Beat slots examined since the rhythm was found. */
        val beatsExpected: Int = 0,
        /** Fraction of the most recent beat slots in which a beat was heard. */
        val recentHitRate: Double = 0.0,
        /** True while the detector is still looking for the clock's rhythm. */
        val listening: Boolean = true,
        /** Each beat's offset from the fitted line, for the timing trace. */
        val trace: List<TickDetector.TracePoint> = emptyList(),
        /** The live estimate once per second, for the progress chart. */
        val history: List<HistoryPoint> = emptyList(),
        /** The known clock the beats were matched to, or null. */
        val recognisedClock: ClockProfile? = null,
        /** null = idle, true = tick, false = tock */
        val lastBeatIsTick: Boolean? = null,
        val flashTrigger: Int = 0,
        val elapsedSeconds: Double = 0.0,
        val synced: Boolean = false,
        /** Tick-to-tock minus ideal half-period, in µs. */
        val imbalanceMicros: Double = 0.0,
        val imbalanceUncertaintyMicros: Double = 0.0,
        val wavPath: String? = null,
        val logPath: String? = null,
        /** Crystal clock bias in ppm (positive = crystal is fast). */
        val crystalBiasPpm: Double = 0.0,
        /**
         * The bias is larger than any phone clock should have, so the audio
         * timing, and with it the result, is unreliable.
         */
        val audioClockSuspect: Boolean = false,
        /** Why the last recording ended early, or null if it didn't. */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var audioCapture: AudioCapture? = null
    private val tickDetector = TickDetector()
    private var sessionLogger: SessionLogger? = null
    private var sessionStartTimeMillis: Long = 0L
    /** Folder holding the current or most recent session's files. */
    private var sessionDir: File? = null
    /** Ratio of nominal to actual sample rate — multiply reported periods by this. */
    private var rateCorrection: Double = 1.0

    private val _recordingMinutes = MutableStateFlow(prefs.getInt(KEY_RECORDING_MINUTES, 10))
    val recordingMinutes: StateFlow<Int> = _recordingMinutes.asStateFlow()

    fun setRecordingMinutes(minutes: Int) {
        _recordingMinutes.value = minutes.coerceIn(1, 30)
        prefs.edit().putInt(KEY_RECORDING_MINUTES, _recordingMinutes.value).apply()
    }

    init {
        phoneSilencer.restore()
    }

    var wavOutputDir: File? = null

    /** For development: a WAV to play in place of the microphone (see [AudioCapture]). */
    var replayWav: File? = null
    var replaySpeed: Double = 1.0

    fun start() {
        if (_state.value.running) return

        tickDetector.reset()

        sessionStartTimeMillis = System.currentTimeMillis()
        // Each session gets its own folder, so earlier recordings are kept.
        val dir = wavOutputDir?.let { RecordingArchive.newSessionDir(it, sessionStartTimeMillis) }
        sessionDir = dir
        sessionLogger?.close()
        // The session log is diagnostic, so record without it if it can't be created.
        sessionLogger = if (dir != null) runCatching { SessionLogger(dir) }.getOrNull() else null
        tickDetector.logger = sessionLogger

        _state.value = UiState(running = true, logPath = sessionLogger?.filePath)

        audioCapture = AudioCapture(
            onBuffer = { buffer, count ->
                val s = tickDetector.process(buffer, count) ?: return@AudioCapture
                val capture = audioCapture ?: return@AudioCapture
                val corr = AudioCapture.SAMPLE_RATE.toDouble() / capture.correctedSampleRate
                rateCorrection = corr
                val biasPpm = (capture.correctedSampleRate / AudioCapture.SAMPLE_RATE - 1.0) * 1_000_000.0
                val elapsedSeconds = s.elapsedSamples.toDouble() / capture.correctedSampleRate
                val audioClockSuspect = elapsedSeconds >= AudioCapture.RATE_OFFSET_SETTLE_SECONDS &&
                    abs(biasPpm) >= AudioCapture.SUSPECT_RATE_OFFSET_PPM
                val prev = _state.value
                val history = if (s.periodMicros > 0 &&
                    elapsedSeconds - (prev.history.lastOrNull()?.elapsedSeconds ?: 0f) >= 1.0
                ) {
                    prev.history + HistoryPoint(
                        elapsedSeconds.toFloat(), s.periodMicros * corr, s.uncertaintyMicros * corr)
                } else if (s.periodMicros > 0) prev.history else emptyList()
                _state.value = prev.copy(
                    periodMicros = s.periodMicros * corr,
                    uncertaintyMicros = s.uncertaintyMicros * corr,
                    tickCount = s.tickCount,
                    beatCount = s.beatCount,
                    beatsExpected = s.beatsExpected,
                    recentHitRate = s.recentHitRate,
                    listening = s.listening,
                    trace = s.trace,
                    history = history,
                    recognisedClock = s.recognisedClock,
                    imbalanceMicros = s.imbalanceMicros * corr,
                    imbalanceUncertaintyMicros = s.imbalanceUncertaintyMicros * corr,
                    lastBeatIsTick = if (s.newBeat) s.lastBeatIsTick else _state.value.lastBeatIsTick,
                    flashTrigger = if (s.newBeat) _state.value.flashTrigger + 1 else _state.value.flashTrigger,
                    elapsedSeconds = elapsedSeconds,
                    synced = s.synced && !audioClockSuspect,
                    crystalBiasPpm = biasPpm,
                    audioClockSuspect = audioClockSuspect,
                )
                if (elapsedSeconds >= _recordingMinutes.value * 60) {
                    viewModelScope.launch { stop() }
                }
            },
            onError = { e ->
                sessionLogger?.log("CAPTURE_ERROR", e.toString())
                viewModelScope.launch {
                    if (!_state.value.running) return@launch
                    _state.value = _state.value.copy(error = e.message ?: e.toString())
                    stop()
                }
            },
            wavOutputDir = dir,
            replayWav = replayWav,
            replaySpeed = replaySpeed,
        )
        audioCapture?.start()
        RecordingService.start(getApplication())
        phoneSilencer.silence()
    }

    fun stop() {
        // Auto-stop can queue several stop() calls before the first one runs;
        // only the first may end the session.
        if (!_state.value.running) return
        phoneSilencer.restore()
        RecordingService.stop(getApplication())
        // Joins the capture thread, so the detector has seen its last buffer.
        audioCapture?.stop()
        val dir = sessionDir
        _state.value = _state.value.copy(
            running = false,
            lastBeatIsTick = null,
            wavPath = audioCapture?.wavFile?.absolutePath,
        )
        audioCapture = null
        sessionLogger?.close()
        sessionLogger = null
        tickDetector.logger = null

        // The live estimate at stop is the session's result: it fits every beat heard.
        val result = _state.value
        val startTimeMillis = sessionStartTimeMillis
        val measuredSampleRate = AudioCapture.SAMPLE_RATE / rateCorrection
        viewModelScope.launch(Dispatchers.IO) {
            if (result.periodMicros > 0 && result.elapsedSeconds >= 60.0) {
                sessionStore.save(SessionRecord(
                    id = startTimeMillis,
                    startTimeMillis = startTimeMillis,
                    durationSeconds = result.elapsedSeconds,
                    periodMicros = result.periodMicros,
                    uncertaintyMicros = result.uncertaintyMicros,
                    bpmClass = SessionStore.classifyBpm(result.periodMicros),
                ))
            }
            if (dir != null) archiveSession(dir, result, startTimeMillis, measuredSampleRate)
        }
    }

    /**
     * Saves the session's results as `info.json` next to its recording and adds the
     * measured beat rate to the folder name, so sessions of different clocks are
     * easy to tell apart. A failure here costs only the label and the summary.
     */
    private suspend fun archiveSession(
        dir: File, s: UiState, startTimeMillis: Long, measuredSampleRate: Double,
    ) {
        val bpm = if (s.periodMicros > 0) SessionStore.classifyBpm(s.periodMicros) else null
        val finalDir = try {
            val info = JSONObject().apply {
                put("startTime", OffsetDateTime.ofInstant(
                    Instant.ofEpochMilli(startTimeMillis), ZoneId.systemDefault()).toString())
                put("durationSeconds", s.elapsedSeconds)
                if (bpm != null) {
                    put("bpm", bpm)
                    put("clock", ClockProfile.nameForBpm(bpm))
                }
                s.recognisedClock?.let { put("recognisedClock", it.name) }
                put("beatCount", s.beatCount)
                put("periodMicros", s.periodMicros)
                put("uncertaintyMicros", s.uncertaintyMicros)
                put("imbalanceMicros", s.imbalanceMicros)
                put("imbalanceUncertaintyMicros", s.imbalanceUncertaintyMicros)
                // The WAV header always says 44100 Hz; this is the rate the app measured.
                put("measuredSampleRate", measuredSampleRate)
                put("crystalBiasPpm", s.crystalBiasPpm)
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                s.error?.let { put("error", it) }
            }
            val label = bpm?.let { b -> ClockProfile.KNOWN.firstOrNull { it.bpm == b }?.name ?: "${b}bpm" }
            RecordingArchive.finish(dir, label, info)
        } catch (e: Exception) {
            dir
        }
        if (finalDir == dir) return
        withContext(Dispatchers.Main) {
            // A session started meanwhile owns the paths shown.
            if (sessionDir != dir) return@withContext
            _state.value = _state.value.copy(
                wavPath = s.wavPath?.let { File(finalDir, File(it).name).absolutePath },
                logPath = s.logPath?.let { File(finalDir, File(it).name).absolutePath },
            )
        }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
