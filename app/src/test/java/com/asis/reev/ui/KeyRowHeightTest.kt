package com.asis.reev.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Landscape phones must keep the focused field visible; portrait and tablets keep full-height rows. */
class KeyRowHeightTest {
    @Test fun portraitUnchanged() = assertEquals(141.75f, keyRowHeightPx(141.75f, false, 2400f, 2.625f), 0.01f)

    @Test fun phoneLandscapeCapped() {
        val d = 2.625f // Pixel 7: 1080 px tall in landscape
        val row = keyRowHeightPx(54 * d, true, 1080f, d)
        assertTrue("keyboard ${64 * d + 4 * row}px > 55% of 1080", 64 * d + 4 * row <= 1080f * 0.55f)
        assertTrue(row >= 32 * d)
    }

    @Test fun tabletLandscapeUnchanged() = assertEquals(108f, keyRowHeightPx(108f, true, 1600f, 2f), 0.01f)
}
