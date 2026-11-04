#!/bin/bash
# Fetch the sherpa-onnx Android AAR (Apache-2.0) used for on-device Parakeet. Not committed (49 MB).
# The download is pinned to a release and checked against its SHA-256 before the build may use it.
set -euo pipefail
cd "$(dirname "$0")/.."
VER=2.0.1
SHA256=a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7
OUT=app/libs/sherpa-onnx-$VER.aar
mkdir -p app/libs
if [ ! -f "$OUT" ]; then
  curl -fL -o "$OUT.tmp" "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VER/sherpa-onnx-$VER.aar"
  mv "$OUT.tmp" "$OUT"
fi
GOT=$( (command -v sha256sum >/dev/null && sha256sum "$OUT" || shasum -a 256 "$OUT") | cut -d' ' -f1)
if [ "$GOT" != "$SHA256" ]; then
  echo "sherpa-onnx AAR checksum mismatch: got $GOT, want $SHA256" >&2
  rm -f "$OUT"; exit 1
fi
ls -l "$OUT"
