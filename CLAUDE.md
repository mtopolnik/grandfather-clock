# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug        # Build debug APK
./gradlew assembleRelease      # Build release APK
./gradlew clean                # Clean build artifacts
```

## Testing

`TickDetectorSimulationTest.simulateFromWav` replays a real WAV recording through `TickDetector` and prints the result. It requires a clock recording WAV file.

```bash
# Point WAV_FILE at a recording pulled from the device:
WAV_FILE=/path/to/clock_recording.wav ./gradlew testDebugUnitTest \
  --tests "com.example.grandfatherclock.audio.TickDetectorSimulationTest.simulateFromWav"
```

The test also checks `~/Desktop/clock_recording.wav` as a fallback. It writes a `session.log` next to the WAV file with detailed detection traces.

### Evaluation against references

`TickDetectorEvaluationTest` judges the detector against offline reference values. `tools/reference.py` (numpy/scipy) computes, for every WAV under `~/Desktop/grandfather-clock`, the full-length period and imbalance from precisely timed beats, and stores them in `tools/eval/references.json`. Run it again after adding recordings:

```bash
python3 -I tools/reference.py            # all WAVs under the default root
./gradlew testDebugUnitTest --rerun --tests "com.example.grandfatherclock.audio.TickDetectorEvaluationTest"
```

The evaluation replays every recording (long ones also as several sessions starting at different offsets) and reports, per listening time, the actual error, the uncertainty the app shows, z = error / shown uncertainty (honest ≈ 1 rms), wrong-period failures, and beats heard. The report goes to stdout and `app/build/reports/evaluation.txt`. `EVAL_ROOT` overrides the recordings folder; `EVAL_LOG=<path>@<startSeconds>` replays one session with the detector's log written to `app/build/reports/eval-log/session.log`. `noClockNeverLocks` checks that noise with irregular clicks never produces a rhythm.

`TickDetectorSimulationTest.longRecordingKeepsTrack` streams a recording of hours through the detector (too large for the evaluation, which loads files whole) and checks that every hour has more than 95% of its beats heard. It runs only with `LONG_WAV=<path>`; on the overnight Medusa recording (9.8 h) it takes about 2 minutes. Keep such recordings out of `references.json`, since the evaluation and `tools/reference.py` load whole files into memory.

### Replaying a recording through the app

Debug builds accept a WAV to play in place of the microphone (useful on the emulator):

```bash
adb shell am start -n com.example.grandfatherclock/.MainActivity \
  --es replay_wav /sdcard/Android/data/com.example.grandfatherclock/files/x.wav --ef replay_speed 4
```

Each session is kept in its own folder under `files/recordings/`, named after its local start time. When the session ends, the folder name also carries the clock: its name for a known clock (e.g. `2026-11-21_14-30-05_Nattich`), otherwise the measured beat rate (e.g. `2026-11-21_14-30-05_81bpm`). A session folder contains `clock_recording.wav`, `session.log`, `beats.csv` (every beat heard: beat number, type 0 = tick / 1 = tock, position in samples, loudness; divide positions by the measured sample rate for seconds), and `info.json` (start time, duration, measured period and imbalance, measured sample rate, device). Recordings can last up to 12 hours (3.8 GB; a WAV's 32-bit size field allows about 13.5 hours). To pull all sessions from a connected device:

```bash
adb pull /storage/emulated/0/Android/data/com.example.grandfatherclock/files/recordings /dest/
```

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
  - **`TickDetector`** — Stateful DSP engine:
    1. Band-pass 2–10 kHz, RMS envelope in ~1 ms hops.
    2. Acquire: autocorrelate the onset strength, then fold the onsets at half/equal/double the candidate period. The period is the shortest candidate whose fold shows a tick/tock pair 40–60% of a period apart, each heard in ≥60% of periods; two consecutive attempts must agree.
    3. Track: examine each predicted beat slot (per beat type, following recent offsets) and take the strongest onset in a ±25–60 ms window; quiet slots are missed beats. Slots are predicted from a robust line fitted to the last 400 beats (4–5 minutes, `TRACK_BEATS`), so tracking follows a rate that drifts over hours (Medusa's period rose by 250 µs overnight), while a few seconds of strike sounds taken for beats barely move it.
    4. Fit t = a[tick|tock] + P·k by robust least squares over the last 30 minutes of beats (`RESULT_WINDOW_SECONDS`; shorter sessions use all beats), so the result follows a rate that drifts over hours. The uncertainty adds a slow-wander term to the formal error, because beat timing wanders in a correlated way (escape-wheel pattern of about ±14 ms per tooth, slow drift over minutes).
  - **`ClockProfile`** — Known clocks: Nattich (81 BPM, 30-tooth escape wheel, ~3.5 ms wander) and Medusa (104 BPM, 26 teeth, ~6.5 ms wander). Their names label session folders and history tabs. A clock is matched by BPM, then recognised only once its beats confirm the tooth count. After about 1.2 wheel turns, beats of the same tooth must scatter less than half as much as around the straight line, and less than 0.8× as much as with one tooth more or fewer. Recognition holds for the session. A recognised clock gets a per-tooth fit (each tooth its own offset, cancelling the pattern) and its own wander amplitude in the uncertainty; others use the generic fit and a cautious 6 ms. `recognitionNeedsTheRightToothCount` checks that wrong tooth counts are never accepted.

## Measurement limits

The clocks' own timing irregularity, not the DSP, limits accuracy. Both clocks repeat a ±10–14 ms per-tooth pattern once per escape-wheel turn (30 periods for Nattich, 26 for Medusa) and wander slowly over minutes, by about ±3 ms (Nattich) and ±6–7 ms (Medusa) within 15-minute recordings; over hours Medusa wanders by 20–30 ms rms (see `docs/clocks.md`). Typical live errors at 15 s / 1 min / 2 min / 4 min: Nattich ≈ 1000 / 60 / 40 / 18 µs, Medusa ≈ 450 / 90 / 58 / 35 µs (1 µs ≈ 0.4 s/week). Wander slower than the listening window can't be seen from inside it, so the uncertainty includes a prior wander amplitude (`WANDER_PRIOR_SECONDS`).

## The two clocks

What is known about Nattich and Medusa (drive, pendulum, strikes, measured rates, dependence on winding, wander) is kept in `docs/clocks.md`. Add to it when new facts come in, from the owner or from recordings.

@docs/clocks.md

## UI

Single Compose screen (`ClockTunerScreen`): flashing circle (teal=tick, orange=tock) labelled with the clock's name for a known beat rate, else the beats per minute, live rate in s/week with uncertainty (labelled "Final" after stop, as the session's result; ", last 30 min" once the session is longer than that), a status line (finding the beat / beats heard / warnings when the beat can't be found or beats are being missed), beat imbalance, a prominent warning when the phone's audio clock is unreliable (see below), an amber one-line "Drifting … → … s/week in … h" notice when the rate changed significantly during a session of an hour or more, a timegrapher-style trace of each beat's offset from the line that predicted it (recorded when the beat is heard and never changed, so the dots only scroll; the vertical scale moves in steps), a rate-over-time chart with its uncertainty band, start/stop, duration selector (arrows step through 1 min … 12 h; tapping the value opens a menu of common durations) and elapsed time. A tap on either chart switches both between the last 10 minutes and the whole session (labelled when the session is longer than 10 minutes). The whole-session timing trace shows offsets from one line through all beats, so a drift over hours shows as the timing bending away; long sessions are averaged into at most 300 time bins per beat type (`SESSION_TRACE_BINS`), 2 s times a power of two wide, so bins only merge in pairs as the session grows. The rate chart keeps at most about 1200 points.

## Key Constants

- Sample rate: 44100 Hz (`AudioCapture.SAMPLE_RATE`)
- Envelope hop: 44 samples (~1 ms); band 2–10 kHz
- Audio buffer: 4 s, so a slow refit late in a long session never loses samples. Refits run after every beat up to 2000 beats, then every (beats / 2000)-th beat (`FULL_REFIT_BEATS`)
- Acquisition: from 6 s of audio, retried every second, last 20 s used
- Beat accepted at ≥ 3.5× the local noise floor (median envelope over 1.5 s)
- "Synced" (green): uncertainty ≤ 10 s/week and ≥ 80% of recent beats heard (`TickDetector.SYNCED_SEC_PER_WEEK`), the audio clock not flagged, and no rate trend
- Rate trend: sessions of ≥ 1 h (`TREND_MIN_SECONDS`) fit a line to the periods of 10-minute blocks; a slope beyond 3 standard errors (from the blocks' scatter) and a change of ≥ 10 s/week is reported as `RateTrend` and saved in `info.json`
- Audio clock: `AudioCapture` measures the true sample rate from `AudioTimestamp`, and periods are corrected by it (bias is under 2 ppm on the Pixel 9 Pro). After a minute (`RATE_OFFSET_SETTLE_SECONDS`), an offset of ≥ 50 ppm (`SUSPECT_RATE_OFFSET_PPM`), more than any phone clock should have, flags the audio timing as unreliable: the screen shows a warning and never turns green
- Uncertainty calibration: `WANDER_PRIOR_SECONDS` (6 ms), `UNCERTAINTY_SCALE`, `IMBALANCE_PATTERN_FACTOR`, `IMBALANCE_FLOOR_SECONDS` in `TickDetector`, tuned with the evaluation test
