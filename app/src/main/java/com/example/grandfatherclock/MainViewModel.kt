package com.example.grandfatherclock

import androidx.lifecycle.ViewModel
import com.example.grandfatherclock.audio.AudioCapture
import com.example.grandfatherclock.audio.TickDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class MainViewModel : ViewModel() {

    data class UiState(
        val running: Boolean = false,
        val periodMicros: Double = 0.0,
        val uncertaintyMicros: Double = 0.0,
        val tickCount: Int = 0,
        val beatCount: Int = 0,
        /** null = idle, true = tick, false = tock */
        val lastBeatIsTick: Boolean? = null,
        /** Monotonically increasing beat counter to drive flash animation */
        val flashTrigger: Int = 0,
        val elapsedSeconds: Double = 0.0,
        val wavPath: String? = null,
        val synced: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var audioCapture: AudioCapture? = null
    private val tickDetector = TickDetector()

    var wavOutputDir: File? = null

    fun start() {
        if (_state.value.running) return

        tickDetector.reset()
        _state.value = UiState(running = true)

        audioCapture = AudioCapture(
            onBuffer = { buffer, count ->
                val result = tickDetector.process(buffer, count)
                if (result != null) {
                    _state.value = _state.value.copy(
                        periodMicros = result.periodMicros,
                        uncertaintyMicros = result.uncertaintyMicros,
                        tickCount = result.tickCount,
                        beatCount = result.beatCount,
                        lastBeatIsTick = result.lastBeatIsTick,
                        flashTrigger = _state.value.flashTrigger + 1,
                        elapsedSeconds = result.elapsedSamples.toDouble() / AudioCapture.SAMPLE_RATE,
                        synced = result.synced,
                    )
                }
            },
            wavOutputDir = wavOutputDir,
        )
        audioCapture?.start()
    }

    fun stop() {
        audioCapture?.stop()
        val path = audioCapture?.wavFile?.absolutePath
        audioCapture = null
        _state.value = _state.value.copy(
            running = false,
            lastBeatIsTick = null,
            wavPath = path,
        )
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
