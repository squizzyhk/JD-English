package com.jdenglish.app.translate

/**
 * Deterministic clean-up of machine-translation output for the JD shopping domain.
 * Pure Kotlin (no Android deps) so it is unit-testable on the JVM.
 *
 * [clean] applies context-independent fixes (terminology, punctuation, spacing) and is what
 * gets cached; [finish] applies context-dependent presentation (capitalization, trailing
 * periods, UI coupon wording). [edit] = finish(clean(x)).
 */
object PostEditor {

    private val UI_CTX = setOf("ui", "image")
    private val LABEL_CTX = setOf("ui", "title", "attr", "image")

    private class Fix(pattern: String, val replacement: String, ignoreCase: Boolean = true) {
        val regex = if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
    }

    /** Order matters: more specific phrases first. */
    private val TERM_FIXES: List<Fix> = listOf(
        // JD Beans (京豆) before the generic Jingdong -> JD rule.
        Fix("""\b(?:Jingdong|JD|Beijing[\s-]East)\s+beans?\b""", "JD Beans"),
        Fix("""\bJing\s?dou\b""", "JD Beans"),
        Fix("""\bJing\s+beans?\b""", "JD Beans"),
        // JD brand
        Fix("""\b(?:Jingdong|JD\.com|JD)\s+Mall\b""", "JD"),
        Fix("""\bJing\s?dong\b""", "JD"),
        Fix("""\bBeijing[\s-]East\b""", "JD"),
        Fix("""\bJd\b""", "JD", ignoreCase = false),
        // JD Direct (京东自营)
        Fix("""\bJD\s+(?:self[\s-]?(?:operated|operation|run|employed|support|managed)|proprietary)\b""", "JD Direct"),
        Fix("""\bself[\s-]?(?:operated|run|employed)\s+(stores?|shops?|products?|items?|goods)\b""", "JD Direct $1"),
        Fix("""\bself[\s-]?(?:operated|run|employed)\b""", "JD Direct"),
        Fix("""\bJD\s+JD\s+Direct\b""", "JD Direct"),
        Fix("""\bJD\s+JD\b""", "JD"),
        // PLUS membership
        Fix("""\bplus\s+membership\b""", "PLUS Membership"),
        Fix("""\bplus\s+members\b""", "PLUS Members"),
        Fix("""\bplus\s+member\b""", "PLUS Member"),
        Fix("""\bplus\s+(?:vip|exclusive)\s+price\b""", "PLUS Price"),
        // Baitiao (白条)
        Fix("""\b(?:JD\s+)?(?:bai\s?tiao|white\s+(?:bars?|strips?|notes?))\b(?!\s*\(credit\))""", "JD Baitiao (credit)"),
        // 百亿补贴
        Fix("""\b(?:(?:ten|10|one\s+hundred|hundred|100)\s+billion|tens\s+of\s+billions(?:\s+of)?|billions\s+of|10\s+billion\s+yuan)\s+(?:yuan\s+)?subsid(?:y|ies)(?:\s+deals?)?\b""", "Big Subsidy Deals"),
        // 包邮
        Fix("""\b(?:packages?\s+(?:mail|post|postage)|bao\s?mail|bao\s?you|parcel\s+post|free\s+of\s+postage|postage\s+included|including\s+postage)\b""", "Free Shipping"),
        // 秒杀
        Fix("""\b(?:JD\s+)?(?:seconds?\s+kill|sec[\s-]?kill|spike\s+sale)\b""", "Flash Sale"),
        // Misc literalisms
        Fix("""\bgenuine\s+products?\s+guarantee\b""", "100% Authentic"),
        Fix("""\bseven\s+days\s+no\s+reason\s+(?:to\s+)?return\b""", "7-day no-reason returns"),
        Fix("""\b7\s+days\s+no\s+reason\s+(?:to\s+)?returns?\b""", "7-day no-reason returns"),
        Fix("""\bshopping\s+cart\b""", "cart"),
    )

    private val FULLWIDTH: List<Pair<Regex, String>> = listOf(
        Regex("(?<=\\d)：(?=\\d)") to ":",
        Regex("，") to ", ",
        Regex("；") to "; ",
        Regex("：") to ": ",
        Regex("（") to " (",
        Regex("）") to ") ",
        Regex("！") to "! ",
        Regex("？") to "? ",
        Regex("、") to ", ",
        Regex("。") to ". ",
        Regex("[【［]") to " [",
        Regex("[】］]") to "] ",
        Regex("[“”〝〞＂]") to "\"",
        Regex("[‘’＇]") to "'",
        Regex("[「『]") to " \"",
        Regex("[」』]") to "\" ",
        Regex("《") to " \"",
        Regex("》") to "\" ",
        Regex("～") to "~",
        Regex("—{2}|──") to " - ",
        Regex("…+") to "...",
        Regex("[％]") to "%",
        Regex("＋") to "+",
        Regex("／") to "/",
        Regex("[　\\u00A0]") to " ",
        Regex("￥") to "¥",
    )

    private val YUAN = Regex("""(\d[\d,]*(?:\.\d+)?)\s*(?:yuan|RMB)\b""", RegexOption.IGNORE_CASE)
    private val YUAN_PREFIX = Regex("""\b(?:RMB|CNY)\s*(\d[\d,]*(?:\.\d+)?)""")
    private val YEN_SPACE = Regex("""¥\s+(?=\d)""")
    private val ONE_PIECES = Regex("""\b(1|one)\s+(piece|item|unit|box|bottle|bag|pack|pair)s\b""", RegexOption.IGNORE_CASE)
    private val N_PIECE = Regex("""\b(\d*[02-9]|\d{2,})\s+(piece|item|unit|bottle|bag|pack|pair)\b(?!s)""", RegexOption.IGNORE_CASE)
    private val N_BOX = Regex("""\b(\d*[02-9]|\d{2,})\s+box\b""", RegexOption.IGNORE_CASE)
    private val MULTI_SPACE = Regex("[ \\t]{2,}")
    private val SPACE_BEFORE_PUNCT = Regex("""[ \t]+([,;:!?.%)\]])""")
    private val SPACE_AFTER_OPEN = Regex("""([(\[])[ \t]+""")
    private val DUP_PUNCT = Regex("""([,;:])\s*\1+""")
    private val COMMA_BEFORE_END = Regex("""[,;:]\s*$""")
    private val EMPTY_PARENS = Regex("""\(\s*\)""")
    private val SPACE_AFTER_PUNCT = Regex("""([,;!?])(?=[A-Za-z¥])""")
    private val DUP_WORD = Regex("""\b(JD|Free Shipping|Flash Sale|JD Direct)(\s+\1\b)+""")

    private val ABBREV_END = Regex("""\b(?:Ltd|Inc|Co|Corp|etc|No|vs|St|Jr|Sr|Mr|Mrs|Dr)\.$""", RegexOption.IGNORE_CASE)
    private val SMALL_WORDS = setOf("a", "an", "the", "and", "or", "nor", "but", "of", "to", "in", "on", "at", "by", "for", "with", "per", "via", "vs", "from", "as")

    private val UI_COUPON: List<Fix> = listOf(
        Fix("""^(?:get|collect|receive|claim|take)\s+(?:the\s+)?(?:tickets?|vouchers?|coupons?)$""", "Get Coupons"),
        Fix("""^(?:coupons?|tickets?)\s+(?:collection|receive|redemption|claim)$""", "Get Coupons"),
        Fix("""^(?:tickets?)$""", "Coupons"),
        Fix("""^spike$""", "Flash Sale"),
        Fix("""^add\s+to\s+(?:the\s+)?(?:shopping\s+)?cart$""", "Add to Cart"),
        Fix("""^(?:buy\s+it\s+now|immediately\s+buy|buy\s+immediately|purchase\s+immediately)$""", "Buy Now"),
        Fix("""^(?:customer\s+service|contact\s+customer\s+service|online\s+customer\s+service)$""", "Customer Service"),
        Fix("""^(?:enter\s+the\s+store|into\s+the\s+store|go\s+to\s+the\s+store)$""", "Visit Store"),
    )

    /** Full post-edit for a given context. */
    fun edit(text: String, ctx: String = "ui", source: String? = null): String = finish(clean(text), ctx, source)

    /** Context-independent fixes: terminology, full-width punctuation, spacing, units. */
    fun clean(text: String): String {
        if (text.isEmpty()) return text
        var s = text
        for ((re, rep) in FULLWIDTH) s = re.replace(s, rep)
        for (f in TERM_FIXES) s = f.regex.replace(s, f.replacement)
        s = YUAN_PREFIX.replace(s) { "¥" + it.groupValues[1] }
        s = YUAN.replace(s) { "¥" + it.groupValues[1] }
        s = YEN_SPACE.replace(s, "¥")
        s = ONE_PIECES.replace(s) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        s = N_PIECE.replace(s) { "${it.groupValues[1]} ${it.groupValues[2]}s" }
        s = N_BOX.replace(s) { "${it.groupValues[1]} boxes" }
        s = DUP_WORD.replace(s) { it.groupValues[1] }
        s = tidySpacing(s)
        return s
    }

    /** Context-dependent finishing: coupon wording in UI, capitalization, trailing period. */
    fun finish(text: String, ctx: String, source: String? = null): String {
        var s = text.trim()
        if (s.isEmpty()) return s
        val looksLikeSentence = s.last() in ".!?" || source?.trimEnd()?.lastOrNull()?.let { it in "。！？.!?" } == true
        if (ctx in UI_CTX) {
            for (f in UI_COUPON) s = f.regex.replace(s, f.replacement)
        }
        if (ctx in LABEL_CTX || source?.trimEnd()?.endsWith("。") == true) {
            s = stripTrailingPeriod(s, ctx, source)
        }
        if (ctx in LABEL_CTX || ctx == "review" || ctx == "product") s = capitalizeFirst(s)
        if (ctx == "ui" && !looksLikeSentence) s = titleCaseShortLabel(s)
        return s
    }

    private fun tidySpacing(input: String): String {
        var s = input
        s = EMPTY_PARENS.replace(s, "")
        s = SPACE_BEFORE_PUNCT.replace(s, "$1")
        s = SPACE_AFTER_OPEN.replace(s, "$1")
        s = DUP_PUNCT.replace(s, "$1")
        s = SPACE_AFTER_PUNCT.replace(s, "$1 ")
        s = MULTI_SPACE.replace(s, " ")
        s = s.lines().joinToString("\n") { it.trim() }.trim()
        s = COMMA_BEFORE_END.replace(s, "")
        return s
    }

    private fun stripTrailingPeriod(s: String, ctx: String, source: String?): String {
        if (!s.endsWith(".") || s.endsWith("..")) return s
        if (ABBREV_END.containsMatchIn(s)) return s
        val body = s.dropLast(1)
        // Multi-sentence output keeps its final period unless the source clearly didn't have one.
        val multiSentence = Regex("""[.!?]\s+\S""").containsMatchIn(body)
        if (multiSentence && ctx !in LABEL_CTX) return s
        if (multiSentence && source != null && source.trimEnd().endsWith("。")) return s
        return body.trimEnd()
    }

    private fun capitalizeFirst(s: String): String {
        val idx = s.indexOfFirst { it.isLetterOrDigit() }
        if (idx < 0 || !s[idx].isLetter()) return s
        val c = s[idx]
        if (!c.isLowerCase()) return s
        // Respect brand casing like iPhone, eBay, iOS, mAh, kg, ml.
        val word = s.substring(idx).takeWhile { it.isLetterOrDigit() }
        if (word.drop(1).any { it.isUpperCase() }) return s
        if (word in UNIT_WORDS) return s
        return s.substring(0, idx) + c.uppercaseChar() + s.substring(idx + 1)
    }

    /** Short UI strings starting with these are messages, not labels: keep sentence case. */
    private val MESSAGE_START = setOf(
        "please", "no", "not", "you", "your", "there", "this", "it", "is", "are", "sorry", "failed", "unable",
        "cannot", "can't", "don't", "network", "loading", "we", "i", "my", "already", "only", "currently", "nothing",
    )

    private val UNIT_WORDS = setOf("kg", "g", "mg", "ml", "l", "cm", "mm", "m", "km", "mah", "w", "kw", "v", "x", "pcs")

    /** "view more" -> "View More" for short UI labels (<= 4 words, no sentence punctuation, no digits). */
    private fun titleCaseShortLabel(s: String): String {
        if (s.length > 40 || s.any { it.isDigit() } || s.any { it in ".!?,;:\"\n" }) return s
        val words = s.split(' ')
        if (words.size > 4 || words.size < 2) return s
        if (words.first().lowercase() in MESSAGE_START) return s
        if (words.any { w -> w.isEmpty() || w.any { !it.isLetter() && it != '-' && it != '\'' && it != '&' && it != '(' && it != ')' } }) return s
        return words.mapIndexed { i, w ->
            val lower = w.lowercase()
            when {
                w.drop(1).any { it.isUpperCase() } -> w // PLUS, JD, iPhone
                i != 0 && i != words.lastIndex && lower in SMALL_WORDS -> lower
                else -> w.replaceFirstChar { it.uppercaseChar() }
            }
        }.joinToString(" ")
    }
}
