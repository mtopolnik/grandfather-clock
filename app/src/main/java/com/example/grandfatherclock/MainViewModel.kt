package com.example.grandfatherclock

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.grandfatherclock.audio.AudioCapture
import com.example.grandfatherclock.audio.SessionLogger
import com.example.grandfatherclock.audio.TickDetector
import com.example.grandfatherclock.data.SessionRecord
import com.example.grandfatherclock.data.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

private const val KEY_RECORDING_MINUTES = "recording_minutes"

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("clock_prefs", Context.MODE_PRIVATE)
    val sessionStore = SessionStore(application)
    val phoneSilencer = PhoneSilencer(application, prefs)

    data class UiState(
        val running: Boolean = false,
        /** Real-time period (autocorrelation on beat signal). */
        val periodMicros: Double = 0.0,
        val uncertaintyMicros: Double = 0.0,
        val tickCount: Int = 0,
        val beatCount: Int = 0,
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

    fun start() {
        // The WAV analysis reads the recording file and the TickDetector state,
        // both of which a new session overwrites, so it must finish first.
        if (_state.value.running || _state.value.analyzing) return

        wavAnalysisJob?.cancel()
        tickDetector.reset()

        val dir = wavOutputDir
        sessionLogger?.close()
        // The session log is diagnostic, so record without it if it can't be created.
        sessionLogger = if (dir != null) runCatching { SessionLogger(dir) }.getOrNull() else null
        tickDetector.logger = sessionLogger

        sessionStartTimeMillis = System.currentTimeMillis()
        _state.value = UiState(running = true, logPath = sessionLogger?.filePath)

        audioCapture = AudioCapture(
            onBuffer = { buffer, count ->
                val s = tickDetector.process(buffer, count) ?: return@AudioCapture
                val capture = audioCapture ?: return@AudioCapture
                val corr = AudioCapture.SAMPLE_RATE.toDouble() / capture.correctedSampleRate
                rateCorrection = corr
                val biasPpm = (capture.correctedSampleRate / AudioCapture.SAMPLE_RATE - 1.0) * 1_000_000.0
                val elapsedSeconds = s.elapsedSamples.toDouble() / capture.correctedSampleRate
                _state.value = _state.value.copy(
                    periodMicros = s.periodMicros * corr,
                    uncertaintyMicros = s.uncertaintyMicros * corr,
                    tickCount = s.tickCount,
                    beatCount = s.beatCount,
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
            wavOutputDir = wavOutputDir,
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
                // A failure here (e.g. storage full when writing the idealized
                // WAV) costs the refined result but must not crash the app.
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
                        analyzing = false,
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
                } else {
                    _state.value = _state.value.copy(analyzing = false)
                }
                sessionLogger?.close()
                sessionLogger = null
                tickDetector.logger = null
            }
        } else {
            sessionLogger?.close()
            sessionLogger = null
            tickDetector.logger = null
        }
    }

    override fun onCleared() {
        wavAnalysisJob?.cancel()
        stop()
        super.onCleared()
    }
}
