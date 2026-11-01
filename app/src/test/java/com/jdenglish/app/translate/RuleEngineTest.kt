package com.jdenglish.app.translate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuleEngineTest {

    private val glossary = mapOf(
        "运费" to "Shipping",
        "库存" to "Stock",
        "热卖榜" to "Best Sellers",
        "手机热卖榜" to "Phone Best Sellers",
        "京东价" to "JD Price",
        "PLUS价" to "PLUS Price",
        "问大家" to "Q&A",
        "去结算" to "Checkout",
        "购物车" to "Cart",
        "现货" to "In Stock",
        "快充" to "Fast Charging",
        "意外换新" to "Accidental Damage Replacement",
        "碎屏换新" to "Screen Damage Replacement",
        "床" to "Bed",
        "国家补贴" to "National Subsidy",
        "包邮" to "Free Shipping",
        "京东自营" to "JD Direct",
        "黑色" to "Black",
        "小米" to "Xiaomi",
        "纪念日" to "Anniversary",
        "白酒" to "Baijiu",
        "需预约" to "Reservation Required",
        "单瓶装" to "Single Bottle",
        "原箱装" to "Full Case",
        "颜色" to "Color",
        "尺码" to "Size",
    )
    private val engine = RuleEngine { glossary[Glossary.normalize(it)] }

    private fun t(zh: String, en: String) = assertEquals("input: $zh", en, engine.apply(zh))
    private fun none(zh: String) = assertNull("input: $zh", engine.apply(zh))

    // ---------------------------------------------------------------- sold counts
    @Test fun soldWan() = t("已售1万+", "10K+ sold")
    @Test fun soldSpace() = t("已售 5000+", "5,000+ sold")
    @Test fun soldMonthly() = t("月销10万+", "100K+ sold/mo")
    @Test fun soldMonthlyPlain() = t("月销2000+", "2,000+ sold/mo")
    @Test fun salesVolume() = t("销量2.3万", "23K sold")
    @Test fun paidCount() = t("1000+人付款", "1,000+ bought")
    @Test fun boughtCount() = t("50万+人已购", "500K+ bought")
    @Test fun groupBought() = t("已拼3.2万件", "32K sold")
    @Test fun soldYi() = t("已售1亿+", "100M+ sold")
    @Test fun soldMillion() = t("已售100万+", "1M+ sold")
    @Test fun soldFullWidthPlus() = t("已售１万＋", "10K+ sold")
    @Test fun soldInDays() = t("近30天售出1万+", "10K+ sold in 30 days")
    @Test fun reserved() = t("1732人已预约", "1,732 reserved")
    @Test fun viewingNow() = t("3人正在看", "3 viewing now")
    @Test fun onlyLeft() = t("（仅剩3件）", "(Only 3 left)")
    @Test fun onlyLeftPlain() = t("仅剩3件", "Only 3 left")
    @Test fun bracketPromo() = t("【满199减20】", "(¥20 off ¥199+)")
    @Test fun limit() = t("（仅限购买1件）", "(Limit 1 per customer)")

    // ---------------------------------------------------------------- reviews
    @Test fun reviewsWan() = t("1万+条评价", "10K+ reviews")
    @Test fun reviewsTab() = t("评价 99+", "Reviews (99+)")
    @Test fun positiveRate() = t("好评率98%", "98% positive")
    @Test fun positiveDegree() = t("好评度96%", "96% positive")
    @Test fun ratingsCount() = t("5000+人评价", "5,000+ ratings")
    @Test fun hundredPercentPositive() = t("100%好评", "100% positive")
    @Test fun positiveCount() = t("2万+好评", "20K+ positive reviews")
    @Test fun positiveTab() = t("好评(120)", "Positive (120)")
    @Test fun negativeTab() = t("差评 3", "Negative (3)")
    @Test fun photosTab() = t("有图(56)", "With Photos (56)")
    @Test fun answers() = t("共5个回答", "5 answers")

    // ---------------------------------------------------------------- prices
    @Test fun priceFrom() = t("¥99.9起", "From ¥99.9")
    @Test fun finalPrice() = t("到手价¥88", "Final price ¥88")
    @Test fun afterCoupon() = t("券后¥79", "¥79 after coupon")
    @Test fun save() = t("省¥20", "Save ¥20")
    @Test fun dropYuan() = t("直降100元", "¥100 off")
    @Test fun instantOff() = t("立减50元", "¥50 off")
    @Test fun upToOff() = t("低至5折", "Up to 50% off")
    @Test fun discount85() = t("8.5折", "15% off")
    @Test fun discount95() = t("95折", "5% off")
    @Test fun twoItems80() = t("2件8折", "20% off 2 items")
    @Test fun secondHalf() = t("第2件半价", "2nd item half price")
    @Test fun spendSave() = t("满199减20", "¥20 off ¥199+")
    @Test fun spendItemsSave() = t("满2件减10", "¥10 off 2+ items")
    @Test fun everySpend() = t("每满300减50", "¥50 off every ¥300")
    @Test fun freeShipOver() = t("满99包邮", "Free shipping on ¥99+")
    @Test fun yuanFreeShip() = t("99元包邮", "Free shipping over ¥99")
    @Test fun cheapFreeShip() = t("9.9元包邮", "¥9.9, free shipping")
    @Test fun itemsFreeShip() = t("3件包邮", "Free shipping on 3+ items")
    @Test fun bundlePrice() = t("199元3件", "3 for ¥199")
    @Test fun couponSpend() = t("领券满300减30", "¥30 off ¥300+")
    @Test fun beansBack() = t("返10京豆", "10 JD Beans back")
    @Test fun deposit() = t("定金¥50抵¥100", "¥50 deposit, worth ¥100")
    @Test fun perKg() = t("12.8元/kg", "¥12.8/kg")
    @Test fun perJin() = t("¥6.5/斤", "¥6.5/500g")
    @Test fun priceRange() = t("10-20元", "¥10-¥20")
    @Test fun underPrice() = t("100元以下", "Under ¥100")
    @Test fun wasPrice() = t("原价¥299", "Was ¥299")
    @Test fun multiTier() = t("满199减20，满299减40", "¥20 off ¥199+, ¥40 off ¥299+")
    @Test fun interestFree() = t("最高12期免息", "Up to 12 interest-free installments")
    @Test fun installments() = t("¥88×12期", "¥88 × 12 installments")
    @Test fun freeBaseShipping() = t("59元免基础运费", "Free shipping over ¥59")
    @Test fun minSpend() = t("满100可用", "Min. spend ¥100")
    @Test fun couponAmount() = t("5元无门槛", "¥5 off, no minimum")
    @Test fun buyGet() = t("买2送1", "Buy 2, get 1 free")
    @Test fun loginSave() = t("登录最高省15%", "Log in to save up to 15%")
    @Test fun cheaperThanAdded() = t("比加入时降¥30", "¥30 cheaper than when added")

    // ---------------------------------------------------------------- quantities
    @Test fun totalItems() = t("共3件", "3 items")
    @Test fun totalOneItem() = t("共1件", "1 item")
    @Test fun itemsGoods() = t("3件商品", "3 items")
    @Test fun weightTimesBags() = t("500g*2袋", "500g × 2 bags")
    @Test fun kgChinese() = t("2.5千克", "2.5kg")
    @Test fun bottlePack() = t("12瓶装", "12-bottle pack")
    @Test fun oneCase() = t("1箱", "1 case")
    @Test fun boxes() = t("24盒", "24 boxes")
    @Test fun pieces() = t("10片", "10 pcs")
    @Test fun jinToKg() = t("5斤", "2.5kg")
    @Test fun stage() = t("1段", "Stage 1")
    @Test fun onePiece() = t("1个", "1 pc")
    @Test fun ageRange() = t("3-6岁", "Ages 3-6")
    @Test fun screenInch() = t("55英寸", "55-inch")
    @Test fun plyAndSheets() = t("3层100抽", "3-ply, 100 sheets")
    @Test fun selected() = t("已选2件", "2 selected")

    // ---------------------------------------------------------------- time & dates
    @Test fun hoursAgo() = t("3小时前", "3 hours ago")
    @Test fun oneMinuteAgo() = t("1分钟前", "1 minute ago")
    @Test fun daysAgo() = t("2天前", "2 days ago")
    @Test fun yesterdayTime() = t("昨天 12:30", "Yesterday 12:30")
    @Test fun monthDay() = t("12月5日", "Dec 5")
    @Test fun fullDate() = t("2024年12月5日", "Dec 5, 2024")
    @Test fun yearOnly() = t("2025年", "2025")
    @Test fun remaining() = t("剩余02:13:44", "02:13:44 left")
    @Test fun endsIn() = t("距结束 02:13:44", "Ends in 02:13:44")
    @Test fun startsIn() = t("距开抢 1天05:00:00", "Starts in 1d 05:00:00")
    @Test fun orderByArrives() = t("今日23:59前下单明天送达", "Order by 23:59, arrives tomorrow")
    @Test fun arrivesTomorrowWeekday() = t("预计明天(周三)送达", "Arrives tomorrow (Wed)")
    @Test fun arrivesTomorrow() = t("预计明天送达", "Arrives tomorrow")
    @Test fun shipsWithin() = t("下单后48小时内发货", "Ships within 48 hours of order")
    @Test fun shipsWithinDays() = t("3天内发货", "Ships within 3 days")
    @Test fun payBy() = t("23:10前付款，预计", "Order by 23:10, est.")
    @Test fun tomorrowDate() = t("明天(11月02日)", "Tomorrow (Nov 2)")
    @Test fun dateWithWeekday() = t("11月11日(周二)", "Nov 11 (Tue)")
    @Test fun session() = t("10点场", "10:00 Deals")
    @Test fun startsAt() = t("20:00开抢", "Starts at 20:00")

    // ---------------------------------------------------------------- counts & ranks
    @Test fun followersLabel() = t("关注人数12.3万", "123K followers")
    @Test fun followersTail() = t("12.3万人关注", "123K followers")
    @Test fun followersPlus() = t("10983万+关注", "110M+ followers")
    @Test fun fans() = t("粉丝5万", "50K followers")
    @Test fun products() = t("4千+商品", "4K+ items")
    @Test fun productsWan() = t("3.5万+商品", "35K+ items")
    @Test fun rankNo() = t("第1名", "No. 1")
    @Test fun rankInList() = t("热卖榜第3名", "No. 3 in Best Sellers")
    @Test fun rankTop() = t("手机热卖榜TOP1", "No. 1 in Phone Best Sellers")
    @Test fun page() = t("第2页", "Page 2")

    // ---------------------------------------------------------------- coupons & guarantees
    @Test fun validUntil() = t("有效期至2024.12.31", "Valid until 2024.12.31")
    @Test fun validFor() = t("有效期7天", "Valid for 7 days")
    @Test fun noReasonReturns() = t("7天无理由退货", "7-Day No-Reason Returns")
    @Test fun priceProtection() = t("7天价保", "7-Day Price Protection")
    @Test fun replacement() = t("90天只换不修", "90-Day Replacement")
    @Test fun warranty() = t("2年质保", "2-Year Warranty")
    @Test fun composedGuarantee() = t("30天意外换新", "30-Day Accidental Damage Replacement")
    @Test fun composedGuarantee2() = t("60天碎屏换新", "60-Day Screen Damage Replacement")

    // ---------------------------------------------------------------- compositional
    @Test fun shippingFee() = t("运费¥6", "Shipping ¥6")
    @Test fun shippingYuan() = t("运费 6元", "Shipping ¥6")
    @Test fun stockCount() = t("库存12件", "12 in stock")
    @Test fun jdPrice() = t("京东价：¥99", "JD Price: ¥99")
    @Test fun plusPrice() = t("PLUS价¥88", "PLUS Price ¥88")
    @Test fun qaCount() = t("问大家(12)", "Q&A (12)")
    @Test fun checkoutCount() = t("去结算(3)", "Checkout (3)")
    @Test fun valueHead() = t("67W快充", "67W Fast Charging")
    @Test fun bedSize() = t("1.8米床", "1.8m Bed")
    @Test fun subsidyPct() = t("国家补贴15%", "15% National Subsidy")
    @Test fun multiPartSpace() = t("现货 24小时内发货", "In Stock Ships within 24 hours")
    @Test fun multiPartGlossary() = t("京东自营 包邮", "JD Direct Free Shipping")

    // ---------------------------------------------------------------- stores & composites
    @Test fun storeGlossaryBrand() = t("小米京东自营旗舰店", "Xiaomi JD Direct Flagship Store")
    @Test fun storeLatinBrand() = t("Apple产品京东自营旗舰店", "Apple JD Direct Flagship Store")
    @Test fun storeParenBrand() = t("富士（FUJIFILM）京东自营旗舰店", "FUJIFILM JD Direct Flagship Store")
    @Test fun storeClosed() = t("小米京东自营旗舰店(1000085427)已退店", "Xiaomi JD Direct Flagship Store (Closed)")
    @Test fun storeUnknownBrand() = none("某某某旗舰店")
    @Test fun rankSegmented() = t("纪念日白酒热卖榜第25名", "No. 25 in Anniversary Baijiu Best Sellers")
    @Test fun parenTail() = t("1699(需预约)", "1699 (Reservation Required)")
    @Test fun multiWithParen() = t("1699(需预约)，单瓶装，1个", "1699 (Reservation Required), Single Bottle, 1 pc")
    @Test fun termTimes() = t("原箱装*2", "Full Case × 2")
    @Test fun labelValue() = t("颜色：黑色", "Color: Black")
    @Test fun labelLatinValue() = t("尺码：XL", "Size: XL")
    @Test fun crossStoreEvery() = t("跨店每满300减50", "Cross-store: ¥50 off every ¥300")
    @Test fun dateStarts() = t("12月12日 00:00开抢", "Starts Dec 12, 00:00")
    @Test fun deliveryWindow() = t("明天09:00-15:00送达", "Arrives tomorrow 09:00-15:00")
    @Test fun arrivesByTime() = t("明天15:00前送达", "Arrives tomorrow by 15:00")
    @Test fun arrivesDate() = t("11月3日送达", "Arrives Nov 3")
    @Test fun nextDayDelivery() = t("明日达", "Arrives tomorrow")
    @Test fun endsInDays() = t("1天后结束", "Ends in 1 day")
    @Test fun notEligibleReturns() = t("不支持7天无理由退货", "Not eligible for 7-day no-reason returns")

    // ---------------------------------------------------------------- non-matches
    @Test fun noHan() = none("¥99.00")
    @Test fun plainWordNotRule() = none("加入购物车")
    @Test fun longTitle() = none("Apple/苹果 iPhone 15 (A3092) 256GB 黑色 支持移动联通电信5G 双卡双待手机")
    @Test fun longPromoText() = none("满199减20满299减40满399减60满499减80满599减100满699减120元")
    @Test fun unknownPrefix() = none("某某¥6")
    @Test fun emptyString() = none("   ")
    @Test fun badDiscount() = none("12.5折")

    // ---------------------------------------------------------------- count formatting
    @Test fun countFormatting() {
        assertEquals("10K", engine.count("1", "万"))
        assertEquals("15K", engine.count("1.5", "万"))
        assertEquals("1M", engine.count("100", "万"))
        assertEquals("100M", engine.count("1", "亿"))
        assertEquals("999", engine.count("999"))
        assertEquals("9,999", engine.count("9999"))
        assertEquals("12.5K", engine.count("12500"))
        assertEquals("1.2B", engine.count("12", "亿"))
    }

    // ---------------------------------------------------------------- real glossary assets
    private val assetsDir: File by lazy {
        listOf("src/main/assets/glossary", "app/src/main/assets/glossary").map { File(it) }.first { it.isDirectory }
    }

    private val realGlossary: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        assetsDir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.forEach { f ->
            val o = JSONObject(f.readText())
            for (k in o.keys()) map[Glossary.normalize(k)] = o.getString(k)
        }
        map
    }

    @Test fun glossaryAssetsAreLargeAndCoverProtectedTerms() {
        assertTrue("glossary too small: ${realGlossary.size}", realGlossary.size >= 2500)
        val protected = File(assetsDir, "_protected.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(protected.size >= 20)
        for (p in protected) assertTrue("protected term without translation: $p", realGlossary.containsKey(p))
        for ((k, v) in realGlossary) assertTrue("Han in value for $k: $v", !Cjk.hasHan(v))
    }

    @Test fun glossaryKeyTerms() {
        val expect = mapOf(
            "购物车" to "Cart", "首页" to "Home", "分类" to "Categories", "去结算" to "Checkout",
            "立即购买" to "Buy Now", "加入购物车" to "Add to Cart", "京东自营" to "JD Direct", "京豆" to "JD Beans",
            "百亿补贴" to "Big Subsidy Deals", "已抢光" to "Sold Out", "综合" to "Best Match", "筛选" to "Filter",
        )
        for ((zh, en) in expect) assertEquals(zh, en, realGlossary[zh])
    }

    @Test fun rulesWithRealGlossary() {
        val e = RuleEngine { realGlossary[Glossary.normalize(it)] }
        assertEquals("Shipping ¥6", e.apply("运费¥6"))
        assertEquals("12 in stock", e.apply("库存12件"))
        assertEquals("Xiaomi JD Direct Flagship Store", e.apply("小米京东自营旗舰店"))
        assertEquals("Color: Black", e.apply("颜色：黑色"))
        assertEquals("Flash Sale Price ¥99", e.apply("秒杀价¥99"))
        assertEquals("Huawei Mate 60 Pro 12GB+512GB Yachuan Green", e.apply("华为 Mate 60 Pro 12GB+512GB 雅川青"))
        assertEquals("1699 (Reservation Required), Single Bottle, 1 pc", e.apply("1699(需预约)，单瓶装，1个"))
        // Store names: Latin brand in parentheses / Latin prefix plus a line-of-business tail.
        assertEquals("PISEN Car Electronics JD Direct Flagship Store", e.apply("品胜（PISEN）车载电器京东自营旗舰店"))
        assertEquals("3M Personal Protection JD Direct Flagship Store", e.apply("3M个人防护京东自营旗舰店01"))
        assertEquals("SATA Hardware & Tools JD Direct Zone", e.apply("世达（SATA）五金工具京东自营专区"))
        assertEquals("FUJIFILM JD Direct Flagship Store", e.apply("富士（FUJIFILM）京东自营旗舰店"))
        assertEquals("THE GREAT WALL Hardware & Tools JD Direct Flagship Store", e.apply("长城（THE GREAT WALL）五金工具京东自营旗舰店"))
        assertEquals("No. 15 in Top-Rated SSDs", e.apply("固态硬盘好评榜第15名"))
    }
}
