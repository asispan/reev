# Spokenly → Reev: what was implemented

Companion to `spokenly-teardown.pdf`. Version 0.2.0 (versionCode 3). Nothing is committed yet.

## Landed

- `polish/CursorContext` + `polish/DictationPolish` — caret context read once per dictation (`getTextBeforeCursor`/`getTextAfterCursor`), leading-space and capitalisation decided from it, unit-tested across sentence start, mid-word, after punctuation, after a bracket, unknown nouns and "I". Sensitive fields get an empty context.
- `mode/Mode` + `mode/ModeStore` — mode record (name, prompt, delivery action, apps, fallback) with three built-ins, JSON persistence, one guaranteed fallback. Long-press the engine chip cycles modes; the resolved mode shows in the idle prompt.
- `delivery/Delivery` — insert, insert-and-send (degrades when the editor has no send action), copy, history-only; clipboard fallback with a sensitive clip and a toast. Field-change mid-flight forces copy.
- `transform/Transformer` — `Local` cleanup always; `Llm` (OpenAI-compatible) only when a mode has a prompt and an endpoint is keyed, falling back on any failure.
- `history/HistoryStore` + `HistoryActivity` — raw text stored before the rewrite, status tracked, capped at 200, retry/copy/delete. `history/AppNames` for friendly labels.
- `ui/VoicePanel` — TRANSCRIBING / INSERTED / ERROR states with their own drawing, labels and a11y strings; single reset path.
- `ui/RationaleView` — three-row microphone rationale before the system dialog; the panel opens itself after a grant.
- ReevService — session-id guards on every callback, a 12 s watchdog, editor-field snapshot, `onWindowHidden` cancel, and graceful failure paths.
- Settings — modes editor, AI endpoint/model/key, spacing and history toggles, history entry point.

## Verified on device (CPH2745)

- Rationale renders in the keyboard; Agree reaches the permission flow.
- Two dictations into WhatsApp: inserted, correct mid-sentence spacing and lower-casing, INSERTED state with the app label, history rows with mode/app/time, no crash.
- `assembleDebug`, `lintDebug` and 37 unit tests pass.

## Not verified / open

- The grant-resume path (`onWindowShown`) compiled but was not re-run on device: the permission is already granted and `pm grant/revoke` and `cmd appops set` are blocked on this ColorOS build.
- AI rewrite path has no live test — no LLM key was entered.
- Devices with the release signature need an uninstall to take a debug build; prefs and the Keystore-wrapped keys do not survive that.
- Deferred: trigger words, word replacements, per-mode temperature, cleanup model.
