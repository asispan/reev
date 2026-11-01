# Reev

An English + emoji Android keyboard (IME) for phones, with Wispr-Flow-style push-to-talk dictation. Tap the mic in the top bar and the keyboard turns into one big talk button: hold to talk, release to insert. A short tap locks it hands-free. The transcript streams live in the top strip.

Dictation engines:

| Engine | How it runs | Model / endpoint |
|---|---|---|
| **Parakeet Ultra** (default) | On-device, offline, sherpa-onnx int8 on CPU | `mldecode/parakeet-ultra-onnx-int8` (Moondream's Parakeet Ultra, a fine-tune of NVIDIA parakeet-tdt-0.6b-v3) |
| NVIDIA Parakeet TDT 0.6B v2 | On-device, offline | `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8` |
| ElevenLabs | Realtime WebSocket, manual commit | `scribe_v2_realtime` |
| Cartesia | Realtime WebSocket, `finalize` | `ink-2` |
| Smallest AI | Realtime WebSocket, `close_stream` | `pulse` |
| Sarvam | Realtime WebSocket (beta), manual endpointing | `saaras:v3-realtime` |

## Measured on OnePlus 15 (SM8850, Android 16)

Test clip: an 8.6 s TTS sentence, streamed at real-time pace through the same path the mic uses (`BenchReceiver`). "Release→final" is the time from lifting your finger to having the final text.

| Engine | First partial | Release→final | Transcript |
|---|---|---|---|
| Parakeet Ultra (warm) | 0.81 s | **0.49 s** | exact |
| Parakeet Ultra (cold, model load 2.7 s) | 3.5 s | 0.97 s | exact |
| Parakeet v2 (warm) | 0.82 s | 0.50 s | exact |
| Cartesia ink-2 | 1.71 s | **0.11 s** | exact |
| Smallest pulse | 1.08 s | 0.19 s | missing one sentence break |
| ElevenLabs scribe_v2_realtime | 2.29 s | 0.47 s | exact |
| Sarvam | — | — | not benchmarked yet (the test key was rejected with `invalid_api_key_error`) |

Real-mic check (speaker → phone mic, holding the button): Parakeet Ultra transcribed 10.9 s of audio in 650 ms with the exact text, and ElevenLabs gave the right text too.

## Rule: keyboard only

Reev never draws outside the keyboard window: no overlay permission, no popup windows, no toasts. Key previews and long-press menus are drawn inside the IME window.

## Keyboard features

- QWERTY with long-press digits and accents, a symbols page plus a second symbols page, and key preview bubbles. Roll-over handles fast two-thumb typing.
- Auto-capitalisation, double-tap shift for caps lock, double-space for a period, and smart punctuation spacing.
- Suggestion strip: a 40k-word frequency dictionary, ranked prefix completions, contractions (`im` → `I'm`), and words you type often are learned.
- Conservative autocorrect on space. It weights transpositions, doubled letters and adjacent keys higher, and skips slang (thx, lol…). Backspace right after a correction undoes it.
- Swipe on the space bar to move the cursor. Swipe left from backspace to delete a word. Holding backspace speeds up to deleting whole words.
- Emoji panel: Unicode 18 set, 1,923 emoji in 9 categories, plus recents.
- Theme: follow system (default), Light or Dark, set in Settings and applied instantly. Suggestions are turned off in password, email and URL fields.

Dictation ideas taken from Spokenly (macOS/iOS, reverse-engineered strings):
- keep the model loaded and warmed up
- remove filler words ("um", "uh")
- detect a silent mic
- split long audio into windows (here: about 20 s chunks cut at the quietest frame)

## Build & install

```bash
# JDK 21 (AGP 8.13 / Gradle 9.0); Android SDK 36
./tools/fetch_deps.sh             # one-time: download the sherpa-onnx AAR into app/libs
./tools/deploy.sh                 # build debug, install over USB, auto-tap ColorOS "Install", select the IME
REEV_CARTESIA_KEY=... python3 tools/inject_keys.py   # optional: push API keys from env vars (or paste them in-app)
```

Note: `adb push`ing models into `Android/data/com.asis.reev` leaves them unreadable by the app (they end up owned by shell); download them in the app instead.

Other phones: install `app-release.apk`, open Reev, follow the three setup steps, then download a Parakeet model in the app (about 630–660 MB, with resume support) or paste API keys.

Benchmark:
```bash
adb push test.wav /sdcard/Android/data/com.asis.reev/files/test.wav
adb shell am broadcast -n com.asis.reev/.BenchReceiver --es engine local --es wav /sdcard/Android/data/com.asis.reev/files/test.wav
adb logcat -s ReevBench
```
(`BenchReceiver` only does anything in debuggable builds.)

## Honest limitations

- Parakeet isn't a streaming model. Live partials come from re-decoding the growing buffer every 0.7 s, which costs CPU and battery on long dictations. The final transcript uses one full decode. A QNN/NPU build for SM8850 exists in sherpa-onnx (RTF about 0.06) but isn't wired up. It would also need fixed-length context binaries per chip, which breaks the "all Android phones" goal.
- Cloud API keys sit in the app's private SharedPreferences and are sent straight to the providers. That's fine for a personal build. A public release would need short-lived tokens minted by a backend; Smallest's docs ask for this explicitly.
- Sarvam's realtime endpoint is beta and hasn't been verified end to end.
- There's no glide typing, no next-word prediction, and English only.
- The release APK is signed with the debug key, so it's for sideloading only.
- The 1,923-emoji grid is shown without skin-tone variants.

## Credits & licences

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0): on-device inference
- [Parakeet Ultra](https://huggingface.co/moondream/parakeet-ultra) by Moondream (CC-BY-4.0), int8 ONNX export by `mldecode`; [NVIDIA Parakeet TDT 0.6B v2](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2) (CC-BY-4.0). Models are downloaded at runtime, not bundled.
- Word frequencies: [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords) (OpenSubtitles 2018, CC-BY-SA-4.0)
- Emoji list: Unicode `emoji-test.txt` v18 (Unicode License)
