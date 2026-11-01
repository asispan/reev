#!/usr/bin/env python3
"""Write Reev's cloud STT API keys into the debug app's prefs over adb (run-as).

Keys are read from environment variables and never printed:
  REEV_ELEVENLABS_KEY, REEV_SARVAM_KEY, REEV_SMALLEST_KEY, REEV_CARTESIA_KEY

Example (any secret manager works):
  REEV_CARTESIA_KEY="$(your-secret-cli read cartesia)" python3 tools/inject_keys.py

You can also just paste keys in the app's settings screen.
"""
import os
import re
import subprocess
import sys
from xml.sax.saxutils import escape

PKG = "com.asis.reev"
ENGINES = ["elevenlabs", "sarvam", "smallest", "cartesia"]


def sh(cmd, inp=None):
    return subprocess.run(cmd, input=inp, capture_output=True, text=True, timeout=60)


# Keep existing non-key settings.
cur = sh(["adb", "shell", f"run-as {PKG} cat shared_prefs/reev.xml"]).stdout
entries = {}
for m in re.finditer(r'<(string|boolean) name="([^"]+)"(?: value="([^"]*)")?>?([^<]*)', cur):
    kind, name, val, body = m.groups()
    entries[name] = (kind, val if kind == "boolean" else body)

ok = []
for eid in ENGINES:
    v = os.environ.get(f"REEV_{eid.upper()}_KEY", "").strip()
    if v:
        entries[f"key_{eid}"] = ("string", v)
        ok.append(eid)

if not ok:
    sys.exit("No REEV_*_KEY environment variables set; nothing to do.")

lines = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>"]
for name, (kind, val) in entries.items():
    if kind == "boolean":
        lines.append(f'    <boolean name="{escape(name)}" value="{val}" />')
    else:
        lines.append(f'    <string name="{escape(name)}">{escape(val)}</string>')
lines.append("</map>")

sh(["adb", "shell", f"am force-stop {PKG}"])
r = sh(["adb", "shell", f"run-as {PKG} sh -c 'mkdir -p shared_prefs && cat > shared_prefs/reev.xml'"],
       inp="\n".join(lines) + "\n")
print("wrote keys for:", ok, "rc", r.returncode)
print("note: force-stop resets the default keyboard; run: adb shell ime set com.asis.reev/.ReevService")
