package com.asis.reev

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.asis.reev.mode.DeliveryAction
import com.asis.reev.mode.Mode
import com.asis.reev.stt.Engines
import com.asis.reev.stt.LocalModel
import com.asis.reev.stt.LocalParakeet
import com.asis.reev.stt.ModelManager
import com.asis.reev.ui.dpi

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var modes: com.asis.reev.mode.ModeStore
    private val main = Handler(Looper.getMainLooper())

    private class ModelViews(val bar: ProgressBar, val status: TextView, val btn: Button)
    private val modelViews = HashMap<LocalModel, ModelViews>()
    private var visible = false

    /** Downloads run in ModelManager (app scope); this screen only observes, so rotation or leaving can't leak it. */
    private val downloadListener = object : ModelManager.Listener {
        override fun onProgress(m: LocalModel, done: Long, total: Long) = main.post {
            if (!visible) return@post
            modelViews[m]?.let { v ->
                v.bar.visibility = View.VISIBLE; v.btn.text = "Cancel"
                v.bar.progress = (done * 1000 / total).toInt()
                v.status.text = "%.0f / %.0f MB".format(done / 1e6, total / 1e6)
            }
        }.let {}
        override fun onDone(m: LocalModel, error: String?) = main.post {
            if (!visible) return@post
            if (error == null) render()   // ModelManager already selected and warmed the model
            else modelViews[m]?.let { v -> v.status.text = "Download failed: $error"; v.btn.text = "Retry"; v.bar.visibility = View.GONE }
        }.let {}
    }
    private lateinit var col: LinearLayout
    private val fg = Color.parseColor("#F3F3F6")
    private val sub = Color.parseColor("#9A9CA6")
    private val accent = Color.parseColor("#E2336B")
    private val card = Color.parseColor("#1B1C20")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // API keys are on this screen: keep it out of screenshots, screen recordings and the recents preview.
        // Debug builds skip it so QA runs and bug reports can capture the screen; release builds always set it.
        if (!BuildConfig.DEBUG) window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        prefs = Prefs(this)
        modes = com.asis.reev.mode.ModeStore(this)
    }

    override fun onResume() {
        super.onResume(); visible = true; ModelManager.addListener(downloadListener); render()
        if (intent?.getBooleanExtra(EXTRA_JUMP_ENGINE, false) == true) { intent.removeExtra(EXTRA_JUMP_ENGINE); jumpToEngine() }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent) }
    override fun onPause() { visible = false; ModelManager.removeListener(downloadListener); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) refreshSetup() }

    private var setupBox: LinearLayout? = null
    private var scroller: ScrollView? = null
    private var modelBox: View? = null
    private var keysBox: View? = null

    companion object { const val EXTRA_JUMP_ENGINE = "jump_engine" }

    /** Scrolls to where a dictation engine is obtained: the model download, or the API keys in cloud mode. */
    private fun jumpToEngine() {
        val sv = scroller ?: return
        val target = (if (prefs.engine == Engine.LOCAL) modelBox else keysBox) ?: return
        sv.post { sv.smoothScrollTo(0, (target.top - dpi(48)).coerceAtLeast(0)) }
    }

    /** Scrolls the focused field into the part of the page that the keyboard ([bottomInset]) leaves visible. */
    private fun revealFocused(sv: ScrollView, bottomInset: Int) {
        val f = sv.findFocus() ?: return
        if (f === sv) return
        val r = android.graphics.Rect(0, 0, f.width, f.height)
        sv.offsetDescendantRectToMyCoords(f, r)
        val top = sv.scrollY + sv.paddingTop
        val bottom = sv.scrollY + sv.height - bottomInset
        val dy = if (r.bottom > bottom) r.bottom - bottom else if (r.top < top) r.top - top else 0
        if (dy != 0) sv.scrollBy(0, dy)
    }

    private fun render() {
        // Reev's screens are English-only: keep them left-to-right under RTL locales instead of half-mirroring.
        val scroll = ScrollView(this).also { scroller = it; it.layoutDirection = View.LAYOUT_DIRECTION_LTR; it.textDirection = View.TEXT_DIRECTION_LTR }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(18), dpi(28), dpi(18), dpi(40)) }
        scroll.addView(col)
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = if (android.os.Build.VERSION.SDK_INT >= 30) {
                // Bars + keyboard, asked for explicitly (edge-to-edge is forced on 35+).
                insets.getInsets(android.view.WindowInsets.Type.systemBars()).top to
                    insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime()).bottom
            } else @Suppress("DEPRECATION") (insets.systemWindowInsetTop to insets.systemWindowInsetBottom)
            v.setPadding(0, top, 0, 0)
            // Bottom inset as extra padding on the content, not a margin on the ScrollView: the ScrollView always
            // fills the window, so a late or stale keyboard inset after a rotation can only add scroll room, never
            // cut the page short. Its size no longer changes with the keyboard, so reveal the focused field by hand.
            val content = scroll.getChildAt(0)
            if (content != null && content.paddingBottom != dpi(40) + bottom) {
                content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, dpi(40) + bottom)
                v.post { revealFocused(scroll, bottom) }
            }
            insets
        }

        col.addView(text("Reev", 30f, fg, bold = true))
        col.addView(text("English + emoji keyboard with push-to-talk dictation.", 14f, sub).also { it.setPadding(0, dpi(4), 0, dpi(18)) })

        // ---- test
        val test = section("Try it")
        test.addView(EditText(this).apply {
            hint = "Tap here, then tap the mic"; setTextColor(fg); setHintTextColor(sub); minLines = 3
            gravity = Gravity.TOP; background = rounded(Color.parseColor("#26282E"), 12)
            setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        })
        // ---- setup
        setupBox = section("Setup")
        refreshSetup()

        // ---- engine
        val eng = section("Dictation engine")
        // Manual switch: on-device model or cloud API keys. It only changes which engine dictation uses;
        // the downloaded model and the saved keys both stay on the phone.
        val segLocal = segment("On-device model")
        val segCloud = segment("API keys (cloud)")
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = rounded(Color.parseColor("#26282E"), 12)
            setPadding(dpi(3), 0, dpi(3), 0); isBaselineAligned = false
            // The row wraps its content; MATCH_PARENT children all take the height of the tallest one, so the pill fills it.
            addView(segLocal, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)); addView(segCloud, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        val providers = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(10), 0, 0) }
        providers.addView(text("Provider", 13.5f, fg, bold = true))
        val rg = RadioGroup(this)
        val cloud = prefs.cloudEngine
        Engine.entries.filter { it != Engine.LOCAL }.forEach { e ->
            val rb = RadioButton(this).apply {
                text = e.title; setTextColor(fg); textSize = 15f; id = View.generateViewId(); tag = e
                buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            }
            rg.addView(rb)
            if (cloud == e) rb.isChecked = true
        }
        rg.setOnCheckedChangeListener { g, id -> (g.findViewById<View>(id).tag as Engine).let { prefs.engine = it; Engines.warm(this, it) } }
        providers.addView(rg)
        val modeNote = text("", 12.5f, sub).also { it.setPadding(0, dpi(10), 0, 0) }
        fun showMode() {
            val local = prefs.engine == Engine.LOCAL
            segLocal.background = if (local) segPill() else null
            segCloud.background = if (local) null else segPill()
            // Setting an InsetDrawable background replaces the view's padding with its insets: set ours afterwards.
            segLocal.setPadding(dpi(6), dpi(8), dpi(6), dpi(8)); segCloud.setPadding(dpi(6), dpi(8), dpi(6), dpi(8))
            segLocal.setTextColor(if (local) Color.WHITE else sub); segCloud.setTextColor(if (local) sub else Color.WHITE)
            providers.visibility = if (local) View.GONE else View.VISIBLE
            segLocal.isSelected = local; segCloud.isSelected = !local   // announced by screen readers
            modeNote.text = if (local) "Dictation runs offline on this phone. Your API keys stay saved."
                else "Audio is streamed to the provider above. The downloaded model stays on the phone; switch back any time."
        }
        segLocal.setOnClickListener { prefs.engine = Engine.LOCAL; Engines.warm(this, Engine.LOCAL); showMode() }
        segCloud.setOnClickListener { prefs.cloudEngine.let { prefs.engine = it; Engines.warm(this, it) }; showMode() }
        showMode()
        eng.addView(seg); eng.addView(providers); eng.addView(modeNote)
        eng.addView(text("Tip: tap the engine chip in the voice panel to switch on the fly.", 12.5f, sub).also { it.setPadding(0, dpi(4), 0, 0) })

        // ---- local model
        val loc = section("On-device model (NVIDIA Parakeet)")
        modelBox = loc
        loc.addView(text("Runs fully offline on the phone via sherpa-onnx. Needs a one-time download over Wi-Fi.", 13f, sub))
        val mg = RadioGroup(this)
        LocalModel.entries.forEach { m -> modelRow(loc, mg, m) }

        // ---- api keys
        val keys = section("Cloud API keys")
        keysBox = keys
        keys.addView(text("Stored only on this phone (app-private storage). Audio is streamed to the provider you pick. Clear a field to delete its key.", 13f, sub))
        listOf(Engine.ELEVENLABS, Engine.SARVAM, Engine.SMALLEST, Engine.CARTESIA).forEach { e -> keyField(keys, e) }

        // ---- modes
        val md = section("Dictation modes")
        md.addView(text("A mode decides how a transcript is shaped and delivered. It picks itself by app; " +
            "long-press the engine chip in the voice panel to cycle modes by hand.", 13f, sub))
        modes.all().forEach { m ->
            md.addView(modeRow(m))
        }
        md.addView(button("Add mode") { editMode(null) }, spaced())

        // ---- ai
        val ai = section("AI rewrite (optional)")
        ai.addView(text("Modes with instructions send the transcript to an OpenAI-compatible endpoint. " +
            "Without a key, modes still work — you get local cleanup only.", 13f, sub))
        ai.addView(text("Endpoint", 13.5f, fg, bold = true).also { it.setPadding(0, dpi(12), 0, dpi(4)) })
        ai.addView(field(prefs.aiBase, "https://api.openai.com/v1", false) { prefs.aiBase = it })
        ai.addView(text("Model", 13.5f, fg, bold = true).also { it.setPadding(0, dpi(12), 0, dpi(4)) })
        ai.addView(field(prefs.aiModel, "gpt-4o-mini", false) { prefs.aiModel = it })
        ai.addView(text("API key", 13.5f, fg, bold = true).also { it.setPadding(0, dpi(12), 0, dpi(4)) })
        ai.addView(field(prefs.aiKey, "sk-…", true) { prefs.aiKey = it })
        ai.addView(text("Clear the key field to delete the key. The endpoint sees the transcript and the text before the caret, never the audio.", 12.5f, sub)
            .also { it.setPadding(0, dpi(10), 0, 0) })

        // ---- typing
        val typ = section("Keyboard")
        typ.addView(text("Keyboard theme", 13.5f, fg, bold = true).also { it.setPadding(0, dpi(4), 0, dpi(2)) })
        typ.addView(text("Changes the keyboard only. Reev's own screens stay dark.", 12.5f, sub))
        // Three labels in one row don't fit at large font or on narrow screens: stack them.
        val tg = RadioGroup(this).apply {
            orientation = if (resources.configuration.fontScale >= 1.3f || resources.configuration.screenWidthDp < 380) RadioGroup.VERTICAL else RadioGroup.HORIZONTAL
        }
        listOf("system" to "Follow system", "light" to "Light", "dark" to "Dark").forEach { (id, label) ->
            tg.addView(RadioButton(this).apply {
                text = label; setTextColor(fg); textSize = 14.5f; this.id = View.generateViewId(); tag = id
                buttonTintList = android.content.res.ColorStateList.valueOf(accent)
                isChecked = prefs.theme == id
                setPadding(0, 0, dpi(12), 0); minHeight = dpi(48); gravity = Gravity.CENTER_VERTICAL
            })
        }
        tg.setOnCheckedChangeListener { g, cid -> prefs.theme = g.findViewById<View>(cid).tag as String }
        typ.addView(tg)
        toggle(typ, "Haptic feedback", prefs.haptics) { prefs.haptics = it }
        toggle(typ, "Glide typing (swipe across letters)", prefs.glideTyping) { prefs.glideTyping = it }
        toggle(typ, "Hinglish suggestions (kya, nahi, yaar…)", prefs.hinglish) { prefs.hinglish = it }
        toggle(typ, "Fix previous word from context (well see → we'll see)", prefs.contextFix) { prefs.contextFix = it }
        toggle(typ, "Auto-capitalisation", prefs.autoCap) { prefs.autoCap = it }
        toggle(typ, "Autocorrect on space", prefs.autocorrect) { prefs.autocorrect = it }
        toggle(typ, "Double-space for period", prefs.doubleSpacePeriod) { prefs.doubleSpacePeriod = it }
        toggle(typ, "Remove filler words (um, uh) from dictation", prefs.removeFillers) { prefs.removeFillers = it }
        toggle(typ, "Mic button starts listening immediately", prefs.autoStartVoice) { prefs.autoStartVoice = it }
        toggle(typ, "Add a space when dictating mid-sentence", prefs.smartSpacing) { prefs.smartSpacing = it }
        toggle(typ, "Keep a dictation history", prefs.historyEnabled) { prefs.historyEnabled = it }
        typ.addView(button("Open history") { startActivity(Intent(this, HistoryActivity::class.java)) }, spaced())
        typ.addView(button("Forget learned words") {
            Suggest.forgetAll(this)
            android.app.AlertDialog.Builder(this).setMessage("Learned words deleted from this phone.").setPositiveButton("OK", null).show()
        }, spaced())
        typ.addView(button("Open-source licences") {
            val txt = runCatching { assets.open("licenses.txt").bufferedReader().readText() }.getOrDefault("")
            android.app.AlertDialog.Builder(this).setTitle("Open-source licences").setMessage(txt).setPositiveButton("Close", null).show()
        }, spaced())

        col.addView(text("v${packageManager.getPackageInfo(packageName, 0).versionName}" +
            (if (LocalParakeet.lastLoadMs > 0) " · model load ${LocalParakeet.lastLoadMs} ms" else "") +
            (if (LocalParakeet.lastDecode.isNotEmpty()) " · ${LocalParakeet.lastDecode}" else ""), 11f, sub).also { it.setPadding(0, dpi(16), 0, 0) })
    }

    private fun refreshSetup() {
        val box = setupBox ?: return
        box.removeViews(1, box.childCount - 1)
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(packageName) == true
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        step(box, "1. Enable Reev", enabled, "Enable") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        // Always shown, worded for before and after: removing it once enabled moved "Switch" up under the finger.
        box.addView(text("Android shows up to two warnings for any keyboard: tap OK on each (Back cancels).", 12.5f, sub)
            .also { it.setPadding(0, 0, 0, dpi(6)) })
        step(box, "2. Switch to Reev", current, "Switch") { imm.showInputMethodPicker() }
        step(box, "3. Allow microphone", mic, "Allow", "✓ Allowed") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2) }
        // Same check the voice panel uses, so Setup and the keyboard never disagree.
        val engineReady = Engines.create(this, prefs.engine).notReadyReason() == null
        step(box, "4. Get a dictation engine", engineReady, "Get") { jumpToEngine() }
        if (!engineReady) {
            // Size and Wi-Fi advice up front, so "Get" doesn't surprise anyone with a 600 MB list.
            val mb = LocalModel.from(prefs.localModel).files.sumOf { it.size } / 1_000_000
            val local = if (prefs.engine == Engine.LOCAL) " The on-device model is about $mb MB: use Wi-Fi." else ""
            box.addView(text("Dictation needs the on-device model (a one-time download) or a cloud API key. Typing works without it.$local", 12.5f, sub)
                .also { it.setPadding(0, 0, 0, dpi(6)) })
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); refreshSetup()
    }

    private fun step(box: LinearLayout, label: String, done: Boolean, action: String, doneLabel: String = "✓ Done", onClick: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dpi(1), 0, dpi(1)); minimumHeight = dpi(50) }
        row.addView(text(label, 15f, fg), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dpi(12) })
        if (done) row.addView(text(doneLabel, 14f, Color.parseColor("#5BD68A"), bold = true))
        else row.addView(button(action, onClick))
        box.addView(row)
    }

    private fun modelRow(parent: LinearLayout, group: RadioGroup, m: LocalModel) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(10), 0, dpi(6)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val rb = RadioButton(this).apply {
            text = m.title; setTextColor(fg); textSize = 15f; minHeight = dpi(48); gravity = Gravity.CENTER_VERTICAL
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            isChecked = prefs.localModel == m.id
            setOnClickListener {
                prefs.localModel = m.id; LocalParakeet.release(); render()
            }
        }
        top.addView(rb, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dpi(12) })
        val installed = ModelManager.isInstalled(this, m)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE; progressTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        val status = text(if (installed) "Installed" else m.note, 12.5f, sub)
        val btn = button(if (installed) "Delete" else "Download") {}
        btn.setOnClickListener {
            if (ModelManager.isInstalled(this, m)) {
                LocalParakeet.release(); ModelManager.delete(this, m); render(); return@setOnClickListener
            }
            if (ModelManager.downloading != null) { ModelManager.cancel(); return@setOnClickListener }
            bar.visibility = View.VISIBLE; btn.text = "Cancel"; status.text = "Starting…"
            ModelManager.start(this, m)
        }
        modelViews[m] = ModelViews(bar, status, btn)
        if (ModelManager.downloading == m) { bar.visibility = View.VISIBLE; btn.text = "Cancel" }
        top.addView(btn)
        box.addView(top); box.addView(status); box.addView(bar)
        parent.addView(box)
    }

    private fun keyField(parent: LinearLayout, e: Engine) {
        parent.addView(text(e.title, 13.5f, fg, bold = true).also { it.setPadding(0, dpi(12), 0, dpi(4)) })
        val et = EditText(this).apply {
            setText(prefs.apiKey(e)); hint = "API key"; setTextColor(fg); setHintTextColor(sub); textSize = 14f
            isSingleLine = true
            // After isSingleLine: setSingleLine() calls setLines(1), which overwrites TextView.minHeight.
            // View.minimumHeight is separate and survives it.
            minHeight = dpi(48); minimumHeight = dpi(48)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            background = rounded(Color.parseColor("#26282E"), 10)
            setPadding(dpi(12), dpi(10), dpi(12), dpi(10))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { prefs.setApiKey(e, s.toString()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        parent.addView(et)
    }

    private fun toggle(parent: LinearLayout, label: String, v: Boolean, set: (Boolean) -> Unit) {
        parent.addView(Switch(this).apply {
            text = label; isChecked = v; setTextColor(fg); textSize = 15f; setPadding(0, dpi(8), 0, dpi(8)); minHeight = dpi(48); gravity = Gravity.CENTER_VERTICAL
            switchPadding = dpi(16)   // gap between the label and the switch, on whichever side the switch is
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, Color.GRAY))
            setOnCheckedChangeListener { _, c -> set(c) }
        })
    }

    private fun section(title: String): LinearLayout {
        col.addView(text(title.uppercase(), 12f, accent, bold = true).also { it.letterSpacing = 0.08f; it.setPadding(dpi(4), dpi(18), 0, dpi(8)) })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(card, 16); setPadding(dpi(16), dpi(12), dpi(16), dpi(14))
        }
        box.addView(View(this), LinearLayout.LayoutParams(0, 0))
        col.addView(box)
        return box
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun segment(s: String) = TextView(this).apply {
        text = s; textSize = 14.5f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; isClickable = true
        minHeight = dpi(48)   // grows past 48 dp when the label wraps; padding is set in showMode()
    }

    /** Selected-segment pill, inset 3 dp top and bottom so the segment itself can be 48 dp tall. */
    private fun segPill() = android.graphics.drawable.InsetDrawable(rounded(accent, 10), 0, dpi(3), 0, dpi(3))

    private fun spaced() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(5) }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f
        // 48 dp touch target; the pill is still drawn 38 dp tall (5 dp transparent inset above and below).
        background = android.graphics.drawable.InsetDrawable(rounded(accent, 20), 0, dpi(5), 0, dpi(5))
        setPadding(dpi(16), 0, dpi(16), 0); minHeight = dpi(48); minimumHeight = dpi(48)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    // ---- modes editing

    private fun modeRow(m: Mode): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpi(10), 0, dpi(10))
            isFocusable = true
            contentDescription = "${m.name}. ${if (m.fallback) "fallback mode" else m.action.id}. Tap to edit."
            setOnClickListener { editMode(m) }
        }
        row.addView(text(m.name + if (m.fallback) "  (fallback)" else "", 15f, fg, bold = true))
        val detail = buildString {
            append(
                when (m.action) {
                    DeliveryAction.INSERT -> "Insert the text"
                    DeliveryAction.INSERT_SEND -> "Insert and send"
                    DeliveryAction.COPY -> "Copy to clipboard"
                    DeliveryAction.HISTORY_ONLY -> "History only"
                }
            )
            if (m.apps.isNotEmpty()) append(" · ").append(m.apps.joinToString(", ") { com.asis.reev.history.AppNames.of(it) })
            if (m.prompt.isNotBlank()) append(" · has AI instructions")
        }
        row.addView(text(detail, 12.5f, sub).also { it.setPadding(0, dpi(3), 0, 0) })
        return row
    }

    private fun editMode(existing: Mode?) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(20), dpi(12), dpi(20), 0) }
        body.addView(text("Name", 12.5f, sub))
        val name = EditText(this).apply {
            setText(existing?.name.orEmpty()); setHintTextColor(sub); setTextColor(fg)
            background = rounded(Color.parseColor("#26282E"), 10); setPadding(dpi(12), dpi(8), dpi(12), dpi(8))
        }
        body.addView(name)
        body.addView(text("Instructions for the AI (optional)", 12.5f, sub).also { it.setPadding(0, dpi(10), 0, 0) })
        val prompt = EditText(this).apply {
            setText(existing?.prompt.orEmpty()); minLines = 4; gravity = Gravity.TOP; setHintTextColor(sub); setTextColor(fg)
            background = rounded(Color.parseColor("#26282E"), 10); setPadding(dpi(12), dpi(8), dpi(12), dpi(8))
        }
        body.addView(prompt)
        body.addView(text("After dictation", 12.5f, sub).also { it.setPadding(0, dpi(10), 0, 0) })
        val actions = RadioGroup(this)
        DeliveryAction.entries.forEach { a ->
            actions.addView(RadioButton(this).apply {
                text = when (a) {
                    DeliveryAction.INSERT -> "Insert the text"
                    DeliveryAction.INSERT_SEND -> "Insert and send"
                    DeliveryAction.COPY -> "Copy to clipboard"
                    DeliveryAction.HISTORY_ONLY -> "Save to history only"
                }
                setTextColor(fg); textSize = 14.5f; id = View.generateViewId(); tag = a
                buttonTintList = android.content.res.ColorStateList.valueOf(accent)
                isChecked = existing?.action == a || (existing == null && a == DeliveryAction.INSERT)
            })
        }
        body.addView(actions)
        body.addView(text("Apps (comma-separated package names)", 12.5f, sub).also { it.setPadding(0, dpi(10), 0, 0) })
        val apps = EditText(this).apply {
            setText(existing?.apps.orEmpty().joinToString(", ")); setHintTextColor(sub); setTextColor(fg); isSingleLine = true
            background = rounded(Color.parseColor("#26282E"), 10); setPadding(dpi(12), dpi(8), dpi(12), dpi(8))
        }
        body.addView(apps)

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New mode" else "Edit mode")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        if (existing != null && !existing.fallback) {
            dialog.setButton(android.app.AlertDialog.BUTTON_NEUTRAL, "Delete") { _, _ -> modes.delete(existing.id); render() }
        }
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = name.text.toString().trim().ifEmpty { "Mode" }
                val action = actions.findViewById<View>(actions.checkedRadioButtonId)?.tag as? DeliveryAction ?: DeliveryAction.INSERT
                val pkgList = apps.text.toString().split(',').map { it.trim() }.filter { it.contains('.') }
                modes.upsert(
                    Mode(
                        id = existing?.id ?: "mode-" + System.currentTimeMillis(),
                        name = label, prompt = prompt.text.toString().trim(), action = action,
                        apps = pkgList, fallback = existing?.fallback ?: false,
                    )
                )
                dialog.dismiss(); render()
            }
        }
        dialog.show()
    }

    private fun field(value: String, hint: String, secret: Boolean, onChange: (String) -> Unit): EditText {
        val et = EditText(this).apply {
            setText(value); this.hint = hint; setTextColor(fg); setHintTextColor(sub); textSize = 14f; isSingleLine = true; minHeight = dpi(48); minimumHeight = dpi(48)
            background = rounded(Color.parseColor("#26282E"), 10); setPadding(dpi(12), dpi(10), dpi(12), dpi(10))
            if (secret) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = onChange(s.toString())
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        return et
    }

    private fun rounded(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dpi(r).toFloat() }
}
