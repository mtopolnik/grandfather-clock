package com.example.grandfatherclock

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.grandfatherclock.audio.AudioCapture
import com.example.grandfatherclock.audio.SessionLogger
import com.example.grandfatherclock.audio.TickDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

private const val KEY_RECORDING_MINUTES = "recording_minutes"

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("clock_prefs", Context.MODE_PRIVATE)

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
        val analyzing: Boolean = false,
        val wavPath: String? = null,
        val logPath: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var audioCapture: AudioCapture? = null
    private val tickDetector = TickDetector()
    private var sessionLogger: SessionLogger? = null
    private var wavAnalysisJob: Job? = null
    private var autoStopJob: Job? = null

    private val _recordingMinutes = MutableStateFlow(prefs.getInt(KEY_RECORDING_MINUTES, 10))
    val recordingMinutes: StateFlow<Int> = _recordingMinutes.asStateFlow()

    fun setRecordingMinutes(minutes: Int) {
        _recordingMinutes.value = minutes.coerceIn(1, 30)
        prefs.edit().putInt(KEY_RECORDING_MINUTES, _recordingMinutes.value).apply()
        if (_state.value.running) restartAutoStop()
    }

    var wavOutputDir: File? = null

    fun start() {
        if (_state.value.running) return

        wavAnalysisJob?.cancel()
        tickDetector.reset()

        val dir = wavOutputDir
        sessionLogger?.close()
        sessionLogger = if (dir != null) SessionLogger(dir) else null
        tickDetector.logger = sessionLogger

        _state.value = UiState(running = true, logPath = sessionLogger?.filePath)

        audioCapture = AudioCapture(
            onBuffer = { buffer, count ->
                val s = tickDetector.process(buffer, count) ?: return@AudioCapture
                _state.value = _state.value.copy(
                    periodMicros = s.periodMicros,
                    uncertaintyMicros = s.uncertaintyMicros,
                    tickCount = s.tickCount,
                    beatCount = s.beatCount,
                    lastBeatIsTick = if (s.newBeat) s.lastBeatIsTick else _state.value.lastBeatIsTick,
                    flashTrigger = if (s.newBeat) _state.value.flashTrigger + 1 else _state.value.flashTrigger,
                    elapsedSeconds = s.elapsedSamples.toDouble() / AudioCapture.SAMPLE_RATE,
                    synced = s.synced,
                    method = s.method,
                )
            },
            wavOutputDir = wavOutputDir,
        )
        audioCapture?.start()
        RecordingService.start(getApplication())

        restartAutoStop()
    }

    private fun restartAutoStop() {
        autoStopJob?.cancel()
        val maxMillis = _recordingMinutes.value * 60 * 1000L
        val elapsedMillis = (_state.value.elapsedSeconds * 1000).toLong()
        val remaining = maxMillis - elapsedMillis
        if (remaining <= 0) {
            stop()
        } else {
            autoStopJob = viewModelScope.launch {
                delay(remaining)
                stop()
            }
        }
    }

    fun stop() {
        autoStopJob?.cancel()
        autoStopJob = null
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
                val result = tickDetector.analyzeWavFile(wavFile)
                _state.value = if (result != null) {
                    _state.value.copy(
                        wavPeriodMicros = result.periodMicros,
                        wavUncertaintyMicros = result.uncertaintyMicros,
                        analyzing = false,
                    )
                } else {
                    _state.value.copy(analyzing = false)
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
