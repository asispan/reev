package com.asis.reev

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * On-device check that the keyboard is usable through the accessibility API, the way a screen reader
 * uses it: it reads the keyboard window's node tree and activates keys with accessibility actions.
 * It never injects touches. Needs no test libraries.
 *
 * It does not prove TalkBack's own gestures (explore by touch, lift to type): those need a finger,
 * because injected touches bypass the screen reader.
 *
 *   ./gradlew assembleDebugAndroidTest      (sign the test APK with the same key as the installed app)
 *   adb shell am instrument -w com.asis.reev.test/com.asis.reev.A11yProbe
 *
 * Opens the settings screen's "Try it" field, types into it and clears it again. The mic is opened
 * for about a second and the audio discarded, and only when the on-device engine is selected, so
 * nothing leaves the phone.
 */
class A11yProbe : Instrumentation() {
    private lateinit var ua: UiAutomation
    private val log = StringBuilder()
    private var failures = 0

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        try { run() } catch (t: Throwable) { failures++; log.append("CRASH ").append(t).append('\n') }
        val out = Bundle()
        out.putString(REPORT_KEY_STREAMRESULT, "\n$log\n${if (failures == 0) "PROBE OK" else "PROBE FAILED ($failures)"}\n")
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, out)
    }

    private fun check(what: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        log.append(if (ok) "ok   " else "FAIL ").append(what)
        if (detail.isNotEmpty()) log.append("  [").append(detail).append(']')
        log.append('\n')
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null) return
        out += n
        for (i in 0 until n.childCount) walk(n.getChild(i), out)
    }

    private fun nodes(type: Int): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        for (w in ua.windows) if (w.type == type) walk(w.root, out)
        return out
    }

    private fun ime() = nodes(AccessibilityWindowInfo.TYPE_INPUT_METHOD)
    private fun names() = ime().mapNotNull { it.contentDescription?.toString() }.filter { it.isNotEmpty() }
    private fun key(name: String) = ime().firstOrNull { it.contentDescription?.toString() == name }
    private fun field() = nodes(AccessibilityWindowInfo.TYPE_APPLICATION).firstOrNull { it.className == "android.widget.EditText" }
    private fun typed(): String = field()?.let { if (it.isShowingHintText) "" else it.text?.toString() } ?: "<no field>"
    private fun settle(ms: Long = 450) = SystemClock.sleep(ms)

    private fun waitFor(what: String, timeout: Long = 6000, cond: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (cond()) return true; SystemClock.sleep(200) }
        check("wait: $what", false); return false
    }

    /** Activate a key the way a screen reader does. */
    private fun click(name: String): Boolean {
        val n = key(name)
        val ok = n != null && n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!ok) check("activate '$name'", false, if (n == null) "no such node" else "action refused")
        settle(); return ok
    }

    private fun expectText(what: String, want: String) { val got = typed(); check(what, got == want, "field=\"$got\"") }

    private fun run() {
        ua = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ua.serviceInfo = ua.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }

        startActivitySync(Intent(targetContext, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (!waitFor("settings field") { field() != null }) return
        val f = field()!!
        f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") })
        f.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!waitFor("keyboard window with named keys") { names().size > 30 }) return

        // 1. Everything on the keyboard is exposed and named.
        val all = ime()
        val keys = all.filter { it.isTextEntryKey }
        check("keys exposed as text-entry keys", keys.size >= 33, "${keys.size} keys")
        check("every key has a name", keys.all { !it.contentDescription.isNullOrEmpty() })
        check("key names are unique", keys.map { it.contentDescription.toString() }.let { it.size == it.toSet().size })
        check("keys have real bounds", keys.all { r -> android.graphics.Rect().also { r.getBoundsInScreen(it) }.let { it.width() > 20 && it.height() > 20 } })
        for (n in listOf("Voice typing", "Reev settings", "Space", "Delete", "Enter", "Emoji", "Symbols", "Comma", "Period"))
            check("has '$n'", key(n) != null)
        check("mic is a button, not a key", key("Voice typing")?.let { it.isClickable && !it.isTextEntryKey } == true)

        // 2. Typing by accessibility action. The field starts a sentence, so shift is on.
        check("shift announced as on at sentence start", key("Shift on") != null && key("Capital H") != null)
        click("Capital H"); expectText("capital letter typed", "H")
        check("shift released after one letter", key("Shift") != null && key("h") != null)
        click("i"); expectText("letter typed", "Hi")
        click("Space"); expectText("space typed", "Hi ")
        click("Shift"); check("shift toggles on", key("Shift on") != null && key("Capital A") != null)
        click("Shift on"); check("shift toggles to caps lock", key("Caps lock on") != null)
        click("Caps lock on"); check("shift toggles off", key("Shift") != null && key("a") != null)

        // 3. Long-press alternatives are reachable as actions on the key.
        val e = key("e")
        val alt = e?.actionList?.firstOrNull { it.label?.toString() == "Type é" }
        check("accent offered as an action on e", alt != null, e?.actionList?.mapNotNull { it.label }?.joinToString() ?: "")
        if (alt != null) { e.performAction(alt.id); settle(); expectText("accent typed via action", "Hi é") }

        // 4. Symbol layouts.
        click("Symbols"); check("symbols layout named", key("At sign") != null && key("Letters") != null && key("More symbols") != null)
        click("At sign"); expectText("symbol typed", "Hi é@")
        click("More symbols"); check("more-symbols layout named", key("Tilde") != null && key("Symbols") != null)
        click("Letters"); check("back to letters", key("q") != null)
        click("Delete"); click("Delete"); expectText("delete works", "Hi ")

        // 5. Emoji panel.
        click("Emoji")
        check("emoji tabs named", waitFor("emoji panel") { key("Recent emoji") != null }, names().filter { it.endsWith(" emoji") }.joinToString())
        check("emoji row keys named", key("Letters") != null && key("Space") != null && key("Delete") != null)
        check("one emoji tab is selected", ime().count { it.isSelected } == 1)
        val cell = ime().firstOrNull { it.className == "android.widget.TextView" && it.isClickable && !it.text.isNullOrEmpty() }
        check("emoji cells are readable and clickable", cell != null)
        val before = typed()
        if (cell != null) { cell.performAction(AccessibilityNodeInfo.ACTION_CLICK); settle(); check("emoji typed", typed().length > before.length) }
        click("Letters"); check("emoji panel closes", waitFor("letters after emoji") { key("q") != null || key("Capital Q") != null })

        // 6. Voice panel: the talk button must toggle, because hold-to-talk can't work under a screen reader.
        click("Voice typing")
        if (waitFor("voice panel") { key("Start dictation") != null || key("Stop and insert") != null }) {
            for (n in listOf("Back to keyboard", "Space", "Delete", "Enter")) check("voice panel has '$n'", key(n) != null)
            val engine = names().firstOrNull { it.startsWith("Speech engine") } ?: ""
            check("engine chip named", engine.isNotEmpty(), engine)
            if (key("Stop and insert") != null) click("Cancel dictation")   // auto-start was on
            if (engine.endsWith(Engine.LOCAL.short) && key("Start dictation") != null) {
                click("Start dictation")
                val started = key("Stop and insert") != null
                check("one activation starts and locks dictation", started, names().joinToString())
                if (started) {
                    check("cancel offered while dictating", key("Cancel dictation") != null)
                    val t0 = typed()
                    click("Cancel dictation")
                    check("cancel returns to idle", waitFor("idle after cancel") { key("Start dictation") != null })
                    check("cancel inserts nothing", typed() == t0)
                }
            } else log.append("skip dictation toggle (engine: $engine)\n")
            click("Back to keyboard"); check("voice panel closes", waitFor("letters after voice") { key("Voice typing") != null })
        }

        field()?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") })
    }
}
