package com.jdenglish.app.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Axis-aligned integer rectangle (left/top inclusive, right/bottom exclusive). Pure Kotlin. */
data class IRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = if (width <= 0 || height <= 0) 0L else width.toLong() * height.toLong()
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun union(o: IRect) = IRect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))

    fun intersectionArea(o: IRect): Long {
        val w = min(right, o.right) - max(left, o.left)
        val h = min(bottom, o.bottom) - max(top, o.top)
        return if (w <= 0 || h <= 0) 0L else w.toLong() * h.toLong()
    }

    fun inset(d: Int) = IRect(left + d, top + d, right - d, bottom - d)
    fun outset(d: Int) = IRect(left - d, top - d, right + d, bottom + d)
    fun clamp(w: Int, h: Int) = IRect(left.coerceIn(0, w), top.coerceIn(0, h), right.coerceIn(0, w), bottom.coerceIn(0, h))
    fun offset(dx: Int, dy: Int) = IRect(left + dx, top + dy, right + dx, bottom + dy)
}

/** One OCR line in image space. [angle] is in degrees (ML Kit convention), 0 = horizontal. */
data class OcrLine(val text: String, val rect: IRect, val angle: Float = 0f)

/** A group of lines that reads as one unit (a headline, a label, a paragraph). */
data class OcrBlock(val text: String, val rect: IRect, val angle: Float, val lines: List<OcrLine>)

/**
 * Merges OCR lines into translation units. A multi-line paragraph should be translated as a
 * single sentence (Chinese wraps mid-word, so per-line translation reads terribly), while a big
 * headline above small body text must stay separate.
 *
 * Pure Kotlin; no Android types.
 */
object BlockGrouper {

    /** Max relative height difference for lines in the same block (±35%). */
    private const val HEIGHT_TOLERANCE = 0.35f
    /** Max vertical gap between consecutive lines, as a fraction of line height. */
    private const val MAX_GAP_FACTOR = 0.8f
    /** Lines overlapping vertically by more than this fraction of height are on the same row. */
    private const val SAME_ROW_OVERLAP = 0.45f
    /** Max angle difference in degrees. */
    private const val MAX_ANGLE_DIFF = 6f

    private val HAN = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]")
    private val SPACE_BETWEEN_CJK =
        Regex("(?<=[\\u3000-\\u303F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\uFF01-\\uFF60])\\s+(?=[\\u3000-\\u303F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\uFF01-\\uFF60])")
    private val MULTI_SPACE = Regex("\\s+")

    fun hasHan(s: String) = HAN.containsMatchIn(s)

    /** Normalizes OCR text: collapses whitespace and removes spaces the recognizer puts between Han characters. */
    fun cleanText(s: String): String = SPACE_BETWEEN_CJK.replace(MULTI_SPACE.replace(s.trim(), " "), "")

    /**
     * Removes duplicate lines produced by overlapping OCR tiles. When two lines cover mostly the
     * same area the more complete one (larger area, then longer text) is kept, since the copy
     * near a tile edge is often clipped.
     */
    fun dedupe(lines: List<OcrLine>): List<OcrLine> {
        val sorted = lines.sortedWith(compareByDescending<OcrLine> { it.rect.area }.thenByDescending { it.text.length })
        val kept = ArrayList<OcrLine>(sorted.size)
        for (l in sorted) {
            val dup = kept.any { k ->
                val inter = k.rect.intersectionArea(l.rect)
                if (inter == 0L) return@any false
                val minArea = min(k.rect.area, l.rect.area).coerceAtLeast(1)
                // Clipped copy: mostly inside the other, and vertically aligned.
                inter.toDouble() / minArea > 0.6 && verticalOverlap(k.rect, l.rect) > 0.5f
            }
            if (!dup) kept += l
        }
        return kept.sortedWith(compareBy<OcrLine> { it.rect.top }.thenBy { it.rect.left })
    }

    /**
     * Groups lines into blocks. Lines without Han characters should be filtered out by the
     * caller beforehand (they are not overlaid).
     */
    fun group(input: List<OcrLine>): List<OcrBlock> {
        val lines = input.filter { !it.rect.isEmpty && it.text.isNotBlank() }
            .sortedWith(compareBy<OcrLine> { it.rect.top }.thenBy { it.rect.left })
        val blocks = ArrayList<MutableList<OcrLine>>()
        for (line in lines) {
            var best: MutableList<OcrLine>? = null
            var bestGap = Float.MAX_VALUE
            for (b in blocks) {
                val last = b.last()
                if (!canFollow(b, last, line)) continue
                val gap = (line.rect.top - last.rect.bottom).toFloat()
                if (gap < bestGap) { bestGap = gap; best = b }
            }
            if (best != null) best += line else blocks += mutableListOf(line)
        }
        return blocks.map { toBlock(it) }
            .sortedWith(compareBy<OcrBlock> { it.rect.top }.thenBy { it.rect.left })
    }

    private fun canFollow(block: List<OcrLine>, last: OcrLine, next: OcrLine): Boolean {
        val a = last.rect
        val b = next.rect
        if (abs(last.angle - next.angle) > MAX_ANGLE_DIFF) return false
        // Similar height, compared both to the last line and to the block's typical height.
        val blockH = block.map { it.rect.height }.sorted()[block.size / 2].toFloat()
        if (!similarHeight(a.height.toFloat(), b.height.toFloat())) return false
        if (!similarHeight(blockH, b.height.toFloat())) return false
        val h = (a.height + b.height) / 2f
        // Must be below, not side by side on the same row.
        if (verticalOverlap(a, b) > SAME_ROW_OVERLAP) return false
        if (b.centerY <= a.centerY) return false
        val gap = (b.top - a.bottom).toFloat()
        if (gap >= MAX_GAP_FACTOR * h) return false
        // Horizontal relation: left-aligned, centred, or substantially overlapping. A short line
        // that merely grazes a long one (e.g. a price tag tucked under the end of a title) is a
        // separate label.
        val xOverlap = min(a.right, b.right) - max(a.left, b.left)
        val narrower = min(a.width, b.width).coerceAtLeast(1)
        val leftAligned = abs(a.left - b.left) < 1.0f * h
        val centred = abs(a.centerX - b.centerX) < 1.0f * h
        val overlapping = xOverlap >= 0.6f * narrower
        return leftAligned || centred || overlapping
    }

    private fun similarHeight(h1: Float, h2: Float): Boolean {
        val big = max(h1, h2)
        val small = min(h1, h2)
        if (big <= 0f) return false
        return (big - small) / big <= HEIGHT_TOLERANCE
    }

    /** Fraction of the smaller height that the two rects overlap vertically. */
    private fun verticalOverlap(a: IRect, b: IRect): Float {
        val ov = min(a.bottom, b.bottom) - max(a.top, b.top)
        val minH = min(a.height, b.height).coerceAtLeast(1)
        return ov.toFloat() / minH
    }

    private fun toBlock(lines: List<OcrLine>): OcrBlock {
        var rect = lines[0].rect
        val sb = StringBuilder(cleanText(lines[0].text))
        for (i in 1 until lines.size) {
            rect = rect.union(lines[i].rect)
            val t = cleanText(lines[i].text)
            if (t.isEmpty()) continue
            if (sb.isNotEmpty() && needsSpace(sb[sb.length - 1], t[0])) sb.append(' ')
            sb.append(t)
        }
        val angle = lines.map { it.angle }.average().toFloat()
        return OcrBlock(sb.toString(), rect, if (angle.isNaN()) 0f else angle, lines)
    }

    /** Chinese joins without spaces; Latin words/numbers split across lines need one. */
    private fun needsSpace(prev: Char, next: Char): Boolean {
        fun latin(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
        return latin(prev) && latin(next) || (prev in ",.;:!?" && latin(next))
    }
}
