#!/bin/bash
# Render README artwork (docs/hero.jpg, docs/screens.jpg, docs/speed.jpg) from docs/src/readme-art.html at 2x.
set -euo pipefail
cd "$(dirname "$0")/.."
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
SRC="file://$PWD/docs/src/readme-art.html"
TMP="$(mktemp -d)"
render() { # name width height
  "$CHROME" --headless=new --hide-scrollbars --force-device-scale-factor=2 --window-size="$2,$3" \
    --virtual-time-budget=6000 --screenshot="$TMP/$1.png" "$SRC?a=$1" >/dev/null 2>&1
  sips -s format jpeg -s formatOptions 88 "$TMP/$1.png" --out "docs/$1.jpg" >/dev/null
  echo "docs/$1.jpg"
}
render hero 1600 900
render screens 1600 560
render speed 1600 560
rm -rf "$TMP"
