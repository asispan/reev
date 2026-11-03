#!/usr/bin/env python3
"""On-device smoke test for Reev typing: taps and glides real touch events through `adb shell input`.

Usage (phone connected, Reev active, a text field focused, e.g. Reev Settings → "Try it"):
    python3 tools/kbd_test.py tap "hello world"
    python3 tools/kbd_test.py glide hello keyboard tonight
    python3 tools/kbd_test.py edge          # taps near key edges to exercise the tap resolver

Geometry mirrors KeyboardView: 4 rows × 54 dp + 2×6 dp padding, bottom padding = nav-bar inset + 4 dp.
"""
import re
import subprocess
import sys
import time


def sh(cmd):
    return subprocess.run(["adb", "shell", cmd], capture_output=True, text=True).stdout


def geometry():
    w, h = map(int, re.search(r"(\d+)x(\d+)", sh("wm size").splitlines()[-1]).groups())
    dens = int(re.search(r"(\d+)", sh("wm density").splitlines()[-1]).group(1)) / 160
    nav = 0
    m = re.search(r"type=navigationBars[^\n]*frame=\[\d+,(\d+)\]\[\d+,(\d+)\]", sh("dumpsys window"))
    if m:
        nav = int(m.group(2)) - int(m.group(1))
    kb_bottom = h - nav - 4 * dens
    kb_top = kb_bottom - (4 * 54 + 12) * dens
    return w, dens, kb_top


def centers():
    w, dens, top = geometry()
    u = w / 10
    row = 54 * dens
    pad = 6 * dens
    c = {}
    for i, ch in enumerate("qwertyuiop"):
        c[ch] = (u * i + u / 2, top + pad + row * 0.5)
    for i, ch in enumerate("asdfghjkl"):
        c[ch] = (u * 0.5 + u * i + u / 2, top + pad + row * 1.5)
    for i, ch in enumerate("zxcvbnm"):
        c[ch] = (u * 1.5 + u * i + u / 2, top + pad + row * 2.5)
    space_x = u * (1 + 1.2 + 1 + 1 + 1.8)  # centre of space with globe key present
    c[" "] = (space_x, top + pad + row * 3.5)
    return c, u, row


def tap(x, y):
    sh(f"input tap {int(x)} {int(y)}")
    time.sleep(0.08)


def glide(word, c):
    pts = []
    for a, b in zip(word, word[1:]):
        (x0, y0), (x1, y1) = c[a], c[b]
        for s in range(8):
            pts.append((x0 + (x1 - x0) * s / 8, y0 + (y1 - y0) * s / 8))
    pts.append(c[word[-1]])
    cmds = [f"input motionevent DOWN {int(pts[0][0])} {int(pts[0][1])}"]
    cmds += [f"input motionevent MOVE {int(x)} {int(y)}" for x, y in pts[1:]]
    cmds += [f"input motionevent UP {int(pts[-1][0])} {int(pts[-1][1])}"]
    sh("; ".join(cmds))
    time.sleep(0.6)


def field_text():
    x = sh("uiautomator dump /sdcard/u.xml >/dev/null; cat /sdcard/u.xml")
    m = re.findall(r'text="([^"]*)"[^>]*class="android.widget.EditText"[^>]*focused="true"', x)
    if not m:
        m = re.findall(r'<node [^>]*class="android.widget.EditText"[^>]*>', x)
        m = [re.search(r'text="([^"]*)"', n).group(1) for n in m]
    return m[-1] if m else None


if __name__ == "__main__":
    mode, args = sys.argv[1], sys.argv[2:]
    c, u, row = centers()
    if mode == "tap":
        for ch in " ".join(args):
            tap(*c[ch])
    elif mode == "glide":
        for wd in args:
            glide(wd, c)
    elif mode == "edge":
        # tap "the" with each touch 40% of a key width off-centre toward a neighbour
        for ch, dx in (("t", -0.4), ("h", 0.4), ("e", 0.4)):
            x, y = c[ch]
            tap(x + dx * u, y)
        tap(*c[" "])
    print(repr(field_text()))
