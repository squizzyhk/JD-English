package com.jdenglish.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockGrouperTest {

    private fun line(text: String, l: Int, t: Int, r: Int, b: Int, angle: Float = 0f) = OcrLine(text, IRect(l, t, r, b), angle)

    @Test fun mergesLeftAlignedParagraphIntoOneSentence() {
        val lines = listOf(
            line("采用进口优质原料，精心", 20, 100, 500, 130),
            line("酿造，口感醇厚绵柔，回", 20, 138, 500, 168),
            line("味悠长", 20, 176, 120, 206),
        )
        val blocks = BlockGrouper.group(lines)
        assertEquals(1, blocks.size)
        assertEquals("采用进口优质原料，精心酿造，口感醇厚绵柔，回味悠长", blocks[0].text)
        assertEquals(IRect(20, 100, 500, 206), blocks[0].rect)
        assertEquals(3, blocks[0].lines.size)
    }

    @Test fun keepsHeadlineSeparateFromBodyText() {
        val lines = listOf(
            line("年货节大促", 100, 40, 650, 140),          // 100px tall headline
            line("全场满199减20，叠加优惠券", 120, 150, 630, 180), // 30px body
        )
        val blocks = BlockGrouper.group(lines)
        assertEquals(2, blocks.size)
        assertEquals("年货节大促", blocks[0].text)
        assertEquals("全场满199减20，叠加优惠券", blocks[1].text)
    }

    @Test fun doesNotMergeDistantLines() {
        val lines = listOf(
            line("正品保障", 20, 100, 200, 130),
            line("七天无理由退货", 20, 200, 260, 230), // gap 70 > 0.8 * 30
        )
        assertEquals(2, BlockGrouper.group(lines).size)
    }

    @Test fun doesNotMergeSideBySideLabels() {
        val lines = listOf(
            line("包邮", 20, 100, 80, 130),
            line("正品", 200, 102, 260, 132),
        )
        assertEquals(2, BlockGrouper.group(lines).size)
    }

    @Test fun keepsTwoColumnsApart() {
        val lines = listOf(
            line("左边第一行文字", 20, 100, 300, 130),
            line("右边第一行文字", 420, 100, 700, 130),
            line("左边第二行文字", 20, 136, 300, 166),
            line("右边第二行文字", 420, 136, 700, 166),
        )
        val blocks = BlockGrouper.group(lines)
        assertEquals(2, blocks.size)
        assertEquals("左边第一行文字左边第二行文字", blocks[0].text)
        assertEquals("右边第一行文字右边第二行文字", blocks[1].text)
    }

    @Test fun mergesCentredLinesOfDifferentWidth() {
        val lines = listOf(
            line("限时秒杀 爆款直降", 200, 100, 550, 140),
            line("低至5折", 300, 146, 450, 186),
        )
        val blocks = BlockGrouper.group(lines)
        assertEquals(1, blocks.size)
        assertEquals("限时秒杀爆款直降低至5折", blocks[0].text)
    }

    @Test fun doesNotMergeShortOffsetLabelUnderTitle() {
        // A small tag tucked under the right end of a title, not aligned with it.
        val lines = listOf(
            line("茅台飞天53度酱香型白酒", 20, 100, 600, 130),
            line("自营", 560, 136, 640, 166),
        )
        assertEquals(2, BlockGrouper.group(lines).size)
    }

    @Test fun joinsLatinWordsWithSpace() {
        val lines = listOf(
            line("适用于 iPhone", 20, 100, 400, 130),
            line("15 Pro Max 手机壳", 20, 136, 400, 166),
        )
        assertEquals("适用于 iPhone 15 Pro Max 手机壳", BlockGrouper.group(lines)[0].text)
    }

    @Test fun doesNotMergeDifferentAngles() {
        val lines = listOf(
            line("斜体标签", 20, 100, 300, 130, angle = 0f),
            line("另一行文字", 20, 136, 300, 166, angle = 20f),
        )
        assertEquals(2, BlockGrouper.group(lines).size)
    }

    @Test fun cleanTextRemovesSpacesBetweenHan() {
        assertEquals("全场 5折包邮", BlockGrouper.cleanText(" 全 场 5折  包 邮 "))
        assertEquals("¥99包邮", BlockGrouper.cleanText("¥99包 邮"))
        assertEquals("iPhone 15", BlockGrouper.cleanText("iPhone   15"))
    }

    @Test fun hasHanDetectsMixedLines() {
        assertTrue(BlockGrouper.hasHan("全场5折"))
        assertTrue(BlockGrouper.hasHan("¥99 包邮"))
        assertFalse(BlockGrouper.hasHan("JD.COM 2024"))
        assertFalse(BlockGrouper.hasHan("¥99"))
    }

    @Test fun dedupeDropsClippedCopyFromTileOverlap() {
        val full = line("商品详情介绍", 50, 1450, 400, 1490)
        val clipped = line("商品详情", 50, 1450, 300, 1480)
        val other = line("其他文字", 50, 1600, 300, 1640)
        val out = BlockGrouper.dedupe(listOf(clipped, other, full))
        assertEquals(listOf(full, other), out)
    }

    @Test fun dedupeKeepsAdjacentNonOverlappingLines() {
        val a = line("第一行", 50, 100, 300, 130)
        val b = line("第二行", 50, 131, 300, 161)
        assertEquals(2, BlockGrouper.dedupe(listOf(a, b)).size)
    }

    @Test fun emptyInput() {
        assertTrue(BlockGrouper.group(emptyList()).isEmpty())
        assertTrue(BlockGrouper.dedupe(emptyList()).isEmpty())
    }

    @Test fun blocksAreSortedTopToBottom() {
        val lines = listOf(
            line("底部文字", 20, 500, 200, 530),
            line("顶部文字", 20, 10, 200, 40),
        )
        val blocks = BlockGrouper.group(lines)
        assertEquals("顶部文字", blocks[0].text)
        assertEquals("底部文字", blocks[1].text)
    }
}
