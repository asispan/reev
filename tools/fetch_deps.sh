#!/bin/bash
# Fetch the sherpa-onnx Android AAR (Apache-2.0) used for on-device Parakeet. Not committed (49 MB).
set -e
cd "$(dirname "$0")/.."
VER=2.0.1
OUT=app/libs/sherpa-onnx-$VER.aar
mkdir -p app/libs
if [ ! -f "$OUT" ]; then
  curl -fL -o "$OUT" "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VER/sherpa-onnx-$VER.aar"
fi
ls -l "$OUT"
