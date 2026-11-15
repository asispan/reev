package com.asis.reev.delivery

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** canSend() is pure bit work, so it runs without Robolectric. */
class DeliverySendTest {

    private fun info(options: Int) = EditorInfo().apply { imeOptions = options }

    @Test fun sendActionAllowed() {
        assertTrue(Delivery.canSend(info(EditorInfo.IME_ACTION_SEND)))
    }

    @Test fun doneActionAllowed() {
        assertTrue(Delivery.canSend(info(EditorInfo.IME_ACTION_DONE)))
    }

    @Test fun searchActionNotSent() {
        assertFalse(Delivery.canSend(info(EditorInfo.IME_ACTION_SEARCH)))
    }

    @Test fun noEnterActionFlagWins() {
        val both = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        assertFalse(Delivery.canSend(info(both)))
    }

    @Test fun nullEditorCannotSend() {
        assertFalse(Delivery.canSend(null))
    }
}
