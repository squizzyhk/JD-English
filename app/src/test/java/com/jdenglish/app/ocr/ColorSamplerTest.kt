package com.jdenglish.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSamplerTest {

    private val W = 120
    private val H = 60
    private val box = IRect(20, 15, 100, 45)

    /** Background everywhere, then "glyph strokes" of [fg] with anti-aliased edges inside [box]. */
    private fun image(bg: Int, fg: Int, alpha: Int = 0xFF): IntArray {
        val px = IntArray(W * H) { (alpha shl 24) or bg }
        val mid = mix(fg, bg)
        for (y in box.top + 4 until box.bottom - 4) {
            for (x in box.left + 2 until box.right - 2) {
                val col = (x - box.left) % 8
                px[y * W + x] = (0xFF shl 24) or when (col) {
                    2, 3, 4 -> fg   // stroke core
                    1, 5 -> mid     // anti-aliased edge
                    else -> bg
                }
            }
        }
        return px
    }

    private fun mix(a: Int, b: Int): Int = ColorSampler.rgb(
        (((a shr 16) and 0xFF) + ((b shr 16) and 0xFF)) / 2,
        (((a shr 8) and 0xFF) + ((b shr 8) and 0xFF)) / 2,
        ((a and 0xFF) + (b and 0xFF)) / 2,
    )

    private fun close(expected: Int, actual: Int, tol: Int = 12) {
        val d = ColorSampler.distance(expected, actual)
        assertTrue("expected ${ColorSampler.toHex(expected)} got ${ColorSampler.toHex(actual)} (d=$d)", d <= tol)
    }

    @Test fun whiteTextOnJdRed() {
        val c = ColorSampler.sample(image(0xE1251B, 0xFFFFFF), W, H, box)
        close(0xE1251B, c.bg)
        close(0xFFFFFF, c.fg)
        assertEquals("#E1251B", c.bgHex)
    }

    @Test fun darkTextOnWhite() {
        val c = ColorSampler.sample(image(0xFFFFFF, 0x333333), W, H, box)
        close(0xFFFFFF, c.bg)
        close(0x333333, c.fg)
    }

    @Test fun ignoresAntiAliasingWhenPickingTextColor() {
        // Yellow text on dark blue: the mid colour must not be chosen.
        val c = ColorSampler.sample(image(0x10206A, 0xFFD700), W, H, box)
        close(0x10206A, c.bg)
        close(0xFFD700, c.fg)
    }

    @Test fun lowContrastFallsBackToBlackOrWhite() {
        // Light grey text on white -> contrast < 3 -> black.
        val c = ColorSampler.sample(image(0xFFFFFF, 0xCCCCCC), W, H, box)
        assertEquals(ColorSampler.BLACK, c.fg)
        // Mid grey on black -> white.
        val d = ColorSampler.sample(image(0x000000, 0x404040), W, H, box)
        assertEquals(ColorSampler.WHITE, d.fg)
    }

    @Test fun uniformBoxGetsReadableColor() {
        val px = IntArray(W * H) { 0xFF00AA00.toInt() }
        val c = ColorSampler.sample(px, W, H, box)
        close(0x00AA00, c.bg, 2)
        assertTrue(ColorSampler.contrastRatio(c.fg, c.bg) >= 3.0)
    }

    @Test fun boxFillingWholeImageUsesItsOwnBorder() {
        val px = image(0xE1251B, 0xFFFFFF)
        // Crop exactly to the box so no ring exists outside it.
        val cw = box.width; val ch = box.height
        val crop = IntArray(cw * ch) { i -> px[(box.top + i / cw) * W + box.left + i % cw] }
        val c = ColorSampler.sample(crop, cw, ch, IRect(0, 0, cw, ch))
        close(0xE1251B, c.bg)
        close(0xFFFFFF, c.fg)
    }

    @Test fun gradientBackgroundUsesMedian() {
        val px = IntArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            px[y * W + x] = (0xFF shl 24) or ColorSampler.rgb(x * 2, 0, 255 - x * 2)
        }
        val c = ColorSampler.sample(px, W, H, box)
        // Median of the ring around a box centred at x=60 is roughly (120, 0, 135).
        close(ColorSampler.rgb(120, 0, 135), c.bg, 40)
    }

    @Test fun transparentPixelsCompositeOverWhite() {
        assertEquals(0xFFFFFF, ColorSampler.opaque(0x00000000))
        assertEquals(0x000000, ColorSampler.opaque(0xFF000000.toInt()))
        close(0x808080, ColorSampler.opaque(0x80000000.toInt()), 3)
        val c = ColorSampler.sample(image(0x000000, 0xE1251B, alpha = 0), W, H, box)
        close(0xFFFFFF, c.bg)
    }

    @Test fun contrastRatioMatchesWcag() {
        assertEquals(21.0, ColorSampler.contrastRatio(0xFFFFFF, 0x000000), 0.01)
        assertEquals(1.0, ColorSampler.contrastRatio(0x777777, 0x777777), 0.001)
        assertEquals(ColorSampler.WHITE, ColorSampler.bestBlackOrWhite(0xE1251B))
        assertEquals(ColorSampler.BLACK, ColorSampler.bestBlackOrWhite(0xFFD700))
    }

    @Test fun hexFormatting() {
        assertEquals("#00FF0A", ColorSampler.toHex(0xFF00FF0A.toInt()))
        assertEquals("#000000", ColorSampler.toHex(0))
    }

    @Test fun boxPartlyOutsideRegionIsClamped() {
        val px = image(0xFFFFFF, 0x222222)
        val c = ColorSampler.sample(px, W, H, IRect(-10, -10, 60, 40))
        assertTrue(ColorSampler.contrastRatio(c.fg, c.bg) >= 3.0)
        val empty = ColorSampler.sample(px, W, H, IRect(500, 500, 600, 600))
        assertEquals(ColorSampler.Colors(ColorSampler.BLACK, ColorSampler.WHITE), empty)
    }
}
