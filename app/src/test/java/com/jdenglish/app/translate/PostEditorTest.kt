package com.jdenglish.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class PostEditorTest {
    private fun ui(s: String) = PostEditor.edit(s, "ui")
    private fun title(s: String) = PostEditor.edit(s, "title")
    private fun product(s: String) = PostEditor.edit(s, "product")

    @Test fun jdBrand() {
        assertEquals("JD Supermarket", ui("Jingdong Supermarket"))
        assertEquals("Welcome to JD", product("Welcome to Jingdong Mall"))
        assertEquals("Welcome to JD", product("Welcome to JD.com Mall"))
        assertEquals("JD Logistics", ui("Beijing East Logistics"))
        assertEquals("JD Express", ui("Jd Express"))
    }

    @Test fun jdDirect() {
        assertEquals("JD Direct", ui("Jingdong self-operated"))
        assertEquals("JD Direct", ui("Self-operated"))
        assertEquals("JD Direct store", product("self-operated store"))
        assertEquals("JD Direct", ui("JD Self-employed"))
        assertEquals("Apple JD Direct Flagship Store", title("Apple Jingdong self-operated Flagship Store"))
    }

    @Test fun beansPlusBaitiao() {
        assertEquals("Earn 50 JD Beans", product("Earn 50 Jingdong beans"))
        assertEquals("JD Beans", ui("Jingdou"))
        assertEquals("PLUS Member price", product("plus member price"))
        assertEquals("PLUS Membership", ui("Plus membership"))
        assertEquals("Pay with JD Baitiao (credit)", product("Pay with white bar"))
        assertEquals("JD Baitiao (credit) installments", product("Baitiao installments"))
        assertEquals("JD Baitiao (credit)", ui("JD Baitiao (credit)"))
    }

    @Test fun promos() {
        assertEquals("Big Subsidy Deals", ui("Ten billion subsidies"))
        assertEquals("Big Subsidy Deals", ui("tens of billions of subsidies"))
        assertEquals("Free Shipping", ui("Package mail"))
        assertEquals("Free Shipping", ui("package post"))
        assertEquals("Free Shipping", ui("Bao mail"))
        assertEquals("Flash Sale", ui("Second kill"))
        assertEquals("Flash Sale", ui("seconds kill"))
        assertEquals("Flash Sale", ui("Spike"))
        assertEquals("Flash Sale prices", product("Jingdong seckill prices"))
    }

    @Test fun couponsOnlyInUi() {
        assertEquals("Get Coupons", ui("Get tickets"))
        assertEquals("Get Coupons", ui("Collect coupons"))
        assertEquals("Coupons", ui("Tickets"))
        assertEquals("Concert tickets", product("Concert tickets"))
    }

    @Test fun uiTitleCase() {
        assertEquals("Add to Cart", ui("Add to shopping cart"))
        assertEquals("View More", ui("view more"))
        assertEquals("Out of Stock", ui("out of stock"))
        assertEquals("Buy Now", ui("Buy it now"))
        assertEquals("iPhone deals", title("iPhone deals"))
        assertEquals("Please log in first", ui("please log in first."))
        assertEquals("No results found", ui("no results found"))
        assertEquals("Customer Service", ui("customer service"))
    }

    @Test fun punctuation() {
        assertEquals("Color: red, size: XL", product("Color：red，size：XL"))
        assertEquals("Ships at 12:30", product("Ships at 12：30"))
        assertEquals("Great quality! Fast delivery?", PostEditor.edit("Great quality！Fast delivery？", "review"))
        assertEquals("Apple (official)", product("Apple（official）"))
        assertEquals("Red, blue, green", product("Red、blue、green"))
        assertEquals("Hello world", product("Hello   world"))
        assertEquals("[Hot] new phone", product("【Hot】new phone"))
    }

    @Test fun trailingPeriod() {
        assertEquals("Order submitted", ui("Order submitted."))
        assertEquals("Order submitted", PostEditor.edit("Order submitted.", "review", "订单已提交。"))
        assertEquals("Shenzhen Tech Co., Ltd.", title("Shenzhen Tech Co., Ltd."))
        assertEquals("Works great. Love it.", PostEditor.edit("Works great. Love it.", "review"))
    }

    @Test fun pricesAndQuantities() {
        assertEquals("Only ¥99", product("Only 99 yuan"))
        assertEquals("¥1,299", product("RMB 1,299"))
        assertEquals("¥59", product("￥ 59"))
        assertEquals("1 piece", product("1 pieces"))
        assertEquals("3 pieces", product("3 piece"))
        assertEquals("2 boxes", product("2 box"))
        assertEquals("21 items", product("21 item"))
    }

    @Test fun cleanIsIdempotent() {
        val inputs = listOf("Jingdong self-operated Package mail", "plus member price ¥ 99", "Color：red，size：XL", "Pay with white bar")
        for (s in inputs) {
            val once = PostEditor.clean(s)
            assertEquals(once, PostEditor.clean(once))
        }
    }
}
