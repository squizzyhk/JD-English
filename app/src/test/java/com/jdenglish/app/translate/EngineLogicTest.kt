package com.jdenglish.app.translate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineLogicTest {

    private val gloss = mapOf(
        "京东自营" to "JD Direct",
        "京东物流" to "JD Logistics",
        "包邮" to "Free Shipping",
        "领券" to "Get Coupons",
        "百亿补贴" to "Big Subsidy Deals",
    )

    // --- placeholder protection ---

    @Test fun protectAndRestore() {
        val p = EngineLogic.protect("京东自营 正品保证 京东物流配送", listOf("京东自营", "京东物流")) { gloss[it] }
        assertNotNull(p)
        p!!
        assertFalse(p.text.contains("京东"))
        assertTrue(p.text.contains("QZXA") && p.text.contains("QZXB"))
        // MT may change case/spacing of the placeholders
        val restored = EngineLogic.restore("Qzxa genuine guarantee qzx b delivery", p)
        assertEquals("JD Direct genuine guarantee JD Logistics delivery", restored)
    }

    @Test fun restoreFailsIfPlaceholderLost() {
        val p = EngineLogic.protect("京东自营正品", listOf("京东自营")) { gloss[it] }!!
        assertNull(EngineLogic.restore("Genuine product", p))
    }

    @Test fun protectSkipsWhenNothingLeftOrNoTerms() {
        assertNull(EngineLogic.protect("京东自营", listOf("京东自营")) { gloss[it] })
        assertNull(EngineLogic.protect("正品保证", listOf("京东自营")) { gloss[it] })
        assertNull(EngineLogic.protect("京东自营正品", listOf("京东自营")) { null })
    }

    // --- segment composition ---

    @Test fun composeSegments() {
        val r = EngineLogic.composeSegments("京东自营 | 包邮") { gloss[it] }
        assertEquals("JD Direct | Free Shipping", r)
        assertEquals("Free Shipping, Get Coupons", EngineLogic.composeSegments("包邮，领券") { gloss[it] })
        assertNull(EngineLogic.composeSegments("包邮 未知词") { gloss[it] })
        assertNull(EngineLogic.composeSegments("包邮") { gloss[it] })
        assertEquals("Apple Free Shipping JD Direct", EngineLogic.composeSegments("Apple 包邮 京东自营") { gloss[it] })
    }

    @Test fun keepOuterSpace() {
        assertEquals(" JD ", EngineLogic.keepOuterSpace(" 京东 ", "JD"))
        assertEquals("JD", EngineLogic.keepOuterSpace("京东", "JD"))
        assertEquals("JD ", EngineLogic.keepOuterSpace("京东\n", "JD"))
    }

    @Test fun usable() {
        assertTrue(EngineLogic.isUsable("包邮", "Free Shipping"))
        assertFalse(EngineLogic.isUsable("包邮", "包邮"))
        assertFalse(EngineLogic.isUsable("包邮", "  "))
        assertFalse(EngineLogic.isUsable("这是一个很长的句子", "这是一个很长 a"))
        assertTrue(EngineLogic.isUsable("Apple 苹果", "Apple"))
    }

    // --- Claude protocol ---

    @Test fun chunking() {
        val sizes = listOf(100, 100, 5900, 50, 10)
        val chunks = ClaudeProtocol.chunk(sizes.indices.toList(), { sizes[it] }, 60, 6000)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3, 4)), chunks)
        assertEquals(listOf(listOf(0), listOf(1)), ClaudeProtocol.chunk(listOf(0, 1), { 5000 }, 60, 6000))
        val many = (0 until 130).toList()
        val c2 = ClaudeProtocol.chunk(many, { 1 }, 60, 6000)
        assertEquals(listOf(60, 60, 10), c2.map { it.size })
    }

    @Test fun parseArrayRobust() {
        assertEquals(listOf("A", "B"), ClaudeProtocol.parseArray("[\"A\",\"B\"]"))
        assertEquals(listOf("Add to Cart", "Free Shipping"),
            ClaudeProtocol.parseArray("```json\n[\"Add to Cart\", \"Free Shipping\"]\n```"))
        assertEquals(listOf("x [1]", null), ClaudeProtocol.parseArray("Here you go: [\"x [1]\", null] done"))
        assertNull(ClaudeProtocol.parseArray("no json here"))
        assertNull(ClaudeProtocol.parseArray("[\"unterminated"))
    }

    @Test fun extractTextSkipsThinking() {
        val resp = JSONObject("""{"content":[{"type":"thinking","thinking":"","signature":"x"},{"type":"text","text":"[\"Hi\"]"}],"stop_reason":"end_turn"}""")
        assertEquals("[\"Hi\"]", ClaudeProtocol.extractText(resp))
    }

    @Test fun effortSupport() {
        assertTrue(ClaudeProtocol.supportsEffort("claude-haiku-5-5"))
        assertTrue(ClaudeProtocol.supportsEffort("claude-opus-5-5"))
        assertTrue(ClaudeProtocol.supportsEffort("claude-sonnet-4-6"))
        assertTrue(ClaudeProtocol.supportsEffort("claude-opus-4-5"))
        assertFalse(ClaudeProtocol.supportsEffort("claude-haiku-4-5"))
        assertFalse(ClaudeProtocol.supportsEffort("claude-sonnet-4-5-20250929"))
        assertFalse(ClaudeProtocol.supportsEffort("something-else"))
    }

    @Test fun requestBodyShape() {
        val body = JSONObject(ClaudeProtocol.buildRequestBody("claude-haiku-5-5", "sys", "user", 2048, true))
        assertEquals("claude-haiku-5-5", body.getString("model"))
        assertEquals(2048, body.getInt("max_tokens"))
        assertEquals("sys", body.getString("system"))
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        assertFalse(body.has("temperature"))
        val noEffort = JSONObject(ClaudeProtocol.buildRequestBody("claude-haiku-4-5", "s", "u", 100, false))
        assertFalse(noEffort.has("output_config"))
    }

    @Test fun hintsAndUserMessage() {
        val hints = ClaudeProtocol.glossaryHints(listOf("京东自营 苹果手机 包邮"), gloss, 80)
        assertEquals(listOf("京东自营" to "JD Direct", "包邮" to "Free Shipping"), hints)
        assertEquals(1, ClaudeProtocol.glossaryHints(listOf("京东自营 包邮"), gloss, 1).size)
        val msg = ClaudeProtocol.buildUserMessage(listOf(TextItem("包邮", "ui"), TextItem("华为手机", "weird")), hints)
        assertTrue(msg.contains("包邮 = Free Shipping"))
        assertTrue(msg.contains("\"type\":\"ui\""))
        assertFalse(msg.contains("weird"))
        assertTrue(msg.contains("exactly 2"))
    }

    @Test fun cleanQuery() {
        assertEquals("华为 Mate 60", ClaudeProtocol.cleanQuery("\"华为 Mate 60\"\n"))
        assertEquals("蓝牙耳机", ClaudeProtocol.cleanQuery("Query: 蓝牙耳机"))
        assertNull(ClaudeProtocol.cleanQuery("   "))
    }

    // --- Glossary parsing ---

    @Test fun glossaryParseMergeAndTolerance() {
        val warnings = ArrayList<String>()
        val map = Glossary.parseFiles(
            listOf(
                "00_base.json" to """{"加入购物车":"Add to Cart","  领  券 ":"Get Coupons","n":5}""",
                "10_bad.json" to """{not json""",
                "20_override.json" to "﻿{\"加入购物车\":\"Add to Cart \",\"包邮\":\"Free Shipping\"}",
            )
        ) { warnings.add(it) }
        assertEquals("Add to Cart", map["加入购物车"])
        assertEquals("Get Coupons", map["领 券"])
        assertEquals("Free Shipping", map["包邮"])
        assertFalse(map.containsKey("n"))
        assertEquals(1, warnings.size)
        val g = Glossary(map)
        assertEquals("Get Coupons", g.lookup(" 领   券"))
        assertEquals("加入购物车", g.reverseLookup("add to cart"))
        assertNull(g.reverseLookup("nothing"))
    }

    @Test fun protectedParsing() {
        val prot = Glossary.parseProtected("# brand terms\n京东自营\n包邮 # free\n京\nUnknown\n百亿补贴\n京东自营\n", gloss)
        assertEquals(listOf("京东自营", "百亿补贴", "包邮"), prot)
        assertEquals(emptyList<String>(), Glossary.parseProtected(null, gloss))
    }
}
