package com.jdenglish.app.translate

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pattern rules for short strings with numbers/prices/dates etc.
 * Pure Kotlin, no Android deps (unit-testable on JVM).
 * OWNER: Lexicon.
 *
 * Only whole strings (after trim / whitespace collapse) of at most [MAX_LEN] chars are
 * considered, so long product titles always fall through to the MT backend.
 *
 * Resolution order inside [apply]:
 *  1. specific regex rules (sold counts, reviews, prices, promos, dates, delivery, ...)
 *  2. compositional fallback: glossary term + number/price/qty ("运费¥6" -> "Shipping ¥6")
 *  3. multi-part: a string made of several separated pieces ("满199减20，满299减40",
 *     "现货 24小时内发货") where every piece is resolvable by glossary or rules.
 */
class RuleEngine(private val glossaryLookup: (String) -> String?) {

    /** Returns natural English, or null if no rule matches the whole string. */
    fun apply(zh: String): String? {
        val norm = normalize(zh)
        if (norm.isEmpty() || norm.length > MAX_LEN || !Cjk.hasHan(norm)) return null
        return try {
            single(norm) ?: multiPart(norm)
        } catch (_: RuntimeException) {
            null
        }
    }

    // ------------------------------------------------------------------ core

    private class Rule(pattern: String, val fn: (List<String>) -> String?) {
        val re = Regex(pattern)
    }

    private val rules = ArrayList<Rule>()

    private fun rule(pattern: String, fn: (List<String>) -> String?) {
        rules += Rule(pattern, fn)
    }

    private fun single(norm: String): String? {
        val compact = norm.replace(WS, "")
        if (compact.isEmpty()) return null
        for (r in rules) {
            val m = r.re.matchEntire(compact) ?: continue
            val out = r.fn(m.groupValues) ?: continue
            return out
        }
        compositional(compact)?.let { return it }
        // "(仅剩3件)" / "【满199减20】" -> translate the inside, keep the brackets.
        BRACKETED.matchEntire(compact)?.let { m ->
            val inner = piece(m.groupValues[2]) ?: return@let
            if (!Cjk.hasHan(m.groupValues[2])) return@let
            return "($inner)"
        }
        // "1699(需预约)" / "黑色(现货)" -> "1699 (Reservation Required)"
        PAREN_TAIL.matchEntire(norm)?.let { m ->
            val head = piece(m.groupValues[1]) ?: return@let
            val tail = piece(m.groupValues[2]) ?: return@let
            if (!Cjk.hasHan(m.groupValues[1]) && !Cjk.hasHan(m.groupValues[2])) return@let
            return "$head ($tail)"
        }
        // "原箱装*2" -> "Full Case × 2"
        TERM_TIMES.matchEntire(compact)?.let { m ->
            val t = term(m.groupValues[1]) ?: return@let
            return "$t × ${m.groupValues[2]}"
        }
        return null
    }

    private fun term(zh: String): String? {
        if (zh.isEmpty()) return null
        return glossaryLookup(zh) ?: BUILTIN[zh]
    }

    /** Glossary-or-rule resolution for a fragment (used by multi-part and some composite rules). */
    private fun piece(zh: String): String? {
        val p = normalize(zh)
        if (p.isEmpty()) return ""
        if (!Cjk.hasHan(p)) return p
        return term(p) ?: single(p)
    }

    private fun multiPart(norm: String): String? {
        // Split on CJK/ASCII list punctuation (not commas inside numbers) and whitespace.
        val tokens = ArrayList<Pair<String, String>>() // (text, separator-after)
        val sb = StringBuilder()
        var i = 0
        while (i < norm.length) {
            val c = norm[i]
            val isComma = c == ',' && !(i > 0 && norm[i - 1].isDigit() && i + 1 < norm.length && norm[i + 1].isDigit())
            val sep = when {
                isComma || c == '、' || c == ';' || c == '。' -> ", "
                c == '|' || c == '｜' -> " | "
                c == ' ' -> " "
                else -> null
            }
            if (sep != null) {
                if (sb.isNotEmpty()) {
                    tokens += sb.toString() to sep
                    sb.setLength(0)
                } else if (tokens.isNotEmpty() && sep != " ") {
                    tokens[tokens.size - 1] = tokens.last().first to sep
                }
            } else sb.append(c)
            i++
        }
        if (sb.isNotEmpty()) tokens += sb.toString() to ""
        if (tokens.size < 2) return null
        val out = StringBuilder()
        var hanParts = 0
        for ((idx, t) in tokens.withIndex()) {
            val tr = piece(t.first) ?: return null
            if (Cjk.hasHan(t.first)) hanParts++
            out.append(tr)
            if (idx < tokens.size - 1) out.append(t.second)
        }
        if (hanParts == 0) return null
        return out.toString().trim()
    }

    // --------------------------------------------------------- compositional

    /** term + value, or value + term, where value is a number / price / percent / quantity. */
    private fun compositional(s: String): String? {
        VALUE_TAIL.matchEntire(s)?.let { m ->
            val vStart = m.groups[2]?.range?.first ?: 0
            val colon = vStart > 0 && s[vStart - 1] == ':'
            val head = m.groupValues[1].trimEnd(':')
            val v = value(m.groupValues[2]) ?: return@let
            val t = term(head) ?: return@let
            return if (colon) "$t: $v" else "$t $v"
        }
        // "颜色:黑色" -> "Color: Black", "尺码:XL" -> "Size: XL"
        LABEL_VALUE.matchEntire(s)?.let { m ->
            val label = term(m.groupValues[1]) ?: return@let
            val v = piece(m.groupValues[2]) ?: return@let
            return "$label: $v"
        }
        VALUE_HEAD.matchEntire(s)?.let { m ->
            val v = value(m.groupValues[1]) ?: return@let
            val t = term(m.groupValues[2]) ?: return@let
            return "$v $t"
        }
        return null
    }

    /** Renders a numeric value token: "¥6" "6元" "99+" "(12)" "15%" "1.5万" "12件" "67W". */
    private fun value(v: String): String? {
        if (v.length >= 2 && v.first() == '(' && v.last() == ')') {
            val inner = value(v.substring(1, v.length - 1)) ?: return null
            return "($inner)"
        }
        (PRICE_VALUE.matchEntire(v) ?: PRICE_VALUE2.matchEntire(v))?.let { m ->
            return yen(m.groupValues[1], m.groupValues[2])
        }
        COUNT_VALUE.matchEntire(v)?.let { m ->
            val n = (if (m.groupValues[2].isEmpty()) m.groupValues[1] else count(m.groupValues[1], m.groupValues[2])) + m.groupValues[3]
            val unit = m.groupValues[4]
            return when {
                unit.isEmpty() -> n
                unit == "%" -> "$n%"
                else -> qty(m.groupValues[1], unit, m.groupValues[2]) ?: return null
            }
        }
        if (LATIN_VALUE.matches(v)) return v
        return null
    }

    // ------------------------------------------------------------- helpers

    /** "1万" -> "10K", "1.5万" -> "15K", "100万" -> "1M", "1亿" -> "100M", "5000" -> "5,000". */
    internal fun count(num: String, unit: String = ""): String {
        val clean = num.replace(",", "")
        val base = clean.toBigDecimalOrNull() ?: return num
        val mult = when (unit) {
            "万", "w", "W" -> BigDecimal(10_000)
            "亿" -> BigDecimal(100_000_000)
            "千", "k", "K" -> BigDecimal(1_000)
            else -> BigDecimal.ONE
        }
        val v = base.multiply(mult)
        val (div, suffix) = when {
            v >= BigDecimal(1_000_000_000) -> BigDecimal(1_000_000_000) to "B"
            v >= BigDecimal(1_000_000) -> BigDecimal(1_000_000) to "M"
            v >= BigDecimal(10_000) || (unit.isNotEmpty() && v >= BigDecimal(1_000)) -> BigDecimal(1_000) to "K"
            else -> return groupDigits(clean)
        }
        val scaled = v.divide(div)
        val rounded = if (scaled >= BigDecimal(100)) scaled.setScale(0, RoundingMode.HALF_UP)
        else scaled.setScale(1, RoundingMode.HALF_UP)
        return rounded.stripTrailingZeros().toPlainString() + suffix
    }

    private fun groupDigits(n: String): String {
        val parts = n.split('.')
        val int = parts[0]
        if (int.length <= 3) return n
        val g = StringBuilder()
        for ((i, c) in int.withIndex()) {
            if (i > 0 && (int.length - i) % 3 == 0) g.append(',')
            g.append(c)
        }
        return if (parts.size > 1) "$g.${parts[1]}" else g.toString()
    }

    private fun yen(num: String, unit: String = ""): String =
        if (unit.isEmpty()) "¥" + num else "¥" + count(num, unit)

    /** "8.5" -> "15", "5" -> "50", "95" -> "5", "88" -> "12"; null if not a valid discount. */
    private fun off(f: String): String? {
        var v = f.toBigDecimalOrNull() ?: return null
        if (!f.contains('.') && v >= BigDecimal(10) && v < BigDecimal(100)) v = v.divide(BigDecimal(10))
        if (v <= BigDecimal.ZERO || v >= BigDecimal(10)) return null
        val pct = BigDecimal(100).subtract(v.multiply(BigDecimal(10))).setScale(1, RoundingMode.HALF_UP)
        return pct.stripTrailingZeros().toPlainString()
    }

    private fun isOne(n: String) = n.replace(",", "").toBigDecimalOrNull()?.compareTo(BigDecimal.ONE) == 0

    private fun plural(n: String, one: String, many: String) = if (isOne(n)) one else many

    private fun ord(n: String): String {
        val i = n.toIntOrNull() ?: return n
        val suf = if (i % 100 in 11..13) "th" else when (i % 10) {
            1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
        }
        return "$i$suf"
    }

    /** Quantity with a unit/measure word, e.g. ("500","克") -> "500g", ("2","袋") -> "2 bags". */
    private fun qty(num: String, unit: String, mag: String = ""): String? {
        val n = if (mag.isEmpty()) num else count(num, mag)
        return when (unit) {
            "克", "g" -> "${n}g"
            "千克", "公斤", "kg", "KG", "Kg" -> "${n}kg"
            "斤" -> {
                val g = num.replace(",", "").toBigDecimalOrNull()?.multiply(BigDecimal(500)) ?: return null
                if (g >= BigDecimal(1000)) g.divide(BigDecimal(1000)).stripTrailingZeros().toPlainString() + "kg"
                else g.stripTrailingZeros().toPlainString() + "g"
            }
            "毫升", "ml", "mL", "ML" -> "${n}ml"
            "升", "L" -> "${n}L"
            "毫米" -> "${n}mm"
            "厘米" -> "${n}cm"
            "米" -> "${n}m"
            "英寸", "寸" -> "$n-inch"
            "平方米", "平米", "㎡" -> "$n m²"
            "毫安", "毫安时" -> "${n}mAh"
            "瓦" -> "${n}W"
            "匹" -> "$n HP"
            "码" -> "Size $n"
            "段" -> "Stage $n"
            "岁" -> "$n ${plural(num, "yr", "yrs")}"
            "层" -> "$n-ply"
            "核" -> "$n-core"
            "倍" -> "${n}x"
            "人份" -> "Serves $n"
            "粒" -> "$n ct"
            "%" -> "$n%"
            else -> MEASURE[unit]?.let { (one, many) -> "$n ${plural(num, one, many)}" }
        }
    }

    private fun packOf(num: String, unit: String): String? {
        val word = PACK_WORD[unit] ?: return null
        return "$num-$word pack"
    }

    private fun weekday(w: String): String? = WEEKDAYS[w]

    private fun monthDay(m: String, d: String): String? {
        val mi = m.toIntOrNull() ?: return null
        val di = d.toIntOrNull() ?: return null
        if (mi !in 1..12 || di !in 1..31) return null
        return "${MONTHS[mi - 1]} $di"
    }

    /** Parses relative-day expressions used in delivery promises. Lower case result. */
    private fun whenPhrase(x: String): String? {
        if (x.isEmpty()) return null
        WHEN_RE.matchEntire(x)?.let { m ->
            val day = when (m.groupValues[1]) {
                "今天", "今日", "当日", "当天" -> "today"
                "明天", "明日", "次日" -> "tomorrow"
                "后天" -> "in 2 days"
                "" -> null
                else -> weekday(m.groupValues[1]) ?: return null
            }
            val paren = m.groupValues[2]
            val parenTxt = if (paren.isEmpty()) null else {
                weekday(paren) ?: DATE_MD.matchEntire(paren)?.let { monthDay(it.groupValues[1], it.groupValues[2]) }
                ?: return null
            }
            val time = m.groupValues[3]
            val sb = StringBuilder()
            if (day != null) sb.append(day)
            if (parenTxt != null) sb.append(" (").append(parenTxt).append(")")
            if (time.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append("by ").append(time)
            }
            return sb.toString().ifEmpty { null }
        }
        DATE_MD_FULL.matchEntire(x)?.let { m ->
            val md = monthDay(m.groupValues[1], m.groupValues[2]) ?: return null
            val wd = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { weekday(it) ?: return null }
            return if (wd != null) "$md ($wd)" else md
        }
        Regex("""(\d+)(?:个)?(?:工作)?(天|日|小时)内""").matchEntire(x)?.let { m ->
            val n = m.groupValues[1]
            return if (m.groupValues[2] == "小时") "within $n ${plural(n, "hour", "hours")}"
            else if (x.contains("工作")) "within $n business ${plural(n, "day", "days")}"
            else "within $n ${plural(n, "day", "days")}"
        }
        return null
    }

    /** Greedy longest-match segmentation of a short Han phrase into up to 3 glossary terms. */
    private fun segment(zh: String): String? {
        val out = ArrayList<String>()
        var i = 0
        while (i < zh.length) {
            var found: String? = null
            var j = minOf(zh.length, i + 8)
            while (j > i + 1 || (j > i && !Cjk.hasHan(zh.substring(i, j)))) {
                val sub = zh.substring(i, j)
                val t = if (!Cjk.hasHan(sub)) sub else term(sub)
                if (t != null) { found = t; break }
                j--
            }
            if (found == null) return null
            out += found
            if (out.size > 3) return null
            i = j
        }
        return if (out.size >= 2) out.joinToString(" ") else null
    }

    /** "小米" -> "Xiaomi", "Apple产品" -> "Apple", "富士（FUJIFILM）" -> "FUJIFILM", "Apple" -> "Apple". */
    private fun storeBrand(raw: String): String? {
        val b = raw.trim()
        if (b.isEmpty()) return null
        if (!Cjk.hasHan(b)) return b
        Regex("""(.*?)\(([A-Za-z0-9&.\- ']+)\)""").matchEntire(b)?.let { return it.groupValues[2].trim() }
        term(b)?.let { return it }
        Regex("""([A-Za-z0-9&.\- ']+)(?:产品|品牌|官方|中国)?""").matchEntire(b)?.let { return it.groupValues[1].trim() }
        Regex("""(.+?)(?:产品|品牌|官方|集团|中国)""").matchEntire(b)?.let { m -> term(m.groupValues[1])?.let { return it } }
        return segment(b)
    }

    private fun cap(s: String) = if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)

    private fun timeSpan(n: String, unit: String): String? = when (unit) {
        "秒", "秒钟" -> "$n ${plural(n, "second", "seconds")}"
        "分钟", "分" -> "$n ${plural(n, "minute", "minutes")}"
        "小时", "个小时" -> "$n ${plural(n, "hour", "hours")}"
        "天", "日" -> "$n ${plural(n, "day", "days")}"
        "周", "星期", "个星期", "个周" -> "$n ${plural(n, "week", "weeks")}"
        "个月", "月" -> "$n ${plural(n, "month", "months")}"
        "年" -> "$n ${plural(n, "year", "years")}"
        else -> null
    }

    /** Countdown "2天02:13:44" / "02:13:44" / "3小时5分" -> "2d 02:13:44" / "02:13:44" / "3h 5m". */
    private fun countdown(t: String): String? {
        Regex("""(?:(\d+)天)?(\d{1,2}:\d{2}(?::\d{2})?)""").matchEntire(t)?.let { m ->
            return (if (m.groupValues[1].isNotEmpty()) "${m.groupValues[1]}d " else "") + m.groupValues[2]
        }
        Regex("""(?:(\d+)天)?(?:(\d+)(?:小时|时))?(?:(\d+)分(?:钟)?)?(?:(\d+)秒)?""").matchEntire(t)?.let { m ->
            val units = listOf("天", "小时", "分钟", "秒")
            val present = (1..4).filter { m.groupValues[it].isNotEmpty() }
            if (present.size == 1) return timeSpan(m.groupValues[present[0]], units[present[0] - 1])
            val parts = listOf("d", "h", "m", "s").mapIndexedNotNull { i, u ->
                m.groupValues[i + 1].takeIf { it.isNotEmpty() }?.let { "$it$u" }
            }
            return if (parts.isEmpty()) null else parts.joinToString(" ")
        }
        return null
    }

    // ---------------------------------------------------------------- rules

    init {
        val N = """(\d[\d,]*(?:\.\d+)?)"""          // number
        val M = """([万亿千wWkK]?)"""                  // magnitude
        val PL = """(\+?)"""                           // optional plus
        val Y = """¥?"""                               // optional yen sign
        val YUAN = """(?:元|块)?"""
        val PRICE = """${Y}${N}${M}${YUAN}"""                  // price → groups: num, mag
        val T = """((?:\d+天)?\d{1,2}:\d{2}(?::\d{2})?|(?:\d+天)?(?:\d+(?:小时|时))?(?:\d+分(?:钟)?)?(?:\d+秒)?)"""
        val P = """(\d+(?:\.\d+)?)%"""                // percent

        // ---- Sold / purchase counts
        rule("""(?:已售|已卖|已销售|已出|售出|热销|已抢购?)${N}${M}${PL}(?:件|单|份|个|笔)?""") { g -> "${count(g[1], g[2])}${g[3]} sold" }
        rule("""(?:月销量?|月售|月销售)${N}${M}${PL}(?:件|单|份|笔)?""") { g -> "${count(g[1], g[2])}${g[3]} sold/mo" }
        rule("""(?:年销量?|年售)${N}${M}${PL}(?:件|单|份)?""") { g -> "${count(g[1], g[2])}${g[3]} sold/yr" }
        rule("""(?:总?销量|销售量)[:]?${N}${M}${PL}(?:件|单|份)?""") { g -> "${count(g[1], g[2])}${g[3]} sold" }
        rule("""${N}${M}${PL}(?:件|单)?(?:已售|已卖|售出|销量)""") { g -> "${count(g[1], g[2])}${g[3]} sold" }
        rule("""(?:近|最近)?(\d+)(?:天|日)(?:已售|售出|销量|热销)${N}${M}${PL}(?:件|单)?""") { g ->
            "${count(g[2], g[3])}${g[4]} sold in ${g[1]} ${plural(g[1], "day", "days")}"
        }
        rule("""(?:近|最近)?(\d+)(?:天|日)${N}${M}${PL}人(?:购买|已购|已买|付款)""") { g ->
            "${count(g[2], g[3])}${g[4]} bought in past ${g[1]} ${plural(g[1], "day", "days")}"
        }
        rule("""(?:已有)?${N}${M}${PL}人(?:已)?(?:付款|购买|已购|已买|买过|下单|抢购|在买|购买过)(?:过)?""") { g -> "${count(g[1], g[2])}${g[3]} bought" }
        rule("""(?:已拼|已团)${N}${M}${PL}(?:件|单|份)?""") { g -> "${count(g[1], g[2])}${g[3]} sold" }
        rule("""${N}${M}${PL}人(?:已拼|已团|拼单|在拼|正在拼)""") { g -> "${count(g[1], g[2])}${g[3]} joined" }
        rule("""${N}${M}${PL}人(?:加购|已加购)""") { g -> "${count(g[1], g[2])}${g[3]} added to cart" }
        rule("""(?:已预约|预约人数)${N}${M}${PL}(?:人)?""") { g -> "${count(g[1], g[2])}${g[3]} reserved" }
        rule("""${N}${M}${PL}人(?:已)?预约""") { g -> "${count(g[1], g[2])}${g[3]} reserved" }
        rule("""${N}${M}${PL}人(?:想要|想买)""") { g -> "${count(g[1], g[2])}${g[3]} want this" }
        rule("""${N}${M}${PL}人(?:正在看|在看|浏览中)""") { g -> "${count(g[1], g[2])}${g[3]} viewing now" }
        rule("""${N}${M}${PL}人(?:看过|浏览|浏览过)""") { g -> "${count(g[1], g[2])}${g[3]} viewed" }
        rule("""${N}${M}${PL}人(?:收藏|已收藏)""") { g -> "${count(g[1], g[2])}${g[3]} saved" }
        rule("""${N}${M}${PL}人(?:已领|领取|已领取)""") { g -> "${count(g[1], g[2])}${g[3]} claimed" }
        rule("""${N}${M}${PL}人(?:推荐)""") { g -> "${count(g[1], g[2])}${g[3]} recommend" }
        rule("""${N}${M}${PL}人(?:回购|复购)""") { g -> "${count(g[1], g[2])}${g[3]} repeat buyers" }
        rule("""${N}${M}${PL}人(?:参与|已参与)""") { g -> "${count(g[1], g[2])}${g[3]} joined" }
        rule("""(?:补贴后|国补后|补贴到手|补贴后到手|国补到手)(?:价)?[:]?${PRICE}""") { g -> "${yen(g[1], g[2])} after subsidy" }
        rule("""¥${N}([万亿千])""") { g -> yen(g[1], g[2]) }
        rule("""${P}(?:的)?(?:用户|买家|人)(?:选择|都选|选购)""") { g -> "Chosen by ${g[1]}% of buyers" }
        rule("""(\d+)人(?:拼|拼团|团购)""") { g -> "${g[1]}-person group buy" }
        rule("""(\d+)人团""") { g -> "${g[1]}-person group buy" }
        rule("""已抢${P}""") { g -> "${g[1]}% claimed" }
        rule("""已领${P}""") { g -> "${g[1]}% claimed" }
        rule("""(?:仅剩|只剩|剩余|还剩|库存仅剩)(\d+)(?:件|个|份|台|套)""") { g -> "Only ${g[1]} left" }
        rule("""(?:仅限购买|每人限购|限购|每单限购|每人限|限)(\d+)(?:件|个|份|台|套)""") { g -> "Limit ${g[1]} per customer" }
        rule("""(\d+)(?:件|个|份)起(?:购|售|订|批)?""") { g -> "Min. order: ${g[1]}" }
        rule("""(?:库存|现货|剩余库存)[:]?${N}${M}(?:件|个|台|份|套)?""") { g -> "${count(g[1], g[2])} in stock" }

        // ---- Reviews / ratings
        rule("""${N}${M}${PL}条?(?:评价|评论|买家评价|条评价)""") { g -> "${count(g[1], g[2])}${g[3]} ${plural(g[1] + g[2], "review", "reviews")}" }
        rule("""(?:商品)?(?:评价|评论)[:]?\(?${N}${M}${PL}\)?(?:条)?""") { g -> "Reviews (${count(g[1], g[2])}${g[3]})" }
        rule("""(?:好评率|好评度|满意度|好评)[:]?${P}""") { g -> "${g[1]}% positive" }
        rule("""${P}(?:好评率|好评度|好评|的?用户好评|买家好评|满意度|满意)""") { g -> "${g[1]}% positive" }
        rule("""${N}${M}${PL}人(?:评价|已评价|点评|打分)""") { g -> "${count(g[1], g[2])}${g[3]} ${plural(g[1] + g[2], "rating", "ratings")}" }
        rule("""${N}${M}${PL}(?:条)?好评""") { g -> "${count(g[1], g[2])}${g[3]} positive reviews" }
        rule("""好评\(?${N}${M}${PL}\)?""") { g -> "Positive (${count(g[1], g[2])}${g[3]})" }
        rule("""中评\(?${N}${M}${PL}\)?""") { g -> "Neutral (${count(g[1], g[2])}${g[3]})" }
        rule("""差评\(?${N}${M}${PL}\)?""") { g -> "Negative (${count(g[1], g[2])}${g[3]})" }
        rule("""(?:晒单|有图|图片|带图)\(?${N}${M}${PL}\)?""") { g -> "With Photos (${count(g[1], g[2])}${g[3]})" }
        rule("""(?:视频|有视频)\(?${N}${M}${PL}\)?""") { g -> "Videos (${count(g[1], g[2])}${g[3]})" }
        rule("""追评\(?${N}${M}${PL}\)?""") { g -> "Follow-ups (${count(g[1], g[2])}${g[3]})" }
        rule("""(?:全部评价|全部)\(?${N}${M}${PL}\)?""") { g -> "All (${count(g[1], g[2])}${g[3]})" }
        rule("""(?:查看)?(?:全部)?${N}${M}${PL}条?(?:评价|评论)(?:>)?""") { g -> "See all ${count(g[1], g[2])}${g[3]} reviews" }
        rule("""(?:查看全部|共)${N}${M}${PL}个?(?:回答|答案)""") { g -> "${count(g[1], g[2])}${g[3]} ${plural(g[1], "answer", "answers")}" }
        rule("""${N}${M}${PL}(?:个|条)(?:回答|答案)""") { g -> "${count(g[1], g[2])}${g[3]} ${plural(g[1], "answer", "answers")}" }
        rule("""${N}${M}${PL}(?:个|条)?(?:问题|提问)""") { g -> "${count(g[1], g[2])}${g[3]} ${plural(g[1], "question", "questions")}" }
        rule("""(\d(?:\.\d)?)星""") { g -> "${g[1]} ${plural(g[1], "star", "stars")}" }
        rule("""(?:评分|店铺评分|综合评分|商品评分|得分)[:]?(\d+(?:\.\d+)?)分?""") { g -> "Rating ${g[1]}" }
        rule("""(\d\.\d{1,2})分""") { g -> "Rated ${g[1]}" }
        rule("""(?:超过|高于|超)${P}(?:的)?(?:同行|同类|同行业|商家|店铺)""") { g -> "Better than ${g[1]}% of peers" }

        // ---- Followers / store counts
        rule("""(?:关注人数|关注数|关注)[:]?${N}${M}${PL}(?:人)?""") { g -> "${count(g[1], g[2])}${g[3]} followers" }
        rule("""${N}${M}${PL}(?:人)?(?:关注|已关注|人关注)""") { g -> "${count(g[1], g[2])}${g[3]} followers" }
        rule("""(?:粉丝|粉丝数)[:]?${N}${M}${PL}(?:人)?""") { g -> "${count(g[1], g[2])}${g[3]} followers" }
        rule("""${N}${M}${PL}(?:个)?粉丝""") { g -> "${count(g[1], g[2])}${g[3]} followers" }
        rule("""(?:全部商品|商品数|在售商品)?[:]?${N}${M}${PL}(?:件|个)?(?:商品|件商品)""") { g ->
            "${count(g[1], g[2])}${g[3]} ${plural(g[1] + g[2], "item", "items")}"
        }
        rule("""(?:上新|新品)${N}${M}${PL}(?:件|款)?""") { g -> "${count(g[1], g[2])}${g[3]} new" }
        rule("""(?:开店|开店时长)?(\d+)年(?:老店|店铺)""") { g -> "${g[1]}-year-old store" }

        // ---- Store names: "小米京东自营旗舰店" -> "Xiaomi JD Direct Flagship Store"
        rule("""(.{1,20}?)(京东自营旗舰店|京东自营官方旗舰店|自营旗舰店|官方旗舰店|京东自营专区|旗舰店|专卖店|专营店|官方店|自营店|品牌店|海外旗舰店|海外专营店)(?:\((\d+)\))?(已退店|已关店)?""") { g ->
            val brand = storeBrand(g[1]) ?: return@rule null
            val kind = STORE_KIND[g[2]] ?: return@rule null
            "$brand $kind" + (if (g[4].isNotEmpty()) " (Closed)" else "")
        }
        rule("""不支持(\d+)天无理由(?:退货|退换货|退换)?""") { g -> "Not eligible for ${g[1]}-day no-reason returns" }
        rule("""支持(\d+)天无理由(?:退货|退换货|退换)?""") { g -> "${g[1]}-day no-reason returns" }

        // ---- Rankings
        rule("""(?:第|No\.?|NO\.?)(\d+)名""") { g -> "No. ${g[1]}" }
        rule("""(?:排名|排行)(?:第)?(\d+)(?:名|位)?""") { g -> "Ranked #${g[1]}" }
        rule("""(.{2,14}?)(?:第|TOP|Top|top|No\.?|NO\.?)(\d+)(?:名|位)?""") { g ->
            val t = term(g[1]) ?: segment(g[1]) ?: return@rule null
            "No. ${g[2]} in $t"
        }
        rule("""(?:入选|上榜|荣登)(.{2,12})""") { g ->
            val t = term(g[1]) ?: return@rule null
            "Featured in $t"
        }

        // ---- Prices
        rule("""${PRICE}起""") { g -> "From ${yen(g[1], g[2])}" }
        rule("""(?:低至|最低|仅需|仅售|只要|只需|仅)${PRICE}""") { g -> "Only ${yen(g[1], g[2])}" }
        rule("""(?:到手价|到手|商品到手价|预估到手价|预估到手|预计到手)[:]?${PRICE}(起)?""") { g ->
            if (g[3].isNotEmpty()) "Final price from ${yen(g[1], g[2])}" else "Final price ${yen(g[1], g[2])}"
        }
        rule("""(?:券后价?|用券后|领券后|券后到手)[:]?${PRICE}(起)?""") { g ->
            (if (g[3].isNotEmpty()) "From " else "") + "${yen(g[1], g[2])} after coupon"
        }
        rule("""(?:立省|省|节省|可省|已省|共省|为您节省|共节省|为你节省)${PRICE}""") { g -> "Save ${yen(g[1], g[2])}" }
        rule("""(?:立省|省|节省|可省)${P}""") { g -> "Save ${g[1]}%" }
        rule("""(?:直降|立降|降价|已降|降|狂降|跌)${PRICE}""") { g -> "${yen(g[1], g[2])} off" }
        rule("""(?:比加入时|比加购时|较加购时|比收藏时|较收藏时)(?:降|便宜|下降|降价|降了)${PRICE}""") { g -> "${yen(g[1], g[2])} cheaper than when added" }
        rule("""(?:立减|减|优惠|下单立减|下单减|立享|再减|共减|已减|共优惠|已优惠|优惠了)${PRICE}""") { g -> "${yen(g[1], g[2])} off" }
        rule("""(?:直降|降|立减)${P}""") { g -> "${g[1]}% off" }
        rule("""(?:低至|最低|低到|折扣低至)(\d+(?:\.\d+)?)折""") { g -> off(g[1])?.let { "Up to $it% off" } }
        rule("""(\d+(?:\.\d+)?)折起""") { g -> off(g[1])?.let { "Up to $it% off" } }
        rule("""(\d+(?:\.\d+)?)折(?:优惠|特惠)?""") { g -> off(g[1])?.let { "$it% off" } }
        rule("""(?:享|立享|可享)?(\d+(?:\.\d+)?)折(?:券|优惠券)""") { g -> off(g[1])?.let { "$it% off coupon" } }
        rule("""(?:满|任选|任意|任买|购买)?(\d+)件(\d+(?:\.\d+)?)折""") { g -> off(g[2])?.let { "$it% off ${g[1]} items" } }
        rule("""第(\d+)件(\d+(?:\.\d+)?)折""") { g -> off(g[2])?.let { "${ord(g[1])} item $it% off" } }
        rule("""第(\d+)件半价""") { g -> "${ord(g[1])} item half price" }
        rule("""第(\d+)件(?:0元|免费|0\.01元|1元)""") { g -> "${ord(g[1])} item free" }
        rule("""第(\d+)件(?:立减|减)${PRICE}""") { g -> "${ord(g[1])} item ${yen(g[2], g[3])} off" }
        rule("""买(\d+)(?:件)?(?:送|赠|免)(\d+)(?:件)?""") { g -> "Buy ${g[1]}, get ${g[2]} free" }
        rule("""(?:每满|每满足)${PRICE}(?:减|立减)${PRICE}""") { g -> "${yen(g[3], g[4])} off every ${yen(g[1], g[2])}" }
        rule("""(?:领券|券|优惠券|店铺券|平台券|品类券)?满${PRICE}(?:减|立减|省)${PRICE}""") { g ->
            "${yen(g[3], g[4])} off ${yen(g[1], g[2])}+"
        }
        rule("""(?:跨店满减|跨店)[:]?(每)?满${PRICE}(?:减|立减)${PRICE}""") { g ->
            if (g[1].isNotEmpty()) "Cross-store: ${yen(g[4], g[5])} off every ${yen(g[2], g[3])}"
            else "Cross-store: ${yen(g[4], g[5])} off ${yen(g[2], g[3])}+"
        }
        rule("""满(\d+)件(?:减|立减)${PRICE}""") { g -> "${yen(g[2], g[3])} off ${g[1]}+ items" }
        rule("""满(\d+)件(\d+(?:\.\d+)?)折""") { g -> off(g[2])?.let { "$it% off ${g[1]}+ items" } }
        rule("""满${PRICE}(?:打)?(\d+(?:\.\d+)?)折""") { g -> off(g[3])?.let { "$it% off ${yen(g[1], g[2])}+" } }
        rule("""(?:满|订单满|实付满)${PRICE}(?:包邮|免运费|免邮)""") { g -> "Free shipping on ${yen(g[1], g[2])}+" }
        rule("""${PRICE}(?:包邮|免运费|免邮)""") { g ->
            val v = g[1].replace(",", "").toBigDecimalOrNull() ?: return@rule null
            if (g[2].isEmpty() && v < BigDecimal(20)) "${yen(g[1])}, free shipping" else "Free shipping over ${yen(g[1], g[2])}"
        }
        rule("""(?:满)?(\d+)件(?:包邮|免运费|免邮)""") { g -> "Free shipping on ${g[1]}+ items" }
        rule("""(?:满)?${PRICE}免(?:基础)?运费""") { g -> "Free shipping over ${yen(g[1], g[2])}" }
        rule("""${PRICE}(?:任选|任意|随心选|自选)(\d+)件""") { g -> "Any ${g[3]} for ${yen(g[1], g[2])}" }
        rule("""${PRICE}(\d+)件""") { g -> "${g[3]} for ${yen(g[1], g[2])}" }
        rule("""(\d+)件${PRICE}""") { g -> "${g[1]} for ${yen(g[2], g[3])}" }
        rule("""(?:满|实付满)${PRICE}可用""") { g -> "Min. spend ${yen(g[1], g[2])}" }
        rule("""满(\d+)件可用""") { g -> "Min. ${g[1]} items" }
        rule("""(?:领|领取|送|赠|得)${PRICE}(?:优惠券|券|红包)""") { g -> "Get ${yen(g[1], g[2])} coupon" }
        rule("""${PRICE}(?:优惠券|券|代金券|红包|礼金)""") { g -> "${yen(g[1], g[2])} coupon" }
        rule("""${PRICE}无门槛(?:券|优惠券|红包)?""") { g -> "${yen(g[1], g[2])} off, no minimum" }
        rule("""无门槛(?:减|立减)?${PRICE}""") { g -> "${yen(g[1], g[2])} off, no minimum" }
        rule("""(?:返|可返|返还|最高返|下单返)${Y}${N}(?:个)?京豆""") { g -> "${count(g[1])} JD Beans back" }
        rule("""(?:送|赠|可得|得|奖励|赚|领)?${N}(?:个)?京豆""") { g -> "${count(g[1])} JD Beans" }
        rule("""(?:返|返现|返还|返利|最高返|可返)${PRICE}""") { g -> "${yen(g[1], g[2])} back" }
        rule("""(?:定金|订金)${PRICE}(?:抵|可抵|抵扣|膨胀为|膨胀至|翻倍抵|当)${PRICE}""") { g -> "${yen(g[1], g[2])} deposit, worth ${yen(g[3], g[4])}" }
        rule("""(?:定金|订金)[:]?${PRICE}""") { g -> "Deposit ${yen(g[1], g[2])}" }
        rule("""(?:尾款|付尾款)[:]?${PRICE}""") { g -> "Balance ${yen(g[1], g[2])}" }
        rule("""(?:预售价|预售)[:]?${PRICE}""") { g -> "Pre-sale ${yen(g[1], g[2])}" }
        rule("""(?:原价|划线价|参考价|日常价|市场价|吊牌价|专柜价)[:]?${PRICE}""") { g -> "Was ${yen(g[1], g[2])}" }
        rule("""${PRICE}/(\S{1,4})""") { g ->
            val u = PER_UNIT[g[3]] ?: return@rule null
            "${yen(g[1], g[2])}/$u"
        }
        rule("""${N}${M}元""") { g -> yen(g[1], g[2]) }
        rule("""${N}${M}块钱""") { g -> yen(g[1], g[2]) }
        rule("""${Y}${N}(?:元)?[-~至到]${Y}${N}元""") { g -> "¥${g[1]}-¥${g[2]}" }
        rule("""${PRICE}(?:以下|以内|及以下)""") { g -> "Under ${yen(g[1], g[2])}" }
        rule("""${PRICE}(?:以上|及以上)""") { g -> "Over ${yen(g[1], g[2])}" }

        // ---- Installments / subsidies
        rule("""(?:最高|至高|最多|可享|享|支持)?(\d+)期免息""") { g ->
            val up = if (Regex("^(最高|至高|最多)").containsMatchIn(g[0])) "Up to " else ""
            "$up${g[1]} interest-free installments"
        }
        rule("""(?:每期|月供|月付)${PRICE}(起)?""") { g -> (if (g[3].isNotEmpty()) "From " else "") + "${yen(g[1], g[2])}/mo" }
        rule("""${PRICE}[x×*X](\d+)期""") { g -> "${yen(g[1], g[2])} × ${g[3]} installments" }
        rule("""(?:分|可分|支持分)(\d+)期""") { g -> "${g[1]} installments" }
        rule("""(?:国家补贴|国补|政府补贴|以旧换新补贴|补贴|官方补贴)(?:最高|至高)?${P}""") { g -> "${g[1]}% National Subsidy".let { if (g[0].startsWith("补贴") || g[0].startsWith("官方")) "${g[1]}% subsidy" else it } }
        rule("""(?:国家补贴|国补|政府补贴)(?:最高|至高|立减|减)?${PRICE}""") { g -> "National Subsidy ${yen(g[1], g[2])}" }
        rule("""(?:补贴|已补贴|平台补贴|官方补贴|百亿补贴)${PRICE}""") { g -> "${yen(g[1], g[2])} subsidy" }
        rule("""(?:登录|开通PLUS|开通会员)(?:最高|至高)?(?:省|可省|立省)${P}""") { g -> "Log in to save up to ${g[1]}%".let { if (g[0].startsWith("登录")) it else "Join to save up to ${g[1]}%" } }

        // ---- Quantities, units, packs
        val UNIT = UNIT_ALT
        rule("""(?:共|合计|总计|一共)(\d+)(?:件|个)(?:商品|宝贝)?""") { g -> "${g[1]} ${plural(g[1], "item", "items")}" }
        rule("""(?:共|合计|总计)(\d+)(?:种|款)(?:商品)?""") { g -> "${g[1]} ${plural(g[1], "product", "products")}" }
        rule("""(?:已选|已选择|选中)(\d+)(?:件|个|款)?(?:商品)?""") { g -> "${g[1]} selected" }
        rule("""(\d+)(?:件|个)?宝贝""") { g -> "${g[1]} ${plural(g[1], "item", "items")}" }
        rule("""(?:数量|购买数量|件数)[:]?[x×X*]?(\d+)(?:件|个)?""") { g -> "Qty: ${g[1]}" }
        rule("""(\d+)(?:色|种颜色|个颜色|色可选)(?:可选)?""") { g -> "${g[1]} ${plural(g[1], "color", "colors")}" }
        rule("""(\d+)(?:款|种款式)(?:可选)?""") { g -> "${g[1]} ${plural(g[1], "style", "styles")}" }
        rule("""(\d+)种口味(?:可选)?""") { g -> "${g[1]} ${plural(g[1], "flavor", "flavors")}" }
        rule("""(\d+)(?:种|个)?(?:规格|版本)(?:可选)?""") { g -> "${g[1]} ${plural(g[1], "option", "options")}" }
        rule("""(\d+)(?:层)(\d+)(?:抽)""") { g -> "${g[1]}-ply, ${g[2]} sheets" }
        rule("""${N}(${UNIT})[*×xX]${N}(${UNIT})?(?:装)?""") { g ->
            val a = qty(g[1], g[2]) ?: return@rule null
            val b = if (g[4].isEmpty()) g[3] else qty(g[3], g[4]) ?: return@rule null
            "$a × $b"
        }
        rule("""${N}(${UNIT})/(${UNIT})""") { g ->
            val a = qty(g[1], g[2]) ?: return@rule null
            val u = PER_UNIT[g[3]] ?: return@rule null
            "$a/$u"
        }
        rule("""${N}[-~]${N}(${UNIT})""") { g ->
            when (g[3]) {
                "岁" -> "Ages ${g[1]}-${g[2]}"
                "个月", "月" -> "${g[1]}-${g[2]} months"
                "天", "日" -> "${g[1]}-${g[2]} days"
                "码" -> "Sizes ${g[1]}-${g[2]}"
                else -> qty(g[2], g[3])?.let { q -> "${g[1]}-$q" }
            }
        }
        rule("""(\d+)(瓶|罐|听|袋|包|盒|箱|桶|支|片|只|个|枚|粒|条|卷|双|套|提|块|颗|根|节|件)装""") { g ->
            packOf(g[1], g[2])
        }
        rule("""${N}(千克|公斤|克|斤|毫升|升|kg|g|ml|L)装""") { g -> qty(g[1], g[2])?.let { "$it pack" } }
        rule("""${N}(${UNIT})""") { g -> qty(g[1], g[2]) }
        rule("""${N}${M}(${UNIT})""") { g -> qty(g[1], g[3], g[2]) }
        rule("""(?:单|一)(瓶|罐|袋|包|盒|箱|支|片|只|个|件|套|双)装""") { g -> PACK_WORD[g[1]]?.let { "Single ${cap(it)}" } }

        // ---- Relative time
        rule("""(\d+)(秒|秒钟|分钟|分|小时|个小时|天|日|周|星期|个星期|个月|月|年)前""") { g -> timeSpan(g[1], g[2])?.let { "$it ago" } }
        rule("""(\d+)(秒|秒钟|分钟|小时|个小时|天|周|星期|个月|年)(?:后|之后)""") { g -> timeSpan(g[1], g[2])?.let { "in $it" } }
        rule("""(?:昨天|昨日)(\d{1,2}:\d{2})""") { g -> "Yesterday ${g[1]}" }
        rule("""(?:今天|今日)(\d{1,2}:\d{2})""") { g -> "Today ${g[1]}" }
        rule("""(?:前天)(\d{1,2}:\d{2})""") { g -> "2 days ago ${g[1]}" }

        // ---- Dates
        rule("""(\d{4})年(\d{1,2})月(\d{1,2})[日号](\d{1,2}:\d{2}(?::\d{2})?)?""") { g ->
            monthDay(g[2], g[3])?.let { md -> "$md, ${g[1]}" + if (g[4].isNotEmpty()) " ${g[4]}" else "" }
        }
        rule("""(\d{4})年(\d{1,2})月""") { g -> g[2].toIntOrNull()?.takeIf { it in 1..12 }?.let { "${MONTHS[it - 1]} ${g[1]}" } }
        rule("""(\d{4})年""") { g -> g[1] }
        rule("""(\d{1,2})月(\d{1,2})[日号](?:\((周.|星期.)\)|(周.|星期.))?(\d{1,2}:\d{2})?""") { g ->
            val md = monthDay(g[1], g[2]) ?: return@rule null
            val wd = (g[3].ifEmpty { g[4] }).takeIf { it.isNotEmpty() }?.let { weekday(it) ?: return@rule null }
            md + (if (wd != null) " ($wd)" else "") + (if (g[5].isNotEmpty()) " ${g[5]}" else "")
        }
        rule("""(\d{1,2})月""") { g -> g[1].toIntOrNull()?.takeIf { it in 1..12 }?.let { MONTHS_LONG[it - 1] } }
        rule("""(今天|今日|明天|明日|后天)\(([^)]+)\)""") { g -> whenPhrase(g[0])?.let { cap(it) } }
        rule("""(?:有效期至|有效期到|有效至|截止|截止日期|截至|有效期截止)[:]?(\d{4}[.\-/]\d{1,2}[.\-/]\d{1,2})(?:\d{1,2}:\d{2}(?::\d{2})?)?""") { g -> "Valid until ${g[1]}" }
        rule("""(\d{4}[.\-/]\d{1,2}[.\-/]\d{1,2})(?:前|之前)?(?:有效|到期|过期)""") { g -> "Expires ${g[1]}" }
        rule("""有效期(\d+)(天|日|个月|年)""") { g -> timeSpan(g[1], g[2])?.let { "Valid for $it" } }
        rule("""(\d+)(天|小时)后(?:过期|到期|失效)""") { g -> timeSpan(g[1], g[2])?.let { "Expires in $it" } }

        // ---- Countdown
        rule("""(?:剩余|还剩|仅剩|剩)${T}""") { g -> countdown(g[1])?.let { "$it left" } }
        rule("""(?:距离?|离)(?:本场|活动|秒杀|抢购|优惠)?(?:结束|截止)(?:还剩|仅剩|还有|剩余)?${T}""") { g -> countdown(g[1])?.let { "Ends in $it" } }
        rule("""(?:距离?|离)(?:本场|活动|秒杀|抢购)?(?:开始|开抢|开售|开团)(?:还剩|仅剩|还有|剩余)?${T}""") { g -> countdown(g[1])?.let { "Starts in $it" } }
        rule("""${T}后(?:结束|截止)""") { g -> countdown(g[1])?.let { "Ends in $it" } }
        rule("""${T}后(?:开始|开抢|开售)""") { g -> countdown(g[1])?.let { "Starts in $it" } }
        rule("""(?:还剩|剩余|仅剩|剩)(\d+)天""") { g -> "${g[1]} ${plural(g[1], "day", "days")} left" }
        rule("""(\d{1,2}:\d{2})(?:开抢|开始|开售|开团)""") { g -> "Starts at ${g[1]}" }
        rule("""(\d{1,2})月(\d{1,2})[日号](\d{1,2}:\d{2})?(?:开抢|开始|开售|开团)""") { g ->
            monthDay(g[1], g[2])?.let { "Starts $it" + if (g[3].isNotEmpty()) ", ${g[3]}" else "" }
        }
        rule("""(今天|今日|明天|明日|后天)(\d{1,2}:\d{2})[-~至](\d{1,2}:\d{2})(?:送达|配送|达)""") { g ->
            whenPhrase(g[1])?.let { "Arrives $it ${g[2]}-${g[3]}" }
        }
        rule("""(?:明天|明日)(\d{1,2}:\d{2})(?:开抢|开始|开售)""") { g -> "Starts tomorrow at ${g[1]}" }
        rule("""(\d{1,2})(?:点|:00)(?:场|档)""") { g -> "${g[1]}:00 Deals" }
        rule("""(\d{1,2})(?:点)(?:开抢|开始|开售)""") { g -> "Starts at ${g[1]}:00" }
        rule("""(?:已结束|结束于)(\d{1,2}:\d{2})""") { g -> "Ended at ${g[1]}" }

        // ---- Delivery promises
        rule("""(?:今日|今天|当日)?(\d{1,2}:\d{2})(?:前|之前)(?:下单|付款|支付|完成支付)[,]?(?:预计)?(.*?)(?:送达|到达|达)""") { g ->
            val w = whenPhrase(g[2]) ?: return@rule null
            "Order by ${g[1]}, arrives $w"
        }
        rule("""(?:今日|今天|当日)?(\d{1,2}:\d{2})(?:前|之前)(?:下单|付款|支付|完成支付)[,]?(?:预计)?(.*?)发货""") { g ->
            if (g[2].isEmpty()) "Order by ${g[1]} to ship today" else whenPhrase(g[2])?.let { "Order by ${g[1]}, ships $it" }
        }
        rule("""(?:今日|今天|当日)?(\d{1,2}:\d{2})(?:前|之前)(?:下单|付款|支付)[,]?(预计)?""") { g ->
            if (g[2].isNotEmpty()) "Order by ${g[1]}, est." else "Order by ${g[1]}"
        }
        rule("""(?:预计|最快|预计最快|最快预计)(.+?)(?:送达|到达|到货|可达|达)""") { g ->
            val w = whenPhrase(g[1]) ?: return@rule null
            val fast = if (g[0].contains("最快")) "as soon as " else ""
            "Arrives $fast$w"
        }
        rule("""((?:今天|今日|明天|明日|后天|周.|星期.|\d{1,2}月\d{1,2}[日号])(?:\([^)]*\))?(?:\d{1,2}:\d{2}(?:前|之前))?)(?:送达|到达|达)""") { g ->
            whenPhrase(g[1])?.let { "Arrives $it" }
        }
        rule("""(?:预计|最快)?(.+?)发货""") { g ->
            val inner = g[1]
            Regex("""(?:下单|付款|支付|付款后|下单后|支付后)?(?:后)?(\d+)(?:个)?(小时|天|工作日|日)内""").matchEntire(inner)?.let { m ->
                val n = m.groupValues[1]
                val u = when (m.groupValues[2]) {
                    "小时" -> plural(n, "hour", "hours"); "工作日" -> plural(n, "business day", "business days")
                    else -> plural(n, "day", "days")
                }
                val after = if (Regex("^(下单|付款|支付)").containsMatchIn(inner)) " of order" else ""
                return@rule "Ships within $n $u$after"
            }
            whenPhrase(inner)?.let { "Ships $it" }
        }
        rule("""(\d+)分钟(?:送达|达|送到|极速达)""") { g -> "Delivered in ${g[1]} min" }
        rule("""(?:最快|最快约)(\d+)分钟(?:送达|达)?""") { g -> "As fast as ${g[1]} min" }
        rule("""(?:约|预计)(\d+)分钟(?:送达|达)?""") { g -> "About ${g[1]} min" }
        rule("""(?:距您|距离您?|距离)${N}(km|公里|千米|m|米)""") { g ->
            val u = if (g[2] == "m" || g[2] == "米") "m" else "km"; "${g[1]}$u away"
        }
        rule("""(\d{1,2}:\d{2})(?:前|之前)下单""") { g -> "Order by ${g[1]}" }
        rule("""(\d{1,2}:\d{2})[-~至](\d{1,2}:\d{2})(?:送达|配送)?""") { g -> "${g[1]}-${g[2]}" }

        // ---- Guarantees with a number of days/years
        rule("""(\d+)天(?:无理由退货|无理由退换货|无理由退换|无理由)""") { g -> "${g[1]}-Day No-Reason Returns" }
        rule("""(\d+)天价保""") { g -> "${g[1]}-Day Price Protection" }
        rule("""(\d+)天(?:只换不修|包换|换货)""") { g -> "${g[1]}-Day Replacement" }
        rule("""(\d+)天(?:退换|退换货|包退|包退换)""") { g -> "${g[1]}-Day Returns" }
        rule("""(?:整机|全国联保|质保|保修|官方质保|官方保修)?(\d+)(?:年|个月)(?:质保|保修|联保|全国联保|官方质保|官方保修)""") { g ->
            val u = if (g[0].contains("个月")) "Month" else "Year"; "${g[1]}-$u Warranty"
        }
        rule("""(\d+)年(?:免费)?(?:上门)?(?:换新|只换不修)""") { g -> "${g[1]}-Year Replacement" }
        rule("""(\d+)(天|年|个月)(.{2,10})""") { g ->
            val t = term(g[3]) ?: return@rule null
            val u = when (g[2]) { "天" -> "Day"; "年" -> "Year"; else -> "Month" }
            "${g[1]}-$u $t"
        }

        // ---- Pages / misc counts
        rule("""第(\d+)页""") { g -> "Page ${g[1]}" }
        rule("""第(\d+)/(\d+)页""") { g -> "Page ${g[1]} of ${g[2]}" }
        rule("""(\d+)/(\d+)页""") { g -> "Page ${g[1]} of ${g[2]}" }
        rule("""共(\d+)页""") { g -> "${g[1]} ${plural(g[1], "page", "pages")}" }
        rule("""共(\d+)(?:条|个)(?:结果|记录|商品)?""") { g -> "${g[1]} ${plural(g[1], "result", "results")}" }
        rule("""(?:找到|为您找到|搜索到)(\d+)(?:条|个|件)(?:相关)?(?:结果|商品)""") { g -> "${g[1]} results" }
        rule("""第(\d+)(?:期|季|集|章|节|卷|版|代)""") { g ->
            when (g[0].last()) {
                '期' -> "Issue ${g[1]}"; '季' -> "Season ${g[1]}"; '集' -> "Episode ${g[1]}"; '章' -> "Chapter ${g[1]}"
                '卷' -> "Vol. ${g[1]}"; '版' -> "${ord(g[1])} Edition"; '代' -> "${ord(g[1])} Gen"; else -> "Section ${g[1]}"
            }
        }
        rule("""第(\d+)(?:件|个|位|次)""") { g -> ord(g[1]) }
        rule("""(\d+)(?:人)(?:已)?(?:成团|拼成)""") { g -> "${g[1]} joined" }
        rule("""还差(\d+)人(?:成团|拼成)?""") { g -> "${g[1]} more ${plural(g[1], "person", "people")} needed" }
        rule("""(\d+)(?:条)?(?:新)?消息""") { g -> "${g[1]} ${plural(g[1], "message", "messages")}" }
        rule("""(\d+)(?:张)(?:可用)?(?:优惠券|券)?(?:可用)?""") { g -> "${g[1]} ${plural(g[1], "coupon", "coupons")}" }
        rule("""(?:可用|可领|已领)(\d+)张""") { g -> "${g[1]} ${plural(g[1], "coupon", "coupons")} available" }
        rule("""(\d+)(?:个)?(?:地址|收货地址)""") { g -> "${g[1]} ${plural(g[1], "address", "addresses")}" }
        rule("""(\d+)(?:个)?(?:订单|笔订单)""") { g -> "${g[1]} ${plural(g[1], "order", "orders")}" }
        rule("""(\d+)(?:积分)""") { g -> "${count(g[1])} points" }
        rule("""(\d+)(?:人)""") { g -> "${g[1]} ${plural(g[1], "person", "people")}" }
        rule("""(\d+(?:\.\d+)?)(?:G|GB)(?:\+|/)(\d+)(?:G|GB)(?:内存|存储)?""") { g -> "${g[1]}GB+${g[2]}GB" }
    }

    companion object {
        const val MAX_LEN = 40
        private val WS = Regex("\\s+")
        private val LABEL_VALUE = Regex("""([^:]{1,8}):(.{1,24})""")
        private val PAREN_TAIL = Regex("""(.+?)\s*\((.+)\)""")
        private val TERM_TIMES = Regex("""(.{1,12}?)[*×xX](\d{1,4})""")
        private val BRACKETED = Regex("""([(\[【〔「])(.+)[)\]】〕」]""")

        /** Full-width ASCII → half-width, ￥ → ¥, unify spaces. */
        fun normalize(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) {
                sb.append(
                    when (c) {
                        in '！'..'～' -> (c.code - 0xFEE0).toChar()
                        '￥', '￥' -> '¥'
                        '　', ' ', ' ', ' ', ' ' -> ' '
                        '—', '–', '〜' -> if (c == '〜') '~' else '-'
                        '（' -> '('
                        '）' -> ')'
                        else -> c
                    }
                )
            }
            return sb.toString().trim().replace(WS, " ")
        }

        private val STORE_KIND = mapOf(
            "京东自营旗舰店" to "JD Direct Flagship Store", "京东自营官方旗舰店" to "JD Direct Official Flagship Store",
            "自营旗舰店" to "JD Direct Flagship Store", "官方旗舰店" to "Official Flagship Store",
            "京东自营专区" to "JD Direct Zone", "旗舰店" to "Flagship Store", "专卖店" to "Authorized Store",
            "专营店" to "Specialty Store", "官方店" to "Official Store", "自营店" to "JD Direct Store",
            "品牌店" to "Brand Store", "海外旗舰店" to "Overseas Flagship Store", "海外专营店" to "Overseas Specialty Store",
        )

        private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        private val MONTHS_LONG = listOf("January", "February", "March", "April", "May", "June", "July",
            "August", "September", "October", "November", "December")

        private val WEEKDAYS: Map<String, String> = buildMap {
            val names = listOf("一" to "Mon", "二" to "Tue", "三" to "Wed", "四" to "Thu", "五" to "Fri", "六" to "Sat", "日" to "Sun", "天" to "Sun")
            for ((zh, en) in names) {
                put("周$zh", en); put("星期$zh", en); put("礼拜$zh", en)
            }
        }

        private val MEASURE: Map<String, Pair<String, String>> = mapOf(
            "件" to ("item" to "items"), "个" to ("pc" to "pcs"), "只" to ("pc" to "pcs"), "枚" to ("pc" to "pcs"),
            "片" to ("pc" to "pcs"), "支" to ("pc" to "pcs"), "颗" to ("pc" to "pcs"), "根" to ("pc" to "pcs"),
            "条" to ("pc" to "pcs"), "节" to ("pc" to "pcs"), "把" to ("pc" to "pcs"),
            "瓶" to ("bottle" to "bottles"), "罐" to ("can" to "cans"), "听" to ("can" to "cans"),
            "袋" to ("bag" to "bags"), "包" to ("pack" to "packs"), "盒" to ("box" to "boxes"),
            "箱" to ("case" to "cases"), "桶" to ("tub" to "tubs"), "提" to ("pack" to "packs"),
            "双" to ("pair" to "pairs"), "副" to ("pair" to "pairs"), "套" to ("set" to "sets"), "组" to ("set" to "sets"),
            "台" to ("unit" to "units"), "张" to ("sheet" to "sheets"), "卷" to ("roll" to "rolls"),
            "本" to ("book" to "books"), "抽" to ("sheet" to "sheets"), "份" to ("serving" to "servings"),
            "杯" to ("cup" to "cups"), "株" to ("plant" to "plants"), "次" to ("time" to "times"),
            "晚" to ("night" to "nights"), "天" to ("day" to "days"), "周" to ("week" to "weeks"),
            "个月" to ("month" to "months"), "年" to ("year" to "years"), "小时" to ("hour" to "hours"),
            "分钟" to ("min" to "min"), "期" to ("installment" to "installments"), "人" to ("person" to "people"),
            "块" to ("pc" to "pcs"),
        )

        private val PACK_WORD: Map<String, String> = mapOf(
            "瓶" to "bottle", "罐" to "can", "听" to "can", "袋" to "bag", "包" to "pack", "盒" to "box",
            "箱" to "case", "桶" to "tub", "支" to "piece", "片" to "piece", "只" to "piece", "个" to "piece",
            "枚" to "piece", "粒" to "count", "条" to "piece", "卷" to "roll", "双" to "pair", "套" to "set",
            "提" to "pack", "块" to "piece", "颗" to "piece", "根" to "piece", "节" to "piece", "件" to "piece",
        )

        private val PER_UNIT: Map<String, String> = mapOf(
            "kg" to "kg", "KG" to "kg", "千克" to "kg", "公斤" to "kg", "斤" to "500g", "克" to "g", "g" to "g",
            "100g" to "100g", "500g" to "500g", "两" to "50g",
            "件" to "item", "个" to "pc", "只" to "pc", "盒" to "box", "瓶" to "bottle", "袋" to "bag", "箱" to "case",
            "片" to "pc", "包" to "pack", "支" to "pc", "双" to "pair", "套" to "set", "台" to "unit", "罐" to "can",
            "月" to "mo", "个月" to "mo", "年" to "yr", "天" to "day", "日" to "day", "次" to "time", "人" to "person",
            "晚" to "night", "间夜" to "night", "平方米" to "m²", "平米" to "m²", "㎡" to "m²", "米" to "m", "升" to "L",
            "L" to "L", "毫升" to "ml", "ml" to "ml", "期" to "installment", "张" to "sheet", "卷" to "roll", "本" to "book",
            "粒" to "pc", "颗" to "pc", "斤装" to "500g",
        )

        private val UNIT_ALT: String = listOf(
            "千克", "公斤", "毫升", "毫米", "厘米", "英寸", "平方米", "平米", "人份", "毫安时", "毫安",
            "kg", "KG", "Kg", "ml", "mL", "ML", "g", "L", "㎡",
            "克", "斤", "升", "米", "寸", "瓦", "匹", "码", "段", "岁", "层", "核", "倍", "粒", "个月", "小时", "分钟",
        ).plus(listOf("件", "个", "只", "枚", "片", "支", "颗", "根", "条", "节", "把", "瓶", "罐", "听", "袋", "包",
            "盒", "箱", "桶", "提", "双", "副", "套", "组", "台", "张", "卷", "本", "抽", "份", "杯", "株", "次", "晚"))
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it).replace("\\Q", "").replace("\\E", "") }

        private val WHEN_RE = Regex("""(今天|今日|当日|当天|明天|明日|次日|后天|周.|星期.|)(?:\(([^)]+)\))?(?:(\d{1,2}:\d{2})(?:前|之前))?""")
        private val DATE_MD = Regex("""(\d{1,2})月(\d{1,2})[日号]?""")
        private val DATE_MD_FULL = Regex("""(\d{1,2})月(\d{1,2})[日号]?(?:\((周.|星期.)\))?""")

        private const val VAL = """(?:\(?[¥]?\d[\d,]*(?:\.\d+)?[万亿千]?\+?(?:元|%|[a-zA-Z]{1,4}|件|个|台|瓶|袋|盒|箱|包|片|支|只|套|双|克|斤|千克|公斤|毫升|升|米|厘米|英寸|寸|岁|天|小时|分钟|年|个月|人)?\)?)"""
        private val VALUE_TAIL = Regex("""(.{1,14}?)[:]?(${VAL})""")
        private val VALUE_HEAD = Regex("""(${VAL})(.{1,14})""")
        private val PRICE_VALUE = Regex("""¥(\d[\d,]*(?:\.\d+)?)([万亿千]?)元?""")
        private val PRICE_VALUE2 = Regex("""(\d[\d,]*(?:\.\d+)?)([万亿千]?)元""")
        private val COUNT_VALUE = Regex("""(\d[\d,]*(?:\.\d+)?)([万亿千]?)(\+?)(%|件|个|台|瓶|袋|盒|箱|包|片|支|只|套|双|克|斤|千克|公斤|毫升|升|米|厘米|英寸|寸|岁|天|小时|分钟|年|个月|人)?""")
        private val LATIN_VALUE = Regex("""\d+(?:\.\d+)?[a-zA-Z]{1,4}""")

        /** A few words that rules reference even when the glossary lacks them. */
        private val BUILTIN: Map<String, String> = mapOf(
            "热卖榜" to "Best Sellers", "热销榜" to "Best Sellers", "销量榜" to "Best Sellers", "好评榜" to "Top Rated",
            "人气榜" to "Most Popular", "新品榜" to "New Arrivals", "排行榜" to "Rankings", "热销" to "Best Sellers",
            "运费" to "Shipping", "库存" to "Stock", "价格" to "Price", "京东价" to "JD Price", "数量" to "Quantity",
        )
    }
}
