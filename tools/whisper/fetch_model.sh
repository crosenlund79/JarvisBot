#!/usr/bin/env bash
# Downloads the offline speech-recognition model (OpenAI Whisper "small", multilingual, int8 ONNX export by the
# sherpa-onnx project) at a pinned revision, verifies it, and prepares the decoder for the phone
# (shrink_decoder.py). Result: app/src/main/assets/models/whisper/. Run once before building; CI does the same.
# The app itself never downloads anything: the model is bundled in the APK.
set -euo pipefail

REPO="csukuangfj/sherpa-onnx-whisper-small"
REV="8f3c18b358db4d1f2fc1eae49d75cd20989e4309"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/app/src/main/assets/models/whisper"
CACHE="${WHISPER_CACHE:-$ROOT/.whisper-cache}"
PYTHON="${PYTHON:-python3}"
mkdir -p "$OUT" "$CACHE"

fetch() { # name sha256
  local f="$CACHE/$1"
  if [ ! -f "$f" ] || ! echo "$2  $f" | sha256sum -c --status; then
    echo "Downloading $1"
    curl -fL --retry 5 --retry-delay 5 -o "$f.tmp" "https://huggingface.co/$REPO/resolve/$REV/$1"
    mv "$f.tmp" "$f"
  fi
  echo "$2  $f" | sha256sum -c --quiet
}

fetch small-encoder.int8.onnx 4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9
fetch small-decoder.int8.onnx acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee
fetch small-tokens.txt b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126

cp "$CACHE/small-encoder.int8.onnx" "$OUT/small-encoder.int8.onnx"
cp "$CACHE/small-tokens.txt" "$OUT/small-tokens.txt"
if [ ! -f "$OUT/small-decoder.jarvis.onnx" ] || [ "$CACHE/small-decoder.int8.onnx" -nt "$OUT/small-decoder.jarvis.onnx" ]; then
  "$PYTHON" "$ROOT/tools/whisper/shrink_decoder.py" "$CACHE/small-decoder.int8.onnx" "$OUT/small-decoder.jarvis.onnx"
fi
ls -l "$OUT"
