#!/bin/bash
# Build a Play-ready, upload-signed AAB.
#   REEV_KEYSTORE=/path/upload.jks REEV_KEYSTORE_PASSWORD=... ./tools/release.sh
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
: "${REEV_KEYSTORE:?set REEV_KEYSTORE}" "${REEV_KEYSTORE_PASSWORD:?set REEV_KEYSTORE_PASSWORD}"
./tools/fetch_deps.sh >/dev/null
./gradlew clean bundleRelease --console=plain -q
AAB=app/build/outputs/bundle/release/app-release.aab
"$JAVA_HOME/bin/jarsigner" -verify "$AAB" >/dev/null && echo "signed: $AAB ($(du -h "$AAB" | cut -f1))"
