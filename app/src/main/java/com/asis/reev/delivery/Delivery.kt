package com.asis.reev.delivery

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.asis.reev.mode.DeliveryAction

/**
 * Puts a finished transcript where the mode says it should go.
 *
 * The mode states intent; the editor decides mechanism. "Send" degrades to a plain insert when the
 * field has no send action, and every path that cannot insert falls back to the clipboard so the text
 * is never lost. Clipboard clips are marked sensitive so the transcript stays out of the system
 * clipboard preview.
 */
object Delivery {

    enum class Result { INSERTED, SENT, COPIED, HISTORY_ONLY, FAILED }

    fun perform(
        ctx: Context,
        ic: InputConnection?,
        text: String,
        action: DeliveryAction,
        info: EditorInfo?,
        sensitive: Boolean,
    ): Result {
        if (text.isEmpty()) return Result.FAILED
        if (action == DeliveryAction.HISTORY_ONLY) return Result.HISTORY_ONLY
        if (action == DeliveryAction.COPY || sensitive) return copy(ctx, text)

        // A live composing region (glide) would be clobbered by a commit: end it first.
        runCatching { ic?.finishComposingText() }
        val committed = runCatching { ic?.commitText(text, 1) }.getOrNull() ?: false
        if (!committed) return copy(ctx, text)

        if (action == DeliveryAction.INSERT_SEND && canSend(info)) {
            val sent = runCatching { ic?.performEditorAction(info!!.imeOptions and EditorInfo.IME_MASK_ACTION) }.getOrNull() ?: false
            return if (sent) Result.SENT else Result.INSERTED
        }
        return Result.INSERTED
    }

    /** True when the editor really has a send-ish action and has not opted out of enter handling. */
    fun canSend(info: EditorInfo?): Boolean {
        if (info == null) return false
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return false
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        return action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_DONE
    }

    private fun copy(ctx: Context, text: String): Result {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return Result.FAILED
        val clip = ClipData.newPlainText("Reev dictation", text)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        return runCatching { cm.setPrimaryClip(clip); Result.COPIED }.getOrElse { Result.FAILED }
    }
}
