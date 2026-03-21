#!/usr/bin/env bash
# Analyze a WAV file with both Android TickDetector and Java core, merge results.
set -euo pipefail

WAV_FILE="${1:?Usage: analyze.sh <wav-file>}"
WAV_FILE="$(cd "$(dirname "$WAV_FILE")" && pwd)/$(basename "$WAV_FILE")"
WAV_DIR="$(dirname "$WAV_FILE")"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
CLOCK_DIR="${CLOCK_DIR:-$HOME/Desktop/grandfather-clock/clock}"

echo "=== Android TickDetector ==="
WAV_FILE="$WAV_FILE" "$PROJECT_DIR/gradlew" -p "$PROJECT_DIR" testDebugUnitTest \
  --tests "com.example.grandfatherclock.audio.TickDetectorSimulationTest.simulateFromWav" \
  --rerun 2>&1 | grep -E "STANDARD_OUT|^    " | head -20

echo ""
echo "=== Java core analyzer ==="
mkdir -p "$CLOCK_DIR/out/classes"
javac -d "$CLOCK_DIR/out/classes" $(find "$CLOCK_DIR/src/main/java" -name '*.java' | sort)
java -cp "$CLOCK_DIR/out/classes" dev.jara.clocktuner.core.WavAnalysisMain "$WAV_FILE"

echo ""
echo "=== Merging beats ==="
python3 "$SCRIPT_DIR/merge-beats.py" \
  "$WAV_DIR/beats.json" \
  "$WAV_DIR/core_beats.json" \
  "$WAV_DIR/beats.json"

echo ""
echo "Result: $WAV_DIR/beats.json"
