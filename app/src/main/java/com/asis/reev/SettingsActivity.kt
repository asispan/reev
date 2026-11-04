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
import com.asis.reev.stt.Engines
import com.asis.reev.stt.LocalModel
import com.asis.reev.stt.LocalParakeet
import com.asis.reev.stt.ModelManager
import com.asis.reev.ui.dpi

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    private class ModelViews(val bar: ProgressBar, val status: TextView, val btn: Button)
    private val modelViews = HashMap<LocalModel, ModelViews>()

    /** Downloads run in ModelManager (app scope); this screen only observes, so rotation or leaving can't leak it. */
    private val downloadListener = object : ModelManager.Listener {
        override fun onProgress(m: LocalModel, done: Long, total: Long) = main.post {
            modelViews[m]?.let { v ->
                v.bar.visibility = View.VISIBLE; v.btn.text = "Cancel"
                v.bar.progress = (done * 1000 / total).toInt()
                v.status.text = "%.0f / %.0f MB".format(done / 1e6, total / 1e6)
            }
        }.let {}
        override fun onDone(m: LocalModel, error: String?) = main.post {
            if (error == null) { prefs.localModel = m.id; prefs.engine = Engine.LOCAL; Engines.warm(this@SettingsActivity, Engine.LOCAL); render() }
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
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        prefs = Prefs(this)
    }

    override fun onResume() { super.onResume(); ModelManager.addListener(downloadListener); render() }
    override fun onPause() { ModelManager.removeListener(downloadListener); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) refreshSetup() }

    private var setupBox: LinearLayout? = null

    private fun render() {
        val scroll = ScrollView(this)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(18), dpi(28), dpi(18), dpi(40)) }
        scroll.addView(col)
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom); insets
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
        val rg = RadioGroup(this)
        Engine.entries.forEach { e ->
            val rb = RadioButton(this).apply {
                text = e.title; setTextColor(fg); textSize = 15f; id = View.generateViewId(); tag = e
                buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            }
            rg.addView(rb)
            if (prefs.engine == e) rb.isChecked = true
        }
        rg.setOnCheckedChangeListener { g, id -> (g.findViewById<View>(id).tag as Engine).let { prefs.engine = it; Engines.warm(this, it) } }
        eng.addView(rg)
        eng.addView(text("Tip: tap the engine chip in the voice panel to switch on the fly.", 12.5f, sub))

        // ---- local model
        val loc = section("On-device model (NVIDIA Parakeet)")
        loc.addView(text("Runs fully offline on the phone via sherpa-onnx. Needs a one-time download over Wi-Fi.", 13f, sub))
        val mg = RadioGroup(this)
        LocalModel.entries.forEach { m -> modelRow(loc, mg, m) }

        // ---- api keys
        val keys = section("Cloud API keys")
        keys.addView(text("Stored only on this phone (app-private storage). Audio is streamed to the provider you pick.", 13f, sub))
        listOf(Engine.ELEVENLABS, Engine.SARVAM, Engine.SMALLEST, Engine.CARTESIA).forEach { e -> keyField(keys, e) }

        // ---- typing
        val typ = section("Keyboard")
        typ.addView(text("Theme", 13.5f, fg, bold = true).also { it.setPadding(0, dpi(4), 0, dpi(2)) })
        val tg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("system" to "Follow system", "light" to "Light", "dark" to "Dark").forEach { (id, label) ->
            tg.addView(RadioButton(this).apply {
                text = label; setTextColor(fg); textSize = 14.5f; this.id = View.generateViewId(); tag = id
                buttonTintList = android.content.res.ColorStateList.valueOf(accent)
                isChecked = prefs.theme == id
                setPadding(0, 0, dpi(12), 0)
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
        step(box, "2. Switch to Reev", current, "Switch") { imm.showInputMethodPicker() }
        step(box, "3. Allow microphone", mic, "Allow") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); refreshSetup()
    }

    private fun step(box: LinearLayout, label: String, done: Boolean, action: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dpi(6), 0, dpi(6)) }
        row.addView(text(label, 15f, fg), LinearLayout.LayoutParams(0, -2, 1f))
        if (done) row.addView(text("✓ Done", 14f, Color.parseColor("#5BD68A"), bold = true))
        else row.addView(button(action, onClick))
        box.addView(row)
    }

    private fun modelRow(parent: LinearLayout, group: RadioGroup, m: LocalModel) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(10), 0, dpi(6)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val rb = RadioButton(this).apply {
            text = m.title; setTextColor(fg); textSize = 15f
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            isChecked = prefs.localModel == m.id
            setOnClickListener {
                prefs.localModel = m.id; LocalParakeet.release(); render()
            }
        }
        top.addView(rb, LinearLayout.LayoutParams(0, -2, 1f))
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
            bar.visibility = View.VISIBLE; btn.text = "Cancel"
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
            setText(prefs.apiKey(e)); hint = "API key (clear the field to delete it)"; setTextColor(fg); setHintTextColor(sub); textSize = 14f
            isSingleLine = true
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
            text = label; isChecked = v; setTextColor(fg); textSize = 15f; setPadding(0, dpi(8), 0, dpi(8))
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

    private fun spaced() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10) }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f
        background = rounded(accent, 20); setPadding(dpi(16), 0, dpi(16), 0); minHeight = dpi(38); minimumHeight = dpi(38)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun rounded(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dpi(r).toFloat() }
}
