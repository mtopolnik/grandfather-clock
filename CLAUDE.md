# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug        # Build debug APK
./gradlew assembleRelease      # Build release APK
./gradlew clean                # Clean build artifacts
```

## Testing

`TickDetectorSimulationTest.simulateFromWav` replays a real WAV recording through `TickDetector`, running both real-time beat detection and WAV-refined analysis. It requires a clock recording WAV file.

```bash
# Point WAV_FILE at a recording pulled from the device:
WAV_FILE=/path/to/clock_recording.wav ./gradlew testDebugUnitTest \
  --tests "com.example.grandfatherclock.audio.TickDetectorSimulationTest.simulateFromWav"
```

The test also checks `~/Desktop/clock_recording.wav` as a fallback. It writes a `session.log` next to the WAV file with detailed detection traces.

To pull recordings from a connected device:

```bash
adb pull /storage/emulated/0/Android/data/com.example.grandfatherclock/files/ /dest/
```

That directory contains `clock_recording.wav`, `clock_idealized.wav`, and `session.log`.

## Project Overview

**Grandfather Clock Tuner** — an Android app that listens to a grandfather clock's tick/tock sounds via the microphone and measures the pendulum period with microsecond-level precision. The app helps clock owners tune their pendulum by showing the measured period, uncertainty, and beat counts in real time.

Package: `com.example.grandfatherclock`
Min SDK 26, Target/Compile SDK 35, Java 17, Jetpack Compose UI, no navigation library.

## Architecture

Single-activity, single-screen app with three layers:

- **`MainActivity`** — Handles RECORD_AUDIO permission, creates the ViewModel, and hosts Compose content.
- **`MainViewModel`** — Owns `UiState` as a `StateFlow`. Coordinates `AudioCapture` and `TickDetector`. Start/stop lifecycle management.
- **Audio layer** (`audio/` package):
  - **`AudioCapture`** — Runs an `AudioRecord` on a dedicated high-priority thread at 44100 Hz mono 16-bit PCM. Streams `ShortArray` buffers to a callback. Optionally writes a WAV file (header patched on stop).
  - **`TickDetector`** — Stateful DSP engine. Processes PCM buffers sample-by-sample to detect tick/tock beats. Key algorithms: adaptive asymmetric noise floor (fast-down/slow-up EMA), peak detection with a 5ms trigger window, dead zone (~600ms) between beats, interval validation against running median to reject false triggers, and missed-beat detection (~2x median gap).

## UI

Single Compose screen (`ClockTunerScreen`): flashing circle indicator (teal=tick, orange=tock, gray=idle), period display in µs with uncertainty, tick/beat counters, elapsed time, WAV save path, and start/stop button. Uses `FlashingCircle` with `LaunchedEffect` + `animateColorAsState`.

## Key Constants

- Sample rate: 44100 Hz (`AudioCapture.SAMPLE_RATE`)
- Dead zone: 600ms between beats
- Trigger window: 5ms for peak detection
- Threshold: 5× noise floor, minimum 700
- Noise floor EMA: α=0.001 down, α=0.0001 up
