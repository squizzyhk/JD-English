package com.jdenglish.app.ocr

/**
 * URL helpers for JD's image CDN (*.360buyimg.com). Pure Kotlin.
 *
 * JD appends format/quality suffixes to image URLs, e.g.
 *   .../xxx.jpg!q70.dpg.webp   .../xxx.jpg.avif   .../xxx.png.webp   .../xxx.jpg.dpg   .../xxx.png/
 * The original JPEG/PNG behind them is always available, so when a variant fails to download or
 * decode (AVIF before Android 12, odd encoders) we retry with the plain URL.
 */
object JdImageUrls {

    private val IMG_EXT_END = Regex("\\.(jpe?g|png|gif|bmp|webp|avif|dpg)$", RegexOption.IGNORE_CASE)
    private val TRAILING_VARIANT = Regex("(\\.(jpe?g|png|gif|bmp))((\\.(avif|webp|dpg))+)$", RegexOption.IGNORE_CASE)

    /** "//img10.360buyimg.com/a.jpg" -> "https://img10.360buyimg.com/a.jpg"; trims whitespace. */
    fun normalize(url: String): String {
        val u = url.trim()
        return when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("http://", ignoreCase = true) && isJdCdn(u) -> "https://" + u.substring(7)
            else -> u
        }
    }

    fun isJdCdn(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host == "360buyimg.com" || host.endsWith(".360buyimg.com")
    }

    fun hostOf(url: String): String? {
        val i = url.indexOf("://")
        if (i < 0) return null
        val rest = url.substring(i + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        return rest.substring(0, end).substringAfter('@').substringBefore(':').lowercase()
    }

    /**
     * Candidate URLs to try, in order: the URL itself, then progressively plainer variants
     * (only for JD's CDN). Never contains duplicates.
     */
    fun candidates(url: String): List<String> {
        val first = normalize(url)
        val out = LinkedHashSet<String>()
        out += first
        if (!isJdCdn(first)) return out.toList()

        val qIdx = first.indexOfFirst { it == '?' || it == '#' }
        val path = if (qIdx >= 0) first.substring(0, qIdx) else first

        var plain = path
        // "xxx.jpg!q70.dpg.webp" / "xxx.jpg!cc_100x100" -> "xxx.jpg"
        val bang = plain.indexOf('!', startIndex = plain.indexOf("://") + 3)
        if (bang > 0) plain = plain.substring(0, bang)
        // Trailing slash after a file name: "xxx.png/" -> "xxx.png"
        if (plain.endsWith("/")) {
            val t = plain.trimEnd('/')
            if (IMG_EXT_END.containsMatchIn(t)) plain = t
        }
        // "xxx.jpg.avif" / "xxx.png.webp" / "xxx.jpg.dpg" -> "xxx.jpg"
        TRAILING_VARIANT.find(plain)?.let { m -> plain = plain.substring(0, m.range.first) + m.groupValues[1] }
        if (plain != path) out += plain
        return out.toList()
    }
}

/** A horizontal strip of the source image to OCR, and the scale applied to it. */
data class Tile(val top: Int, val height: Int, val scale: Float)

/**
 * Splits tall images (JD product descriptions are often 750x5000+) into overlapping strips so
 * ML Kit sees text at a usable resolution. Pure Kotlin.
 */
object TilePlanner {
    const val MAX_SIDE = 2000
    const val TILE_HEIGHT = 1500
    const val OVERLAP = 80

    fun plan(width: Int, height: Int): List<Tile> {
        if (width <= 0 || height <= 0) return emptyList()
        val scale = minOf(1f, MAX_SIDE.toFloat() / width)
        // Whole image fits in one OCR pass.
        if (height * scale <= MAX_SIDE) return listOf(Tile(0, height, minOf(scale, MAX_SIDE.toFloat() / height)))
        val tileH = (TILE_HEIGHT / scale).toInt().coerceAtLeast(1)
        val overlap = (OVERLAP / scale).toInt().coerceIn(0, tileH / 2)
        val step = (tileH - overlap).coerceAtLeast(1)
        val tiles = ArrayList<Tile>()
        var top = 0
        while (true) {
            val h = minOf(tileH, height - top)
            tiles += Tile(top, h, scale)
            if (top + h >= height) break
            top += step
            // Avoid a sliver tile at the bottom: stretch the last one back instead.
            if (height - top < overlap * 2 + 1) {
                top = maxOf(0, height - tileH)
                if (top <= tiles.last().top) break
            }
        }
        return tiles
    }
}
