# Review

Overall: the plan is sound and the P0/P1/P2 split is roughly right, but the ordering inside P1 is wrong (delivery must land with or before modes), the state machine needs session-generation guards it doesn't mention, and there are three concrete regression risks the plan misses entirely (sensitive-field context reads, composing-text interaction with glide, and `performEditorAction` semantics). Details per question.

---

## 1. Class breakdown — keep ReevService a thin host

ReevService should shrink, not hold steady. Extract everything dictation-related into a controller that the service owns:

```
dictation/DictationController.kt   (~250 lines)
mode/Mode.kt, mode/ModeStore.kt
transform/Transformer.kt           (interface + LocalTransformer + LlmTransformer)
delivery/Delivery.kt               (enum + DeliveryExecutor)
polish/CursorContext.kt, polish/DictationPunctuation.kt
history/HistoryStore.kt
```

Key shape:

```kotlin
class DictationController(
    private val host: Host,          // interface, NOT ReevService directly — testability
    private val modes: ModeStore,
    private val transformer: Transformer,
    private val history: HistoryStore,
    private val prefs: Prefs
) {
    interface Host {
        fun inputConnection(): InputConnection?
        fun editorInfo(): EditorInfo?
        fun isSensitive(): Boolean
        fun setPanelState(state: VoicePanel.State, label: CharSequence?)
        fun runOnUi(token: Any, delayMs: Long = 0, block: () -> Unit)
    }
    fun onEditorChanged(info: EditorInfo, sensitive: Boolean)  // from onStartInputView
    fun onTalkStart()    // reads CursorContext ONCE here, resolves Mode here
    fun onTalkStop()
    fun onCancel()
    // internal: SttCallback impl, transform dispatch, delivery
}
```

```kotlin
interface Transformer {
    // Runs on caller's background executor. Must honour the cancel check.
    fun transform(raw: String, mode: Mode, ctx: CursorContext,
                  isCancelled: () -> Boolean): String   // never throws; falls back to raw/local
}
```

Call sites in ReevService shrink to one-liners: `onStartInputView → controller.onEditorChanged(...)`, VoicePanel listeners → `controller.onTalkStart()` etc., `cancelVoice() → controller.onCancel()`. `insertDictation()` moves into `DeliveryExecutor` entirely; ReevService keeps only the `Host` implementation (~40 lines). Mode resolution lives in `ModeStore.resolve(packageName: String, manualOverrideId: String?): Mode` — a pure lookup, unit-testable with injected JSON. The transform call lives in DictationController on a single-thread `Executor`, never in the service callbacks.

LlmTransformer uses `HttpURLConnection` (no new deps) with 2s connect / 6s read timeouts, and on *any* failure — no key, timeout, non-200, JSON error — returns `LocalTransformer.transform(...)`. That satisfies the "optional AI" constraint structurally rather than with flags scattered around.

## 2. LLM-in-IME hazards

- **Session lifetime / editor switch mid-flight.** `onStartInput` can fire while the transform is in flight (user tapped another field or app). At `onTalkStart`, snapshot a binding: `(packageName, EditorInfo.fieldId, sessionId)`. On transform completion, re-check against current editor. Match → deliver normally. Mismatch → **never commit to the new field**; write to history, clipboard + toast ("Copied — editor changed"). This is the single most important guard; committing an AI-transformed message into the wrong app's text field is the catastrophic failure mode.
- **Cancellation.** `isCancelled: () -> Boolean` closure capturing the sessionId, checked before/after the HTTP call; additionally keep the `HttpURLConnection` reference and call `disconnect()` from `onCancel()` so you don't wait out a read timeout holding the TRANSCRIBING state.
- **Process death.** The IME process is killable. Append the **raw transcript** to HistoryStore *before* starting the transform (status=`pending`), update the entry after. Worst case the user loses the polish, not the words. No resume logic — not worth it.
- **Window hidden / resize.** `onWindowHidden` and `onFinishInputView` must call `controller.onCancel()` if state is RECORDING/LATCHED (otherwise AudioRecord leaks with the keyboard gone — this bug class is common). If TRANSCRIBING, let the transform finish but the binding check above will route it to clipboard. All UI posts go through `host.runOnUi(token, ...)`; teardown does `handler.removeCallbacksAndMessages(token)`.
- **Threading.** Network strictly on the executor. `getSurroundingText(2000,2000,0)` is synchronous binder IPC — do it exactly once at `onTalkStart` on the main thread (it's bounded and fine), never from the background thread, and never re-read after transform. `commitText` from the main thread only; if it returns false (dead IC), fall through to clipboard.
- **Watchdog.** Hard 10s timer per session: fires → ERROR state with local-cleanup text delivered, generation invalidated. Guarantees TRANSCRIBING can never strand.

## 3. HistoryActivity: yes, separate — but trim retry

Separate activity is correct. Do **not** put a scrolling history list inside the IME window (focus-stealing, height negotiation with the editor, bloats VoicePanel), and don't cram it into the already-304-line SettingsActivity — though do add a Settings entry that launches it. Launching from the keyboard requires `FLAG_ACTIVITY_NEW_TASK`.

IME process caveats: by default everything is one process, but don't rely on shared in-memory state anyway — HistoryStore reads the JSONL file fresh and writes atomically (temp file + rename), since the activity and the service have independent lifecycles. **Cut "retry" down**: HistoryActivity has no InputConnection, so retry = re-run transform → copy to clipboard, never re-insert. Say so in the UI. Also: entries are never written for sensitive fields *or* when the editor sets `IME_FLAG_NO_PERSONALIZED_LEARNING` — that's your "no learning" constraint and the plan doesn't mention the flag.

## 4. State machine audit

Transitions that strand or double-fire today's design:

1. **Double `onTalkStop`** — finger-up racing tap-to-latch toggle → two STT finalisations, two deliveries. Guard: `sessionId: Long` incremented in `onTalkStart`; `onTalkStop` is a no-op unless state ∈ {RECORDING, LATCHED} *and* captures the id.
2. **Stale STT callback after cancel** — cloud engine returns after `onCancel` reset to IDLE → panel jumps IDLE→INSERTED. Guard: every `SttCallback` and transform completion carries the sessionId; `if (id != sessionId) return`.
3. **Delayed-reset runnables** — INSERTED→IDLE and ERROR→IDLE timers fire after a *new* session started, snapping RECORDING back to IDLE. Guard: either post with a per-state token and `removeCallbacksAndMessages(token)` on every `setState`, or have the runnable capture the sessionId and check. Do both — it's cheap.
4. **Silent background exception** in transform/delivery → stranded TRANSCRIBING. Guard: `try/finally` that always posts a terminal state, plus the 10s watchdog from Q2.
5. **Illegal transitions.** Encode a small allow-map in `setState(s, forSession)`; log-and-ignore illegal ones rather than crash. Explicitly allow IDLE→RECORDING from INSERTED/ERROR (user re-dictates immediately — cancel pending timers, bump session) rather than forcing a visible IDLE frame.

```kotlin
fun setState(s: State, forSession: Long) {
    if (forSession != sessionId) return
    handler.removeCallbacksAndMessages(stateToken)
    if (!legal(current, s)) { log; return }
    ...
}
```

Also: each new state needs a VirtualNode content description and an accessibility announcement, or you regress the 0.1.2 screen-reader work. Announce TRANSCRIBING and the terminal states; key announcements to sessionId too so stale announcements don't fire.

## 5. Landing order

1. **P0a + P0b together, one PR** — CursorContext + DictationPunctuation are one feature; shipping the data class without the consumer is pointless. Ship: measurably better spacing/caps, fully unit-tested, zero new UI.
2. **P0c** state machine + guards above. Ship: better feedback, no behaviour change.
3. **Extract DictationController** (my Q1 breakdown) as its own refactor PR *before* modes — otherwise P1 lands into a 649-line service and you'll never un-tangle it.
4. **P1b + P1a together** — delivery before/with modes, not after. A Mode whose `delivery` field can't execute is dead weight; conversely delivery alone (default INSERT) is shippable. Mode v1 = prompt-less: app→{delivery, cleanup toggles}. Ship: Messages-mode insert-and-send works with pure local cleanup.
5. **P1c** history.
6. **P2a** trigger words + word replacements — these are local, cheap, high-value; do them *before* the LLM.
7. **P2b** LlmTransformer; only now expose model/temperature/prompt in Mode.
8. **P1d last** (rationale/practice) — it's the least risky and most cuttable.

Defer: encrypted history (JSONL in app-private storage is fine for v1), mode override UI in the panel (see Q6), everything "tone" until P2b.

## 6. Cuts and missed risks

**Over-engineered:**
- **Mode fields `model`/`temperature`/`prompt` in P1a** when no LLM exists until P2. Put a `schemaVersion` in the JSON and add fields when they do something. Shipping dead settings UI is how keyboards get cluttered.
- **"Tone" without an LLM is fiction.** Deterministic cleanup has no tone. Either define it as concrete local rules (punctuation aggressiveness, caps style) or defer the word entirely to P2b.
- **Practice *loop*** → one dismissible practice card. The rationale screen must gate only the mic, never keyboard enablement — a typing-only user should never see it.
- **Mode override in the voice panel** — the panel already has an engine chip, cancel, and latch affordances on a keyboard-sized canvas. Make override a long-press on the existing chip or cut it from v1.
- **INSERTED as a state** is fine but keep it timer-only (~600ms), no interactions.

**Missed regression risks — these are the serious ones:**
- **`getSurroundingText(2000,2000,0)` in sensitive fields reads password context.** CursorContext must return `CursorContext.EMPTY` when `isSensitive()`, and handle a null return (editors may not support it) everywhere. This is a must-fix in P0a, not a later hardening.
- **Glide typing composition.** `commitText` during an active composing region clobbers the glide word. DeliveryExecutor must `finishComposingText()` first, and the suggestions strip (OverlayView) needs its context reset/updated after a dictation commit, or you'll show suggestions for stale text.
- **`performEditorAction` is not "send".** Resolve the action from `EditorInfo.imeOptions` at delivery time: if the editor's action is SEARCH/GO/NONE or `IME_FLAG_NO_ENTER_ACTION` is set, degrade INSERT_SEND→INSERT silently. Never INSERT_SEND in sensitive fields. The mode stores *intent*, the editor decides *mechanism*.
- **Clipboard previews.** On 33+ set `ClipDescription.EXTRA_IS_SENSITIVE` on transcript clips so dictated text doesn't float in the system clipboard overlay.
- **Tests:** CursorContext/DictationPunctuation/ModeStore.resolve/LocalTransformer are pure — good. Make LlmTransformer's HTTP a one-method seam (`fun post(url, body): String`) so unit tests inject a fake and the no-network constraint holds without Robolectric gymnastics.
