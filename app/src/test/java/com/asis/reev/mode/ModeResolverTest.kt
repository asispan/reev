package com.asis.reev.mode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeResolverTest {

    private val modes = Mode.builtIns()

    @Test fun picksTheModeThatClaimsTheApp() {
        assertEquals("messages", ModeResolver.resolve(modes, "com.whatsapp", null).id)
        assertEquals("prompts", ModeResolver.resolve(modes, "com.openai.chatgpt", null).id)
    }

    @Test fun unknownAppFallsBack() {
        assertTrue(ModeResolver.resolve(modes, "com.android.chrome", null).fallback)
        assertTrue(ModeResolver.resolve(modes, null, null).fallback)
    }

    @Test fun manualOverrideBeatsTheApp() {
        assertEquals("prompts", ModeResolver.resolve(modes, "com.whatsapp", "prompts").id)
    }

    @Test fun unknownOverrideIsIgnored() {
        assertEquals("messages", ModeResolver.resolve(modes, "com.whatsapp", "nope").id)
    }

    @Test fun packageMatchIgnoresCase() {
        assertEquals("messages", ModeResolver.resolve(modes, "COM.WHATSAPP", null).id)
    }

    @Test fun jsonRoundTripKeepsFields() {
        val m = Mode("x", "Name", "do the thing", DeliveryAction.COPY, listOf("a.b"), fallback = true)
        val back = Mode.from(m.toJson())
        assertEquals(m, back)
    }

    @Test fun deliveryActionsRoundTripAndDefault() {
        assertEquals(DeliveryAction.INSERT_SEND, DeliveryAction.from("send"))
        assertEquals(DeliveryAction.INSERT, DeliveryAction.from("garbage"))
        assertFalse(Mode.builtIns().none { it.fallback })
    }
}
