package com.asis.reev.ui

import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.asis.reev.R

/** One control painted inside a Canvas-drawn view, as a screen reader should see it. */
class A11yItem(
    val id: Int,
    val name: String,
    val bounds: RectF,
    val clickable: Boolean = true,
    /** A keyboard key: TalkBack types it when the exploring finger lifts (its "lift to type"). */
    val textKey: Boolean = false,
    val selected: Boolean = false,
    /** Long-press alternatives, offered as extra screen-reader actions. */
    val alts: List<String> = emptyList(),
)

/**
 * Exposes the controls a custom view draws as virtual accessibility nodes, so TalkBack can find, name and
 * activate them. The keyboard's views paint everything themselves; without this a screen reader sees one
 * unlabeled rectangle. Does nothing unless an accessibility service is running.
 *
 * The host view must forward `dispatchHoverEvent` to [dispatchHoverEvent] and call [invalidateRoot]
 * whenever what [items] returns changes.
 */
class VirtualNodes(
    host: View,
    private val items: () -> List<A11yItem>,
    private val onClick: (A11yItem) -> Unit,
    private val onAlt: (A11yItem, Int) -> Unit = { _, _ -> },
) : ExploreByTouchHelper(host) {

    init { ViewCompat.setAccessibilityDelegate(host, this) }

    private fun find(id: Int) = items().firstOrNull { it.id == id }

    override fun getVirtualViewAt(x: Float, y: Float): Int =
        items().firstOrNull { it.bounds.contains(x, y) }?.id ?: HOST_ID

    override fun getVisibleVirtualViews(ids: MutableList<Int>) { items().forEach { ids.add(it.id) } }

    override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
        val it = find(id)
        val r = Rect()
        it?.bounds?.roundOut(r)
        if (r.isEmpty) r.set(0, 0, 1, 1)
        @Suppress("DEPRECATION") // ExploreByTouchHelper derives the screen bounds from these.
        node.setBoundsInParent(r)
        // A node that has just gone away (layout switched under the reader) still needs a description.
        node.contentDescription = it?.name ?: ""
        if (it == null) return
        node.className = when {
            it.textKey -> "android.inputmethodservice.Keyboard\$Key"
            it.clickable -> "android.widget.Button"
            else -> "android.widget.TextView"
        }
        if (it.clickable) { node.isClickable = true; node.addAction(AccessibilityActionCompat.ACTION_CLICK) }
        if (it.textKey) node.isTextEntryKey = true
        if (it.selected) node.isSelected = true
        it.alts.take(ALT_IDS.size).forEachIndexed { i, s ->
            node.addAction(AccessibilityActionCompat(ALT_IDS[i], "Type ${KeyNames.charName(s)}"))
        }
    }

    override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
        val it = find(id) ?: return false
        if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
            if (!it.clickable) return false
            onClick(it); return true
        }
        val alt = ALT_IDS.indexOf(action)
        if (alt >= 0 && alt < it.alts.size) { onAlt(it, alt); return true }
        return false
    }

    private companion object {
        // Custom accessibility actions must be identified by resource ids.
        val ALT_IDS = intArrayOf(
            R.id.a11y_alt_0, R.id.a11y_alt_1, R.id.a11y_alt_2, R.id.a11y_alt_3, R.id.a11y_alt_4,
            R.id.a11y_alt_5, R.id.a11y_alt_6, R.id.a11y_alt_7, R.id.a11y_alt_8, R.id.a11y_alt_9,
        )
    }
}
