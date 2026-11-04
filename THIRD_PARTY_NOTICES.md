# Third-party notices

Reev's own source code is licensed under the **GNU Affero General Public License v3.0 only** (`AGPL-3.0-only`, see [`LICENSE`](LICENSE)). The components below keep their own licences. Full licence texts are in [`licenses/`](licenses/). The same notices ship inside the app: **Settings → Open-source licences**.

Source code for every Reev release: <https://github.com/asispan/reev>

---

## Code

### Glide typing decoder: `app/src/main/java/com/asis/reev/glide/GlideDecoder.kt`
Adapted from FlorisBoard's `StatisticalGlideTypingClassifier`
(<https://github.com/florisboard/florisboard>), Copyright (C) 2025 The FlorisBoard Contributors. That class is in turn based on Étienne Desticourt's statistical gesture classifier for AnySoftKeyboard (<https://github.com/AnySoftKeyboard/AnySoftKeyboard/pull/1870>).
Licensed under the Apache License 2.0 ([`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)).
**Changes:** ported to a dependency-free Kotlin class for Reev's key geometry and vocabulary, with key-centre handling, pruning, scoring constants and API reworked. The file header records the modifications.

### sherpa-onnx (bundled AAR: `libsherpa-onnx-jni.so`, `libsherpa-onnx-c-api.so`, `libsherpa-onnx-cxx-api.so`, Kotlin API)
<https://github.com/k2-fsa/sherpa-onnx>. Copyright Xiaomi Corporation and the sherpa-onnx contributors. Apache License 2.0. No upstream NOTICE file.

### ONNX Runtime (bundled inside the sherpa-onnx AAR: `libonnxruntime.so`)
<https://github.com/microsoft/onnxruntime>. Copyright (c) Microsoft Corporation. MIT License ([`licenses/onnxruntime-MIT.txt`](licenses/onnxruntime-MIT.txt)).

### OkHttp and Okio
<https://square.github.io/okhttp/>. Copyright Square, Inc. Apache License 2.0.

### AndroidX (core-ktx, recyclerview) and Kotlin standard library
Copyright The Android Open Source Project and JetBrains s.r.o. Apache License 2.0.

---

## Data

### Lexicon: `app/src/main/assets/lexicon/` (`words.tsv`, `bigrams.bin`, `contractions.tsv`)
The combined and modified lexicon is licensed under **Creative Commons Attribution-ShareAlike 4.0 International** (CC BY-SA 4.0, [`licenses/CC-BY-SA-4.0.txt`](licenses/CC-BY-SA-4.0.txt)). This is separate from the AGPL that covers the code. It incorporates:

1. **Tatoeba English sentences.** Tatoeba contributors, <https://tatoeba.org>, export `eng_sentences.tsv.bz2` downloaded 3 November 2026. Licence: CC BY 2.0 France (<https://creativecommons.org/licenses/by/2.0/fr/>).
2. **FrequencyWords, English `en_50k` (2018).** Copyright (c) 2016 Hermit Dave, <https://github.com/hermitdave/FrequencyWords>. Derived from the OpenSubtitles corpus (<http://www.opensubtitles.org/>, via OPUS: <http://opus.nlpl.eu/>). Licence: CC BY-SA 4.0.
3. **Hinglish Everyday Conversations 1M.** Abhishek Khatri, <https://huggingface.co/datasets/Abhishekcr448/Hinglish-Everyday-Conversations-1M>. Licence: MIT. The dataset card declares MIT (published November 2024) but includes no separate LICENSE file, so the notice below names the dataset's curator:
   > Copyright (c) 2024 Abhishek Khatri
   >
   > Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
   >
   > The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
   >
   > THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

**Changes by the Reev project:** the sources were tokenised, case-normalised, filtered, deduplicated and merged, then converted into word-frequency, word-pair (bigram) and contraction tables. Hinglish pair counts are halved. `tools/build_lexicon.py` reproduces the build, and [`app/src/main/assets/lexicon/SOURCES.md`](app/src/main/assets/lexicon/SOURCES.md) records the SHA-256 of each output.

### Emoji list: `app/src/main/assets/emoji.tsv`
Derived from Unicode `emoji-test.txt`, Version 18.0 (<https://www.unicode.org/Public/emoji/>), © 2026 Unicode®, Inc. The file was reduced to fully-qualified emoji, grouped by category. Licence: Unicode License v3 ([`licenses/Unicode-3.0.txt`](licenses/Unicode-3.0.txt)). Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in the U.S. and other countries.

---

## Speech models (downloaded at runtime, not bundled)

Reev downloads these directly from Hugging Face, pinned to a fixed commit and checked against a SHA-256 for every file. Reev doesn't redistribute them.

- **Parakeet Ultra** by Moondream (<https://huggingface.co/moondream/parakeet-ultra>), a further-trained version of NVIDIA `parakeet-tdt-0.6b-v3`. Licence: CC BY 4.0 (<https://creativecommons.org/licenses/by/4.0/>). Changes: converted to ONNX and int8-quantised by `mldecode` (<https://huggingface.co/mldecode/parakeet-ultra-onnx-int8>).
- **NVIDIA Parakeet TDT 0.6B v2** by NVIDIA (<https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2>). Licence: CC BY 4.0. Changes: exported to ONNX and int8-quantised for sherpa-onnx by `csukuangfj` (<https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8>).
