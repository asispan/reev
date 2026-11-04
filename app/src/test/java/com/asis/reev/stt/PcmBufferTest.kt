package com.asis.reev.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmBufferTest {
    @Test fun growsAndSnapshots() {
        val b = PcmBuffer(maxSamples = 100)
        b.add(ShortArray(30) { it.toShort() })
        b.add(ShortArray(30) { (100 + it).toShort() })
        val s = b.snapshot()
        assertEquals(60, s.size)
        assertEquals(0.toShort(), s[0]); assertEquals(100.toShort(), s[30])
        assertFalse(b.full)
    }

    @Test fun capDropsAudioInsteadOfGrowingForever() {
        val b = PcmBuffer(maxSamples = 100)
        repeat(10) { b.add(ShortArray(40)) }
        assertEquals(100, b.size)
        assertTrue(b.full)
        assertEquals(100, b.snapshot().size)
    }
}
