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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

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
        val method: TickDetector.Method = TickDetector.Method.NONE,
        /** WAV-refined period (template matching on full recording). */
        val wavPeriodMicros: Double = 0.0,
        val wavUncertaintyMicros: Double = 0.0,
        /** Tick-to-tock minus ideal half-period, in µs. */
        val imbalanceMicros: Double = 0.0,
        val imbalanceUncertaintyMicros: Double = 0.0,
        val analyzing: Boolean = false,
        val wavPath: String? = null,
        val logPath: String? = null,
        /** Crystal clock bias in ppm (positive = crystal is fast). */
        val crystalBiasPpm: Double = 0.0,
        /** Why the last recording ended early, or null if it didn't. */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var audioCapture: AudioCapture? = null
    private val tickDetector = TickDetector()
    private var sessionLogger: SessionLogger? = null
    private var wavAnalysisJob: Job? = null
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
        // The WAV analysis reads the recording file and the TickDetector state,
        // both of which a new session overwrites, so it must finish first.
        if (_state.value.running || _state.value.analyzing) return

        wavAnalysisJob?.cancel()
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
                    synced = s.synced,
                    method = s.method,
                    crystalBiasPpm = biasPpm,
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
        // only the first may close the session logger the WAV analysis uses.
        if (!_state.value.running) return
        phoneSilencer.restore()
        RecordingService.stop(getApplication())
        audioCapture?.stop()
        val wavFile = audioCapture?.wavFile
        val path = wavFile?.absolutePath
        val dir = sessionDir
        audioCapture = null
        _state.value = _state.value.copy(
            running = false,
            lastBeatIsTick = null,
            wavPath = path,
        )

        sessionLogger?.flush()

        if (wavFile != null && wavFile.exists()) {
            _state.value = _state.value.copy(analyzing = true)
            wavAnalysisJob = viewModelScope.launch(Dispatchers.IO) {
                // A failure here costs the refined result but must not crash the app.
                val result = try {
                    tickDetector.analyzeWavFile(wavFile)
                } catch (e: Exception) {
                    sessionLogger?.log("WAV_ANALYSIS", "Failed: $e")
                    null
                }
                val corr = rateCorrection
                if (result != null) {
                    val correctedPeriod = result.periodMicros * corr
                    val correctedUncertainty = result.uncertaintyMicros * corr
                    _state.value = _state.value.copy(
                        wavPeriodMicros = correctedPeriod,
                        wavUncertaintyMicros = correctedUncertainty,
                        imbalanceMicros = result.imbalanceMicros * corr,
                        imbalanceUncertaintyMicros = result.imbalanceUncertaintyMicros * corr,
                    )
                    if (_state.value.elapsedSeconds >= 60.0) {
                        sessionStore.save(SessionRecord(
                            id = sessionStartTimeMillis,
                            startTimeMillis = sessionStartTimeMillis,
                            durationSeconds = _state.value.elapsedSeconds,
                            periodMicros = correctedPeriod,
                            uncertaintyMicros = correctedUncertainty,
                            bpmClass = SessionStore.classifyBpm(correctedPeriod),
                        ))
                    }
                }
                sessionLogger?.close()
                sessionLogger = null
                tickDetector.logger = null
                if (dir != null) archiveSession(dir)
                // Start stays blocked until this point, so a new session can't have
                // its logger closed or its state overwritten by this job.
                _state.value = _state.value.copy(analyzing = false)
            }
        } else {
            sessionLogger?.close()
            sessionLogger = null
            tickDetector.logger = null
            if (dir != null) viewModelScope.launch(Dispatchers.IO) { archiveSession(dir) }
        }
    }

    /**
     * Saves the session's results as `info.json` next to its recording and adds the
     * measured beat rate to the folder name, so sessions of different clocks are
     * easy to tell apart. A failure here costs only the label and the summary.
     */
    private fun archiveSession(dir: File) {
        val s = _state.value
        val period = if (s.wavPeriodMicros > 0) s.wavPeriodMicros else s.periodMicros
        val bpm = if (period > 0) SessionStore.classifyBpm(period) else null
        val finalDir = try {
            val info = JSONObject().apply {
                put("startTime", OffsetDateTime.ofInstant(
                    Instant.ofEpochMilli(sessionStartTimeMillis), ZoneId.systemDefault()).toString())
                put("durationSeconds", s.elapsedSeconds)
                if (bpm != null) {
                    put("bpm", bpm)
                    put("clock", ClockProfile.nameForBpm(bpm))
                }
                s.recognisedClock?.let { put("recognisedClock", it.name) }
                put("beatCount", s.beatCount)
                put("realtimePeriodMicros", s.periodMicros)
                put("realtimeUncertaintyMicros", s.uncertaintyMicros)
                put("wavPeriodMicros", s.wavPeriodMicros)
                put("wavUncertaintyMicros", s.wavUncertaintyMicros)
                put("imbalanceMicros", s.imbalanceMicros)
                put("imbalanceUncertaintyMicros", s.imbalanceUncertaintyMicros)
                // The WAV header always says 44100 Hz; this is the rate the app measured.
                put("measuredSampleRate", AudioCapture.SAMPLE_RATE / rateCorrection)
                put("crystalBiasPpm", s.crystalBiasPpm)
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                s.error?.let { put("error", it) }
            }
            val label = bpm?.let { b -> ClockProfile.KNOWN.firstOrNull { it.bpm == b }?.name ?: "${b}bpm" }
            RecordingArchive.finish(dir, label, info)
        } catch (e: Exception) {
            dir
        }
        if (finalDir != dir) {
            _state.value = _state.value.copy(
                wavPath = s.wavPath?.let { File(finalDir, File(it).name).absolutePath },
                logPath = s.logPath?.let { File(finalDir, File(it).name).absolutePath },
            )
        }
    }

    override fun onCleared() {
        wavAnalysisJob?.cancel()
        stop()
        super.onCleared()
    }
}
