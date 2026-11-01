package com.jdenglish.app.ocr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Estimates the background and text colors of a text block from raw ARGB pixels.
 *
 * - Background: dominant color of a ring 2..4 px outside the box (falls back to the box's
 *   own border when the ring is clipped by the image edge, and to the per-channel median when
 *   the background is a gradient with no clear dominant color).
 * - Text: among pixels inside the box that are far from the background, the most populated
 *   color cluster (so anti-aliased edge pixels, which are intermediate colors, are ignored).
 * - Contrast: if fg/bg contrast is below 3:1, fg becomes white or black, whichever reads better.
 *
 * Pure Kotlin on an IntArray of ARGB (row-major); see [BitmapColorSampler] for the Android wrapper.
 */
object ColorSampler {

    data class Colors(val fg: Int, val bg: Int) {
        val fgHex: String get() = toHex(fg)
        val bgHex: String get() = toHex(bg)
    }

    const val WHITE = 0xFFFFFF
    const val BLACK = 0x000000
    private const val RING_INNER = 2
    private const val RING_OUTER = 4
    private const val MIN_CONTRAST = 3.0

    /**
     * @param pixels ARGB pixels of a region, row-major, [width] x [height]
     * @param box the text box in the region's coordinates (may touch/exceed the region edges)
     * @return colors as 0xRRGGBB (alpha composited over white)
     */
    fun sample(pixels: IntArray, width: Int, height: Int, box: IRect): Colors {
        require(pixels.size >= width * height) { "pixel buffer too small" }
        val inner = box.clamp(width, height)
        if (inner.isEmpty) return Colors(BLACK, WHITE)

        // 1) Background from the ring outside the box.
        val ring = IntArrayList()
        val outer = box.outset(RING_OUTER).clamp(width, height)
        val hole = box.outset(RING_INNER - 1)
        for (y in outer.top until outer.bottom) {
            val row = y * width
            val insideRowHole = y >= hole.top && y < hole.bottom
            for (x in outer.left until outer.right) {
                if (insideRowHole && x >= hole.left && x < hole.right) continue
                ring.add(opaque(pixels[row + x]))
            }
        }
        if (ring.size < 8) {
            // Ring clipped away (box fills the image): use the box's own 2px border.
            val core = inner.inset(2)
            for (y in inner.top until inner.bottom) for (x in inner.left until inner.right) {
                val onBorder = core.isEmpty || y < core.top || y >= core.bottom || x < core.left || x >= core.right
                if (onBorder) ring.add(opaque(pixels[y * width + x]))
            }
        }
        val bg = if (ring.size == 0) WHITE else dominantOrMedian(ring)

        // 2) Text color: the biggest cluster among pixels far from the background.
        val dists = IntArray(inner.area.toInt())
        val colors = IntArray(dists.size)
        var n = 0
        var maxD = 0
        for (y in inner.top until inner.bottom) {
            val row = y * width
            for (x in inner.left until inner.right) {
                val c = opaque(pixels[row + x])
                val d = distance(c, bg)
                colors[n] = c; dists[n] = d; n++
                if (d > maxD) maxD = d
            }
        }
        var fg: Int
        if (maxD < 40) {
            // No visible ink (e.g. very faint text): choose a readable color.
            fg = bestBlackOrWhite(bg)
        } else {
            val threshold = max(40, (maxD * 0.55).toInt())
            val counts = HashMap<Int, IntArray>() // bin -> [count, r, g, b, sumDist]
            for (i in 0 until n) {
                if (dists[i] < threshold) continue
                val c = colors[i]
                val bin = quantize(c)
                val acc = counts.getOrPut(bin) { IntArray(5) }
                acc[0]++; acc[1] += (c shr 16) and 0xFF; acc[2] += (c shr 8) and 0xFF; acc[3] += c and 0xFF; acc[4] += dists[i]
            }
            // Score clusters by population, slightly favouring stronger contrast.
            var bestScore = -1.0
            var best: IntArray? = null
            for (acc in counts.values) {
                val meanD = acc[4].toDouble() / acc[0]
                val score = acc[0] * (0.5 + meanD / maxD)
                if (score > bestScore) { bestScore = score; best = acc }
            }
            fg = if (best == null) bestBlackOrWhite(bg)
            else rgb(best[1] / best[0], best[2] / best[0], best[3] / best[0])
        }

        if (contrastRatio(fg, bg) < MIN_CONTRAST) fg = bestBlackOrWhite(bg)
        return Colors(fg and 0xFFFFFF, bg and 0xFFFFFF)
    }

    /** Dominant 4-bit-per-channel bin (averaged), or per-channel median if nothing dominates. */
    internal fun dominantOrMedian(list: IntArrayList): Int {
        val counts = HashMap<Int, IntArray>()
        for (i in 0 until list.size) {
            val c = list[i]
            val acc = counts.getOrPut(quantize(c)) { IntArray(4) }
            acc[0]++; acc[1] += (c shr 16) and 0xFF; acc[2] += (c shr 8) and 0xFF; acc[3] += c and 0xFF
        }
        val top = counts.values.maxByOrNull { it[0] }!!
        if (top[0] * 4 >= list.size) return rgb(top[1] / top[0], top[2] / top[0], top[3] / top[0])
        val r = IntArray(list.size); val g = IntArray(list.size); val b = IntArray(list.size)
        for (i in 0 until list.size) {
            val c = list[i]
            r[i] = (c shr 16) and 0xFF; g[i] = (c shr 8) and 0xFF; b[i] = c and 0xFF
        }
        r.sort(); g.sort(); b.sort()
        val m = list.size / 2
        return rgb(r[m], g[m], b[m])
    }

    private fun quantize(c: Int): Int = ((c shr 20) and 0xF shl 8) or ((c shr 12) and 0xF shl 4) or ((c shr 4) and 0xF)

    /** Composites a possibly-transparent ARGB pixel over white (the usual page background). */
    fun opaque(argb: Int): Int {
        val a = (argb ushr 24) and 0xFF
        if (a == 0xFF) return argb and 0xFFFFFF
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        fun mix(ch: Int) = (ch * a + 255 * (255 - a)) / 255
        return rgb(mix(r), mix(g), mix(b))
    }

    /** Weighted ("redmean") RGB distance, roughly 0..765. */
    fun distance(c1: Int, c2: Int): Int {
        val r1 = (c1 shr 16) and 0xFF; val g1 = (c1 shr 8) and 0xFF; val b1 = c1 and 0xFF
        val r2 = (c2 shr 16) and 0xFF; val g2 = (c2 shr 8) and 0xFF; val b2 = c2 and 0xFF
        val rm = (r1 + r2) / 2
        val dr = r1 - r2; val dg = g1 - g2; val db = b1 - b2
        val sq = (((512 + rm) * dr * dr) shr 8) + 4 * dg * dg + (((767 - rm) * db * db) shr 8)
        return kotlin.math.sqrt(sq.toDouble()).toInt()
    }

    /** WCAG relative luminance, 0..1. */
    fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    /** WCAG contrast ratio, 1..21. */
    fun contrastRatio(c1: Int, c2: Int): Double {
        val l1 = luminance(c1); val l2 = luminance(c2)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    fun bestBlackOrWhite(bg: Int): Int = if (contrastRatio(WHITE, bg) >= contrastRatio(BLACK, bg)) WHITE else BLACK

    fun rgb(r: Int, g: Int, b: Int): Int = (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun toHex(c: Int): String = String.format(java.util.Locale.ROOT, "#%06X", c and 0xFFFFFF)
}

/** Minimal growable int list (avoids boxing on hot pixel loops). */
internal class IntArrayList(initial: Int = 256) {
    private var data = IntArray(initial)
    var size = 0
        private set
    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    operator fun get(i: Int): Int = data[i]
}
