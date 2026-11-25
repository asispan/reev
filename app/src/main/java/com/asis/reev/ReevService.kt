package com.asis.reev

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.asis.reev.audio.MicRecorder
import com.asis.reev.stt.Engines
import com.asis.reev.stt.SttCallback
import com.asis.reev.stt.SttSession
import com.asis.reev.ui.EmojiView
import com.asis.reev.ui.KbMode
import com.asis.reev.ui.KeyboardView
import com.asis.reev.ui.OverlayView
import com.asis.reev.ui.Palette
import com.asis.reev.ui.ShiftState
import com.asis.reev.ui.TopBar
import com.asis.reev.ui.VoicePanel
import com.asis.reev.delivery.Delivery
import com.asis.reev.history.HistoryStore
import com.asis.reev.mode.DeliveryAction
import com.asis.reev.mode.Mode
import com.asis.reev.mode.ModeStore
import com.asis.reev.polish.CursorContext
import com.asis.reev.polish.DictationPolish
import com.asis.reev.transform.Llm
import com.asis.reev.transform.Local
import com.asis.reev.transform.Transformer
import android.os.SystemClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.asis.reev.ui.RationaleView
import com.asis.reev.ui.dpi

class ReevService : InputMethodService(), KeyboardView.Listener, TopBar.Listener,
    EmojiView.Listener, VoicePanel.Listener, RationaleView.Listener {

    private lateinit var prefs: Prefs
    private lateinit var suggest: Suggest
    private lateinit var modeStore: ModeStore
    private lateinit var history: HistoryStore
    private val main = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private lateinit var topBar: TopBar
    private lateinit var keyboard: KeyboardView
    private var emoji: EmojiView? = null
    private lateinit var voice: VoicePanel
    private lateinit var content: FrameLayout
    private lateinit var overlay: OverlayView

    // editor state
    private var noSuggest = false
    private var noLearn = false
    /** Password, PIN or incognito field: no dictation, no suggestions, no learning. */
    private var sensitive = false
    /** Bumped on every new editor: async results (glide) from a previous field are dropped. */
    private var inputGen = 0
    private var lastSpaceAt = 0L
    private var autoSpaced = false
    private var lastCorrection: Pair<String, String>? = null // original -> corrected (+space)

    // glide typing
    private val glideExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var glideDecoder: com.asis.reev.glide.GlideDecoder? = null
    private var glideLayoutKey = ""
    private var glideWord: String? = null          // last glided word, as committed
    private var glideAlts: List<String> = emptyList()
    private var afterGlide = false                 // cursor sits right after a glided word

    // voice state
    private var recorder: MicRecorder? = null
    private var session: SttSession? = null
    private var sessionId = 0
    private var finalTimeout: Runnable? = null
    @Volatile private var peakLevel = 0f

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        modeStore = ModeStore(this)
        history = HistoryStore(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(themeListener)
        suggest = Suggest(this)
        Engines.warm(this, prefs.engine)
    }

    private var builtPalette: Palette? = null
    private val themeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "hinglish") suggest.setHinglish(prefs.hinglish)
        if (key == "theme" && root != null) main.post { if (Palette.of(this) != builtPalette) setInputView(onCreateInputView()) }
    }

    override fun onCreateInputView(): View {
        val pal = Palette.of(this)
        builtPalette = pal
        emoji = null
        overlay = OverlayView(this, pal)
        topBar = TopBar(this, pal).also { it.listener = this; it.micEnabled = !sensitive }
        keyboard = KeyboardView(this, pal).also { it.listener = this; it.overlay = overlay; it.previewsEnabled = !sensitive }
        voice = VoicePanel(this, pal).also { it.listener = this }
        content = FrameLayout(this)
        content.addView(keyboard)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
            addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Root sized by the column only; the overlay matches it (a MATCH_PARENT child would
        // otherwise blow the IME window up to full screen).
        val fl = object : FrameLayout(this) {
            override fun onMeasure(wSpec: Int, hSpec: Int) {
                col.measure(wSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                val w = col.measuredWidth; val h = col.measuredHeight
                this@ReevService.overlay.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
                setMeasuredDimension(w, h)
            }
        }
        fl.clipChildren = false
        fl.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fl.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fl.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            col.setPadding(0, 0, 0, bottom + dpi(4))
            insets
        }
        root = fl
        applyPrefs()
        return fl
    }

    private fun applyPrefs() {
        val h = prefs.haptics
        keyboard.hapticsOn = h; topBar.hapticsOn = h; voice.hapticsOn = h; emoji?.hapticsOn = h
        keyboard.glideEnabled = prefs.glideTyping
        voice.engineLabel = prefs.engine.short
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Theme toggled in settings (or system dark mode flipped): rebuild the views.
        if (builtPalette != null && Palette.of(this) != builtPalette) setInputView(onCreateInputView())
        applyPrefs()
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val isPassword = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        noSuggest = isPassword || cls != InputType.TYPE_CLASS_TEXT ||
            variation == InputType.TYPE_TEXT_VARIATION_URI || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        inputGen++
        val numericPassword = cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val incognito = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        sensitive = isPassword || numericPassword || incognito
        if (sensitive) noSuggest = true
        // Incognito / sensitive fields: never learn words from them.
        noLearn = noSuggest || incognito
        topBar.micEnabled = !sensitive
        keyboard.previewsEnabled = !sensitive
        if (sensitive) topBar.suggestions = emptyList()
        if (sensitive) { cancelVoice(); if (this::topBar.isInitialized && topBar.voiceMode) showKeys() }
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        keyboard.enterAction = if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
            (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) 0
        else if (action in 2..6) action else 0
        keyboard.showSwitchKey = false   // no globe key: Android's own keyboard switcher handles this
        if (!restarting) {
            keyboard.mode = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME) KbMode.SYMBOLS else KbMode.LETTERS
            if (keyboard.shift == ShiftState.LOCKED) keyboard.shift = ShiftState.OFF
            showKeys()
        }
        lastCorrection = null; autoSpaced = false
        if (this::voice.isInitialized) voice.modeLabel = modeStore.resolve(info.packageName, prefs.modeOverride).takeIf { !it.fallback }?.name.orEmpty()
        clearGlide()
        updateShift(); updateSuggestions()
    }

    private fun clearGlide() { glideWord = null; glideAlts = emptyList(); afterGlide = false }

    override fun onWindowHidden() {
        super.onWindowHidden()
        if (this::voice.isInitialized && voice.isShown &&
            (voice.state == VoicePanel.State.RECORDING || voice.state == VoicePanel.State.LATCHED)) cancelVoice()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        cancelVoice()
        overlay.hidePopup(); overlay.hidePreview()
    }

    override fun onUpdateSelection(oss: Int, ose: Int, nss: Int, nse: Int, cs: Int, ce: Int) {
        super.onUpdateSelection(oss, ose, nss, nse, cs, ce)
        if (root == null) return
        if (glideWord != null && wordBeforeCursor() != glideWord) clearGlide()
        updateShift(); updateSuggestions()
    }

    override fun onEvaluateFullscreenMode() = false

    // ---------------- panels ----------------
    private fun showKeys() {
        if (root == null) return
        content.removeAllViews(); content.addView(keyboard)
        topBar.voiceMode = false
        updateSuggestions()
    }

    /** Spoken by a screen reader if one is running; silent otherwise. */
    private fun say(msg: String) { root?.announceForAccessibility(msg) }

    private fun showEmoji() {
        val e = emoji ?: EmojiView(this, Palette.of(this)) { prefs.recentEmoji.split(' ').filter { it.isNotEmpty() } }
            .also { it.listener = this; it.hapticsOn = prefs.haptics; emoji = it }
        e.rebuild()
        content.removeAllViews(); content.addView(e)
        topBar.voiceMode = false
        say("Emoji")
    }

    private fun showVoice() {
        content.removeAllViews(); content.addView(voice)
        topBar.voiceMode = true
        // Holding a button isn't possible with a screen reader exploring by touch: there the talk button toggles.
        val explore = getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        topBar.transcript = ""; topBar.status = if (explore) "Activate Start dictation, then speak" else "Hold the mic and speak"
        voice.state = VoicePanel.State.IDLE
        voice.engineLabel = prefs.engine.short
        val reason = Engines.create(this, prefs.engine).notReadyReason()
        voice.hint = reason?.let { setupPill() }
        voice.hintTappable = reason != null          // tap the hint to open Reev settings
        voice.talkEnabled = reason == null
        if (reason != null) topBar.status = "Dictation isn't set up yet"
        if (reason == null && !prefs.autoStartVoice) say("Voice typing")
        Engines.warm(this, prefs.engine)
        if (reason == null && prefs.autoStartVoice) { onTalkStart(); voice.state = VoicePanel.State.LATCHED }
    }

    // ---------------- editing helpers ----------------
    private fun wordBeforeCursor(): String {
        val before = currentInputConnection?.getTextBeforeCursor(48, 0)?.toString() ?: return ""
        var i = before.length
        while (i > 0 && (before[i - 1].isLetterOrDigit() || before[i - 1] == '\'' || before[i - 1] == '\u2019')) i--
        return before.substring(i)
    }

    /**
     * The word before the current one (skipping [curLen] chars of the word being typed), or null at a
     * sentence start. [singleSpace] is true when exactly one space separates them (safe to rewrite).
     */
    private fun prevWord(curLen: Int): Pair<String?, Boolean> {
        val before = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: return null to false
        val end = before.length - curLen
        if (end <= 0) return null to false
        var j = end
        while (j > 0 && before[j - 1] == ' ') j--
        val gap = before.substring(j, end)
        if (j == 0 || !(before[j - 1].isLetterOrDigit() || before[j - 1] == '\'' || before[j - 1] == '\u2019')) return null to false
        var k = j
        while (k > 0 && (before[k - 1].isLetterOrDigit() || before[k - 1] == '\'' || before[k - 1] == '\u2019')) k--
        return before.substring(k, j) to (gap == " ")
    }

    private var pinned: String? = null   // word the user explicitly chose/restored: never auto-rewrite it

    private fun updateShift() {
        if (!this::keyboard.isInitialized) return
        if (keyboard.shift == ShiftState.LOCKED) return
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo ?: return
        val caps = prefs.autoCap && ei.inputType != 0 && ic.getCursorCapsMode(ei.inputType) != 0
        keyboard.shift = if (caps) ShiftState.ON else ShiftState.OFF
    }

    private fun updateSuggestions() {
        if (!this::topBar.isInitialized || topBar.voiceMode) return
        if (noSuggest) { topBar.suggestions = emptyList(); return }
        val w = wordBeforeCursor()
        val gw = glideWord
        if (gw != null && glideAlts.isNotEmpty() && w == gw) { topBar.suggestions = glideAlts; return }
        // Digits, or a lone accented letter the English dictionary can only echo back: nothing useful to suggest.
        if (w.any { it.isDigit() } || (w.length == 1 && w[0].code > 0x7F)) { topBar.suggestions = emptyList(); return }
        val prev = prevWord(w.length).first
        topBar.suggestions = if (w.isNotEmpty()) suggest.suggest(w, prev) else {
            // next-word prediction right after a space
            val last = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: ""
            if (last == " " && prev != null) suggest.nextWords(prev) else emptyList()
        }
    }

    private fun commit(text: String) { currentInputConnection?.commitText(text, 1) }

    // ---------------- KeyboardView.Listener ----------------
    override fun onKeyText(text: String) {
        val ic = currentInputConnection ?: return
        if (text == " ") { afterGlide = false; glideWord = null; onSpaceKey(); return }
        if (afterGlide) {
            // Gboard-style: tapping a letter right after a glided word starts a new word.
            if (text.length == 1 && text[0].isLetterOrDigit()) ic.commitText(" ", 1)
            clearGlide()
        }
        val isPunct = text.length == 1 && text[0] in ".,?!:;)"
        if (isPunct && autoSpaced) {
            val b = ic.getTextBeforeCursor(1, 0)
            if (b == " ") ic.deleteSurroundingText(1, 0)
            ic.commitText(text + if (text[0] in ".,?!:;") " " else "", 1)
            autoSpaced = text[0] in ".,?!:;"
        } else {
            ic.commitText(text, 1)
            autoSpaced = false
        }
        lastCorrection = null
        if (keyboard.shift == ShiftState.ON && keyboard.mode == KbMode.LETTERS) keyboard.shift = ShiftState.OFF
        // after typing a symbol like ' or digits, return to letters on space; Gboard-like
        updateShift()
        if (text.any { it.code > 0x7F }) updateSuggestions()   // accent/symbol commit: don't wait for the editor's selection update
    }

    private fun onSpaceKey() {
        val ic = currentInputConnection ?: return
        val now = System.currentTimeMillis()
        if (prefs.doubleSpacePeriod && !noSuggest && now - lastSpaceAt < 450) {
            val b = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (b.length == 2 && b[1] == ' ' && (b[0].isLetterOrDigit() || b[0] in ")\"'")) {
                ic.deleteSurroundingText(1, 0); ic.commitText(". ", 1)
                lastSpaceAt = 0; autoSpaced = true; lastCorrection = null; updateShift(); return
            }
        }
        lastSpaceAt = now
        val w = wordBeforeCursor()
        if (!noSuggest && w.isNotEmpty()) {
            val (w1, singleSpace) = prevWord(w.length)
            val fix = if (prefs.autocorrect) suggest.autocorrect(w, w1) else null
            val cur = if (fix != null && fix != w) fix else w
            // Context fix of the previous word, e.g. "well see" -> "we'll see" (never "well done").
            val retro = if (prefs.autocorrect && prefs.contextFix && w1 != null && singleSpace &&
                !w1.equals(pinned, ignoreCase = true)) suggest.retroCorrect(w1, cur) else null
            if (cur != w || retro != null) {
                val orig = if (retro != null) "$w1 $w" else w
                val replacement = (if (retro != null) "$retro " else "") + "$cur "
                ic.beginBatchEdit()
                ic.deleteSurroundingText(orig.length, 0); ic.commitText(replacement, 1)
                ic.endBatchEdit()
                lastCorrection = orig to replacement
                if (!noLearn) suggest.learn(cur)
                autoSpaced = true
                if (keyboard.mode != KbMode.LETTERS) keyboard.mode = KbMode.LETTERS
                return
            }
            if (!noLearn) suggest.learn(w)
        }
        ic.commitText(" ", 1)
        lastCorrection = null; autoSpaced = false
        if (keyboard.mode == KbMode.SYMBOLS && w.isEmpty()) {} // stay
    }

    override fun onDelete() {
        val ic = currentInputConnection ?: return
        // Backspace right after a glided word removes the whole word (as Gboard does).
        val gw = glideWord
        if (afterGlide && gw != null) {
            val before = ic.getTextBeforeCursor(gw.length + 1, 0)?.toString() ?: ""
            clearGlide()
            if (before.endsWith(gw)) {
                val n = if (before.length == gw.length + 1 && before[0] == ' ') gw.length + 1 else gw.length
                ic.deleteSurroundingText(n, 0); return
            }
        }
        lastCorrection?.let { (orig, fixed) ->
            pinned = orig.substringBefore(' ')
            val b = ic.getTextBeforeCursor(fixed.length, 0)?.toString()
            if (b == fixed) {
                ic.deleteSurroundingText(fixed.length, 0); ic.commitText(orig, 1)
                lastCorrection = null; autoSpaced = false; return
            }
        }
        lastCorrection = null; autoSpaced = false
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1)
        else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    override fun onDeleteWord() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: return
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        ic.deleteSurroundingText(before.length - i, 0)
        lastCorrection = null; autoSpaced = false
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo
        val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (keyboard.enterAction != 0 && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        lastCorrection = null; autoSpaced = false
    }

    override fun onEmoji() = showEmoji()

    override fun onShift() {
        val dbl = keyboard.shiftTappedAt(System.currentTimeMillis())
        keyboard.shift = when {
            dbl -> ShiftState.LOCKED
            keyboard.shift == ShiftState.OFF -> ShiftState.ON
            else -> ShiftState.OFF
        }
    }

    override fun onModeChange(mode: KbMode) { if (mode == KbMode.LETTERS) updateShift() }

    override fun onCursorMove(steps: Int) {
        val ic = currentInputConnection ?: return
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    override fun onKeyDownFeedback() {}

    override fun onSwitchIme(longPress: Boolean) {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        if (longPress) { imm.showInputMethodPicker(); return }
        val switched = if (Build.VERSION.SDK_INT >= 28) switchToNextInputMethod(false) else {
            val token = window?.window?.attributes?.token
            @Suppress("DEPRECATION")
            token != null && imm.switchToNextInputMethod(token, false)
        }
        if (!switched) imm.showInputMethodPicker()
    }

    override fun letterPrior(): FloatArray? {
        if (noSuggest) return null
        val w = if (afterGlide) "" else wordBeforeCursor()
        if (w.length > 20) return null
        return suggest.letterPrior(w)
    }

    override fun onGlide(xs: FloatArray, ys: FloatArray, n: Int) {
        val cx = xs.copyOf(n); val cy = ys.copyOf(n)
        val layoutKey = "${keyboard.width}x${keyboard.height}:${keyboard.showSwitchKey}"
        val centers = if (layoutKey != glideLayoutKey || glideDecoder == null) keyboard.letterCenters() else null
        val shift = keyboard.shift
        val gen = inputGen
        glideExec.execute {
            if (centers != null) {
                if (!suggest.ready || centers.isEmpty()) return@execute
                glideDecoder = com.asis.reev.glide.GlideDecoder(centers, suggest.glideVocab())
                glideLayoutKey = layoutKey
            }
            val t0 = System.currentTimeMillis()
            val cands = glideDecoder?.decode(cx, cy, n, 5) ?: emptyList()
            if (BuildConfig.DEBUG) android.util.Log.d("ReevGlide", "decoded $n pts in ${System.currentTimeMillis() - t0} ms, ${cands.size} candidates")
            main.post { if (gen == inputGen && !sensitive) commitGlide(cands, shift) }
        }
    }

    private fun commitGlide(cands: List<String>, shift: ShiftState) {
        val ic = currentInputConnection ?: return
        if (cands.isEmpty()) return
        val withForms = (cands.take(1) + suggest.contractionsOf(cands[0]) + cands.drop(1)).distinct()
        val shown = withForms.map { suggest.display(it, capFirst = shift == ShiftState.ON, allCaps = shift == ShiftState.LOCKED) }
        val word = shown[0]
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        val lead = if (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "([{\"'“‘/@#") " " else ""
        ic.commitText(lead + word, 1)
        glideWord = word; glideAlts = shown; afterGlide = true
        autoSpaced = false; lastCorrection = null
        if (keyboard.shift == ShiftState.ON) keyboard.shift = ShiftState.OFF
        topBar.suggestions = shown
        updateShift()
    }

    // ---------------- TopBar.Listener ----------------
    override fun onSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private var rationale: RationaleView? = null

    override fun onMic() {
        if (sensitive) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showRationale()
            return
        }
        showVoice()
        if (!prefs.practiced && voice.hint == null) voice.hint = "Hold the mic and say a sentence"
    }

    /** Ask in the keyboard before the system dialog, so the first request is a decision. */
    private fun showRationale() {
        if (root == null) return
        val v = rationale ?: RationaleView(this, Palette.of(this)).also {
            it.listener = this; rationale = it
        }
        content.removeAllViews(); content.addView(v)
        topBar.voiceMode = true
        v.announceForAccessibility("How Reev hears you")
    }

    override fun onRationaleDecline() {
        showKeys()
        say("Keyboard")
    }

    override fun onRationaleAgree() {
        wantVoiceAfterGrant = true
        startActivity(Intent(this, PermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private var wantVoiceAfterGrant = false

    /** Coming back from the permission dialog: open the panel if the user agreed a moment ago. */
    override fun onWindowShown() {
        super.onWindowShown()
        if (!wantVoiceAfterGrant) return
        wantVoiceAfterGrant = false
        if (sensitive) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            showVoice()
            if (!prefs.practiced && voice.hint == null) voice.hint = "Hold the mic and say a sentence"
        } else {
            showRationale()
        }
    }

    override fun onSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        val w = wordBeforeCursor()
        val gw = glideWord
        if (gw != null && w == gw) {
            // Tapping a glide candidate (even the one already shown) confirms it: swap if needed,
            // add the space and move on to next-word predictions.
            clearGlide()
            ic.beginBatchEdit(); ic.deleteSurroundingText(gw.length, 0); ic.commitText("$word ", 1); ic.endBatchEdit()
            pinned = word
            if (!noLearn) suggest.learn(word)
            autoSpaced = true; lastCorrection = null
            updateShift(); updateSuggestions()
            return
        }
        ic.beginBatchEdit()
        if (w.isNotEmpty()) ic.deleteSurroundingText(w.length, 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        pinned = word
        if (!noLearn) suggest.learn(word)
        autoSpaced = true; lastCorrection = null
    }

    override fun onBackToKeys() { cancelVoice(); showKeys(); say("Keyboard") }

    // ---------------- EmojiView.Listener ----------------
    override fun onEmoji(e: String) {
        commit(e)
        val rec = (listOf(e) + prefs.recentEmoji.split(' ').filter { it.isNotEmpty() && it != e }).take(32)
        prefs.recentEmoji = rec.joinToString(" ")
        autoSpaced = false; lastCorrection = null
    }

    override fun onAbc() { showKeys(); say("Keyboard") }
    override fun onSpace() { commit(" ") }

    // ---------------- VoicePanel.Listener ----------------
    // A dictation is bound to one editor field for its whole life. sessionId guards every callback;
    // the field snapshot decides whether the finished text may be committed or has to be copied.

    private var sessionMode: Mode = Mode.builtIns().first()
    private var sessionCtx: CursorContext = CursorContext.EMPTY
    private var sessionPkg: String = ""
    private var sessionFieldId: Int = 0
    private var sessionStartedAt = 0L
    private var watchdog: Runnable? = null
    private var resetRunnable: Runnable? = null
    private val transformExec: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var transformer: Transformer? = null

    /** Chosen per utterance: the LLM only when a mode has a prompt and an endpoint is configured. */
    private fun transformerFor(mode: Mode): Transformer {
        val cfg = if (prefs.aiKey.isNotEmpty() && prefs.aiModel.isNotEmpty() && mode.prompt.isNotBlank()) Llm(prefs) else Local()
        transformer = cfg
        return cfg
    }

    /** Text either side of the caret, read once per dictation. Empty in password fields. */
    private fun cursorContext(): CursorContext {
        if (sensitive) return CursorContext.EMPTY
        val ic = currentInputConnection ?: return CursorContext.EMPTY
        val w = CursorContext.WINDOW
        val before = runCatching { ic.getTextBeforeCursor(w, 0)?.toString() }.getOrNull() ?: return CursorContext.EMPTY
        val after = runCatching { ic.getTextAfterCursor(w, 0)?.toString() }.getOrNull() ?: ""
        return CursorContext(before, after)
    }

    override fun onTalkStart() {
        if (session != null) return
        val engine = Engines.create(this, prefs.engine)
        engine.notReadyReason()?.let { notReady(it); return }
        voice.hint = null; voice.insertedIn = ""; voice.talkEnabled = true
        val id = ++sessionId
        val info = currentInputEditorInfo
        sessionPkg = info?.packageName ?: ""
        sessionFieldId = info?.fieldId ?: 0
        sessionMode = modeStore.resolve(sessionPkg, prefs.modeOverride)
        sessionCtx = cursorContext()
        sessionStartedAt = SystemClock.elapsedRealtime()
        topBar.transcript = ""; topBar.status = "Listening…"; topBar.transcriptPending = true
        val s = engine.start(object : SttCallback {
            override fun onPartial(text: String) = main.post { if (id == sessionId) topBar.transcript = text }.let {}
            override fun onFinal(text: String) = main.post { deliverFinal(id, text) }.let {}
            override fun onError(msg: String) = main.post {
                if (id == sessionId) {
                    stopMic(); session = null
                    failSession(id, msg)
                }
            }.let {}
        })
        session = s
        peakLevel = 0f
        val rec = MicRecorder(onChunk = { s.feed(it) }, onLevel = { lv -> if (lv > peakLevel) peakLevel = lv; main.post { voice.pushLevel(lv) } },
            onError = { main.post { if (id == sessionId) { cancelVoice(); failSession(sessionId, "Microphone stopped unexpectedly") } } })
        if (!rec.start()) {
            s.cancel(); session = null
            voice.hint = "Microphone unavailable"; voice.state = VoicePanel.State.IDLE
            return
        }
        recorder = rec
        voice.state = VoicePanel.State.RECORDING
        armWatchdog(id)
    }

    override fun onTalkStop() {
        if (voice.state != VoicePanel.State.RECORDING && voice.state != VoicePanel.State.LATCHED) return
        val s = session ?: run { voice.state = VoicePanel.State.IDLE; return }
        stopMic()
        val id = sessionId
        voice.state = VoicePanel.State.TRANSCRIBING
        topBar.status = "Transcribing…"
        s.finish()
        finalTimeout = Runnable {
            if (id == sessionId && session != null) {
                s.cancel(); session = null
                failSession(id, "Timed out waiting for transcript")
            }
        }.also { main.postDelayed(it, 20_000) }
    }

    override fun onTalkCancel() {
        cancelVoice()
        topBar.transcript = ""; topBar.status = "Cancelled"
        say("Cancelled")
    }

    private fun stopMic() { recorder?.stop(); recorder = null }

    /** Friendly name for the INSERTED label; see AppNames. */
    private fun appName(pkg: String): String = com.asis.reev.history.AppNames.of(pkg)

    private fun cancelVoice() {
        stopMic()
        transformer?.abort()
        session?.cancel(); session = null
        sessionId++
        finalTimeout?.let { main.removeCallbacks(it) }
        clearTimers()
        if (this::voice.isInitialized) voice.state = VoicePanel.State.IDLE
    }

    private fun clearTimers() {
        watchdog?.let { main.removeCallbacks(it); watchdog = null }
        resetRunnable?.let { main.removeCallbacks(it); resetRunnable = null }
    }

    /** Nothing may sit in TRANSCRIBING forever, whatever the engine or the network does. */
    private fun armWatchdog(id: Int) {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = Runnable {
            if (id == sessionId && voice.state != VoicePanel.State.IDLE) {
                session?.cancel(); session = null; stopMic()
                failSession(id, "Timed out")
            }
        }.also { main.postDelayed(it, 12_000) }
    }

    private fun failSession(id: Int, msg: String) {
        if (id != sessionId) return
        clearTimers()
        voice.hint = msg.take(90)
        voice.state = VoicePanel.State.ERROR
        topBar.transcriptPending = false; topBar.status = "Error"
        say(msg)
        main.postDelayed(Runnable {
            if (id == sessionId && voice.state == VoicePanel.State.ERROR) { voice.state = VoicePanel.State.IDLE; voice.hint = null }
        }.also { resetRunnable = it }, 2_600)
    }

    private fun deliverFinal(id: Int, raw: String) {
        if (id != sessionId) return
        finalTimeout?.let { main.removeCallbacks(it) }
        watchdog?.let { main.removeCallbacks(it); watchdog = null }
        session = null
        topBar.transcriptPending = false
        if (raw.isBlank()) { failSession(id, if (peakLevel < 0.08f) "Mic seems silent — is another app using it?" else "Didn't catch that"); return }

        val mode = sessionMode
        val ctx = sessionCtx
        val appLabel = appName(sessionPkg)
        val duration = SystemClock.elapsedRealtime() - sessionStartedAt
        // Raw text is stored before any rewrite, so a crash or a bad endpoint can only cost the polish.
        val entry = history.add(sessionPkg, mode.name, prefs.engine.short, duration, raw)

        val tr = transformerFor(mode)
        transformExec.execute {
            val polished = runCatching { tr.transform(raw, mode.prompt, ctx, prefs.removeFillers) { id != sessionId } }
                .getOrDefault(raw)
            main.post { completeDelivery(id, entry, polished, mode, ctx, appLabel) }
        }
    }

    private fun completeDelivery(
        id: Int, entry: HistoryStore.Entry?, polished: String, mode: Mode, ctx: CursorContext, appLabel: String,
    ) {
        // A stale session still lands in history, it just must not touch the new editor.
        val text0 = if (polished.isBlank()) entry?.raw.orEmpty() else polished
        if (id != sessionId) { entry?.let { history.update(it, text0, HistoryStore.Entry.Status.DONE) }; return }
        topBar.transcript = text0

        val plan = DictationPolish.plan(ctx, suggest::isKnown, DictationPolish.firstWord(text0))
        val gated = if (prefs.smartSpacing) plan else plan.copy(leadingSpace = false)
        val finalText = DictationPolish.apply(text0, gated, ctx, suggest::isKnown)

        val sameEditor = currentInputEditorInfo?.packageName == sessionPkg &&
            (currentInputEditorInfo?.fieldId ?: 0) == sessionFieldId
        // The field changed under the dictation, or "history only" with history switched off:
        // copy rather than insert into the wrong place or drop the text.
        val action = when {
            !sameEditor -> DeliveryAction.COPY
            mode.action == DeliveryAction.HISTORY_ONLY && !history.enabled -> DeliveryAction.COPY
            else -> mode.action
        }
        val result = Delivery.perform(this, currentInputConnection, finalText, action, currentInputEditorInfo, sensitive)

        when (result) {
            Delivery.Result.SENT, Delivery.Result.INSERTED -> {
                entry?.let { history.update(it, finalText, HistoryStore.Entry.Status.DONE) }
                voice.insertedIn = appLabel
                voice.hint = null
                prefs.practiced = true
                voice.state = VoicePanel.State.INSERTED
                topBar.status = "Inserted in $appLabel"
                val ends = finalText.trimEnd().lastOrNull()?.let { it == '.' || it == '!' || it == '?' } == true
                if (ends && prefs.autoCap && this::keyboard.isInitialized && keyboard.shift != ShiftState.LOCKED) keyboard.shift = ShiftState.ON
                else updateShift()
            }
            Delivery.Result.COPIED -> {
                entry?.let { history.update(it, finalText, HistoryStore.Entry.Status.COPIED) }
                voice.insertedIn = "clipboard"
                voice.state = VoicePanel.State.INSERTED
                // Status line and screen reader only: a keyboard draws nothing outside its own window.
                topBar.status = if (!sameEditor) "Copied to clipboard — the field changed" else "Copied to clipboard"
                say(topBar.status)
            }
            Delivery.Result.HISTORY_ONLY -> {
                entry?.let { history.update(it, finalText, HistoryStore.Entry.Status.DONE) }
                voice.state = VoicePanel.State.IDLE
                topBar.status = "Saved to history"
            }
            Delivery.Result.FAILED -> {
                entry?.let { history.update(it, finalText, HistoryStore.Entry.Status.FAILED) }
                failSession(id, "Couldn't insert the text")
                return
            }
        }
        resetRunnable?.let { main.removeCallbacks(it) }
        resetRunnable = Runnable {
            if (id == sessionId && voice.state == VoicePanel.State.INSERTED) { voice.state = VoicePanel.State.IDLE; voice.insertedIn = "" }
        }.also { main.postDelayed(it, 1_100) }
    }

    override fun onModeTap() {
        val modes = modeStore.all()
        val current = modeStore.resolve(currentInputEditorInfo?.packageName, prefs.modeOverride).id
        val next = modes[(modes.indexOfFirst { it.id == current } + 1) % modes.size]
        prefs.modeOverride = if (next.fallback) null else next.id
        voice.modeLabel = if (next.fallback) "" else next.name
        say("Mode, ${next.name}")
        voice.hint = null
    }

    override fun onEngineTap() {
        val all = Engine.entries
        val cur = prefs.engine
        var next = cur
        for (i in 1..all.size) {
            val cand = all[(all.indexOf(cur) + i) % all.size]
            if (Engines.create(this, cand).notReadyReason() == null) { next = cand; break }
        }
        if (next == cur) {
            // No engine ready at all: keep the setup pill (and its highlight) that the status line points at.
            val alert = voice.hintAlert
            voice.hint = if (voice.talkEnabled) "Add API keys / download the model in Reev settings" else setupPill()
            voice.hintTappable = true; voice.hintAlert = alert
        } else {
            prefs.engine = next
            voice.engineLabel = next.short
            if (!voice.talkEnabled) { voice.talkEnabled = true; topBar.transcript = ""; topBar.status = "Hold the mic and speak" }
            say("Speech engine, ${next.short}")
            voice.hint = null
            Engines.warm(this, next)
        }
    }

    /**
     * Engine not ready: nothing is recorded. The answer stays on screen: the hint pill stays highlighted,
     * the talk button is dimmed and the status line says what to do. Also spoken for screen readers.
     */
    private fun notReady(reason: String) {
        val pill = setupPill()
        if (voice.hint != pill) voice.hint = pill      // the setter announces; don't repeat it on every press
        voice.hintTappable = true; voice.hintAlert = true; voice.talkEnabled = false
        voice.state = VoicePanel.State.IDLE
        topBar.transcript = ""; topBar.transcriptPending = false
        topBar.status = "Dictation is off — tap “${setupAction()}” below"
        say("Dictation is off. $reason. Activate ${setupAction()} to open Reev settings")
    }

    /** What the setup pill does, in the words the status line repeats. */
    private fun setupAction() = if (prefs.engine == Engine.LOCAL) "Set up dictation" else "Add an API key"

    /** Pill label: the action plus its cost (download size, computed as in SettingsActivity). VoicePanel appends the "›". */
    private fun setupPill(): String {
        if (prefs.engine != Engine.LOCAL) return setupAction()
        val mb = com.asis.reev.stt.LocalModel.from(prefs.localModel).files.sumOf { it.size } / 1_000_000
        return "${setupAction()} · $mb MB download"
    }

    override fun onHintTap() {
        // CLEAR_TOP so the extra reaches the activity even if Settings is already open in its task.
        startActivity(Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(SettingsActivity.EXTRA_JUMP_ENGINE, true))
    }

    override fun onDestroy() {
        cancelVoice()
        prefs.sp.unregisterOnSharedPreferenceChangeListener(themeListener)
        suggest.close()
        glideExec.shutdownNow()
        transformExec.shutdownNow()
        super.onDestroy()
    }
}
