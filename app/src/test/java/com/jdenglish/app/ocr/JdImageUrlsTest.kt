package com.jdenglish.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JdImageUrlsTest {

    @Test fun normalizesProtocolRelative() {
        assertEquals("https://img10.360buyimg.com/n1/a.jpg", JdImageUrls.normalize("//img10.360buyimg.com/n1/a.jpg"))
        assertEquals("https://img10.360buyimg.com/n1/a.jpg", JdImageUrls.normalize(" http://img10.360buyimg.com/n1/a.jpg "))
        assertEquals("http://example.com/a.jpg", JdImageUrls.normalize("http://example.com/a.jpg"))
        assertEquals("data:image/png;base64,AAAA", JdImageUrls.normalize("data:image/png;base64,AAAA"))
    }

    @Test fun detectsJdCdn() {
        assertTrue(JdImageUrls.isJdCdn("https://img14.360buyimg.com/n1/x.jpg"))
        assertTrue(JdImageUrls.isJdCdn("https://m.360buyimg.com/x.jpg"))
        assertFalse(JdImageUrls.isJdCdn("https://evil360buyimg.com/x.jpg"))
        assertFalse(JdImageUrls.isJdCdn("https://m.jd.com/x.jpg"))
    }

    @Test fun stripsDpgWebpSuffix() {
        val u = "https://img14.360buyimg.com/n1/s450x450_jfs/t1/1/2/3/4/abc.jpg!q70.dpg.webp"
        assertEquals(listOf(u, "https://img14.360buyimg.com/n1/s450x450_jfs/t1/1/2/3/4/abc.jpg"), JdImageUrls.candidates(u))
    }

    @Test fun stripsAvifAndWebpAndDpgExtensions() {
        val base = "https://m.360buyimg.com/umm/jfs/t1/1/2/x"
        assertEquals("$base.jpg", JdImageUrls.candidates("$base.jpg.avif").last())
        assertEquals("$base.png", JdImageUrls.candidates("$base.png.webp").last())
        assertEquals("$base.jpg", JdImageUrls.candidates("$base.jpg.dpg").last())
        assertEquals("$base.jpg", JdImageUrls.candidates("$base.jpg.dpg.webp").last())
        assertEquals("$base.png", JdImageUrls.candidates("$base.png/").last())
        assertEquals("$base.jpg", JdImageUrls.candidates("//m.360buyimg.com/umm/jfs/t1/1/2/x.jpg!q80.dpg.webp").last())
    }

    @Test fun plainUrlsHaveSingleCandidate() {
        val u = "https://img20.360buyimg.com/babel/jfs/t1/x.png"
        assertEquals(listOf(u), JdImageUrls.candidates(u))
        val other = "https://example.com/a.jpg!q70.dpg.webp"
        assertEquals(listOf(other), JdImageUrls.candidates(other))
    }

    @Test fun dropsQueryOnFallback() {
        val u = "https://img10.360buyimg.com/n1/abc.jpg.avif?v=1"
        assertEquals(listOf(u, "https://img10.360buyimg.com/n1/abc.jpg"), JdImageUrls.candidates(u))
    }

    @Test fun tilePlannerSingleTileForNormalImages() {
        assertEquals(listOf(Tile(0, 600, 1f)), TilePlanner.plan(800, 600))
        val wide = TilePlanner.plan(4000, 500)
        assertEquals(1, wide.size)
        assertEquals(0.5f, wide[0].scale, 0.0001f)
        assertTrue(TilePlanner.plan(0, 100).isEmpty())
    }

    @Test fun tilePlannerCoversTallImagesWithOverlap() {
        val h = 5200
        val tiles = TilePlanner.plan(750, h)
        assertTrue(tiles.size >= 4)
        assertEquals(0, tiles.first().top)
        assertEquals(h, tiles.last().top + tiles.last().height)
        for (i in 1 until tiles.size) {
            val prevEnd = tiles[i - 1].top + tiles[i - 1].height
            assertTrue("tile $i must overlap previous", tiles[i].top <= prevEnd - TilePlanner.OVERLAP)
            assertTrue(tiles[i].top > tiles[i - 1].top)
        }
        tiles.forEach { assertTrue(it.height <= TilePlanner.TILE_HEIGHT) ; assertEquals(1f, it.scale, 0f) }
    }

    @Test fun tilePlannerScalesWideTallImages() {
        val tiles = TilePlanner.plan(3000, 12000)
        val s = 2000f / 3000f
        tiles.forEach {
            assertEquals(s, it.scale, 0.0001f)
            assertTrue(it.height * it.scale <= TilePlanner.MAX_SIDE + 1)
        }
        assertEquals(12000, tiles.last().top + tiles.last().height)
    }

    @Test fun tilePlannerNoSliverAtBottom() {
        // 1500 + 1420 = 2920; a naive plan would leave an 80px sliver tile.
        val tiles = TilePlanner.plan(750, 3000)
        tiles.forEach { assertTrue("tile too small: $it", it.height >= 200) }
        assertEquals(3000, tiles.last().top + tiles.last().height)
    }

    @Test fun resultCacheRoundTripAndKeying() {
        val dir: File = Files.createTempDirectory("ocrcache").toFile()
        try {
            val c = OcrResultCache(dir, memEntries = 2, maxFiles = 2000)
            val k1 = c.key("https://a/1.jpg", "mlkit")
            val k2 = c.key("https://a/1.jpg", "claude")
            assertTrue(k1 != k2)
            c.put(k1, "{\"w\":1}")
            assertEquals("{\"w\":1}", c.get(k1))
            // A fresh instance (new process) reads it from disk.
            assertEquals("{\"w\":1}", OcrResultCache(dir).get(k1))
            assertEquals(null, c.get(k2))
            c.clear()
            assertEquals(null, OcrResultCache(dir).get(k1))
        } finally {
            dir.deleteRecursively()
        }
    }
}
