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
import com.asis.reev.ui.dpi

class ReevService : InputMethodService(), KeyboardView.Listener, TopBar.Listener,
    EmojiView.Listener, VoicePanel.Listener {

    private lateinit var prefs: Prefs
    private lateinit var suggest: Suggest
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
        topBar = TopBar(this, pal).also { it.listener = this }
        keyboard = KeyboardView(this, pal).also { it.listener = this; it.overlay = overlay }
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
        // Incognito / sensitive fields: never learn words from them.
        noLearn = noSuggest || (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        keyboard.enterAction = if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
            (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) 0
        else if (action in 2..6) action else 0
        keyboard.showSwitchKey = offerSwitch()
        if (!restarting) {
            keyboard.mode = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME) KbMode.SYMBOLS else KbMode.LETTERS
            if (keyboard.shift == ShiftState.LOCKED) keyboard.shift = ShiftState.OFF
            showKeys()
        }
        lastCorrection = null; autoSpaced = false
        clearGlide()
        updateShift(); updateSuggestions()
    }

    /** Whether to show the globe key: true when the user has another keyboard to switch to. */
    private fun offerSwitch(): Boolean = if (Build.VERSION.SDK_INT >= 28) shouldOfferSwitchingToNextInputMethod() else {
        val token = window?.window?.attributes?.token
        @Suppress("DEPRECATION")
        token != null && getSystemService(android.view.inputmethod.InputMethodManager::class.java).shouldOfferSwitchingToNextInputMethod(token)
    }

    private fun clearGlide() { glideWord = null; glideAlts = emptyList(); afterGlide = false }

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

    private fun showEmoji() {
        val e = emoji ?: EmojiView(this, Palette.of(this)) { prefs.recentEmoji.split(' ').filter { it.isNotEmpty() } }
            .also { it.listener = this; it.hapticsOn = prefs.haptics; emoji = it }
        e.rebuild()
        content.removeAllViews(); content.addView(e)
        topBar.voiceMode = false
    }

    private fun showVoice() {
        content.removeAllViews(); content.addView(voice)
        topBar.voiceMode = true
        topBar.transcript = ""; topBar.status = "Hold the mic and speak"
        voice.state = VoicePanel.State.IDLE
        voice.engineLabel = prefs.engine.short
        val reason = Engines.create(this, prefs.engine).notReadyReason()
        voice.hint = reason
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
        if (w.any { it.isDigit() }) { topBar.suggestions = emptyList(); return }
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
        glideExec.execute {
            if (centers != null) {
                if (!suggest.ready || centers.isEmpty()) return@execute
                glideDecoder = com.asis.reev.glide.GlideDecoder(centers, suggest.glideVocab())
                glideLayoutKey = layoutKey
            }
            val t0 = System.currentTimeMillis()
            val cands = glideDecoder?.decode(cx, cy, n, 5) ?: emptyList()
            android.util.Log.d("ReevGlide", "decoded ${n} pts in ${System.currentTimeMillis() - t0} ms -> $cands")
            main.post { commitGlide(cands, shift) }
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

    override fun onMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, PermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        showVoice()
    }

    override fun onSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        val w = wordBeforeCursor()
        val gw = glideWord
        if (gw != null && w == gw) {
            // Swap the glided word for the alternative, keeping the glide state.
            ic.beginBatchEdit(); ic.deleteSurroundingText(gw.length, 0); ic.commitText(word, 1); ic.endBatchEdit()
            glideWord = word; afterGlide = true
            if (!noLearn) suggest.learn(word)
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

    override fun onBackToKeys() { cancelVoice(); showKeys() }

    // ---------------- EmojiView.Listener ----------------
    override fun onEmoji(e: String) {
        commit(e)
        val rec = (listOf(e) + prefs.recentEmoji.split(' ').filter { it.isNotEmpty() && it != e }).take(32)
        prefs.recentEmoji = rec.joinToString(" ")
        autoSpaced = false; lastCorrection = null
    }

    override fun onAbc() = showKeys()
    override fun onSpace() { commit(" ") }

    // ---------------- VoicePanel.Listener ----------------
    override fun onTalkStart() {
        if (session != null) return
        val engine = Engines.create(this, prefs.engine)
        engine.notReadyReason()?.let { voice.hint = it; voice.state = VoicePanel.State.IDLE; return }
        voice.hint = null
        val id = ++sessionId
        topBar.transcript = ""; topBar.status = "Listening…"; topBar.transcriptPending = true
        val s = engine.start(object : SttCallback {
            override fun onPartial(text: String) = main.post { if (id == sessionId) topBar.transcript = text }.let {}
            override fun onFinal(text: String) = main.post { if (id == sessionId) deliverFinal(text) }.let {}
            override fun onError(msg: String) = main.post {
                if (id == sessionId) {
                    stopMic(); session = null
                    voice.state = VoicePanel.State.IDLE; voice.hint = msg.take(80)
                    topBar.transcriptPending = false; topBar.status = "Error"
                }
            }.let {}
        })
        session = s
        peakLevel = 0f
        val rec = MicRecorder(onChunk = { s.feed(it) }, onLevel = { lv -> if (lv > peakLevel) peakLevel = lv; main.post { voice.pushLevel(lv) } })
        if (!rec.start()) {
            s.cancel(); session = null
            voice.hint = "Microphone unavailable"; voice.state = VoicePanel.State.IDLE
            return
        }
        recorder = rec
        voice.state = VoicePanel.State.RECORDING
    }

    override fun onTalkStop() {
        val s = session ?: run { voice.state = VoicePanel.State.IDLE; return }
        stopMic()
        voice.state = VoicePanel.State.PROCESSING
        topBar.status = "Transcribing…"
        val id = sessionId
        s.finish()
        finalTimeout = Runnable {
            if (id == sessionId && session != null) {
                s.cancel(); session = null
                voice.state = VoicePanel.State.IDLE; voice.hint = "Timed out waiting for transcript"
            }
        }.also { main.postDelayed(it, 20_000) }
    }

    override fun onTalkCancel() {
        cancelVoice()
        topBar.transcript = ""; topBar.status = "Cancelled"
    }

    private fun stopMic() { recorder?.stop(); recorder = null }

    private fun cancelVoice() {
        stopMic()
        session?.cancel(); session = null
        sessionId++
        finalTimeout?.let { main.removeCallbacks(it) }
        if (this::voice.isInitialized) voice.state = VoicePanel.State.IDLE
    }

    private fun deliverFinal(raw: String) {
        finalTimeout?.let { main.removeCallbacks(it) }
        session = null
        voice.state = VoicePanel.State.IDLE
        topBar.transcriptPending = false
        val text = com.asis.reev.stt.TextCleanup.clean(raw, prefs.removeFillers)
        if (text.isEmpty()) {
            topBar.transcript = ""
            topBar.status = if (peakLevel < 0.08f) "Mic seems silent — is another app using it?" else "Didn't catch that"
            return
        }
        topBar.transcript = text
        insertDictation(text)
    }

    private fun insertDictation(text: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
        var t = text
        val sentenceStart = before.isEmpty() || before.trimEnd().let { it.isEmpty() || it.last() in ".!?\n" }
        if (sentenceStart) t = t.replaceFirstChar { it.uppercase() }
        else if (t.length > 1 && t[0].isUpperCase() && t[1].isLowerCase() && !t.startsWith("I ")) {
            // keep provider casing for proper nouns; just leave as-is
        }
        if (before.isNotEmpty() && !before.last().isWhitespace()) t = " $t"
        ic.commitText(t, 1)
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
            voice.hint = "Add API keys / download the model in Reev settings"
        } else {
            prefs.engine = next
            voice.engineLabel = next.short
            voice.hint = null
            Engines.warm(this, next)
        }
    }

    override fun onDestroy() {
        cancelVoice()
        super.onDestroy()
    }
}
