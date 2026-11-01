package com.jdenglish.app.web

import android.net.Uri
import java.net.URLEncoder

/** JD URLs and URL helpers shared by the activity and the WebView client. */
object JdUrls {
    const val HOME = "https://m.jd.com/"
    const val CATEGORIES = "https://so.m.jd.com/category/all.html"
    const val CART = "https://p.m.jd.com/cart/cart.action"
    const val MY_JD = "https://home.m.jd.com/myJd/newhome.action"

    fun search(keyword: String): String =
        "https://so.m.jd.com/ware/search.action?keyword=" + URLEncoder.encode(keyword, "UTF-8")

    /** Schemes JD pages use to try to launch native apps. Blocked silently. */
    val BLOCKED_SCHEMES = setOf(
        "openapp.jdmobile", "jdmobile", "openjd", "jdpay", "weixin", "wechat", "alipays", "alipay",
        "taobao", "tbopen", "mqq", "mqqapi", "mqqwpa", "tmall", "snssdk1128", "market", "jdlite",
    )

    private val URL_IN_TEXT = Regex("""https?://[^\s"'<>，。、；！？）)\]】]+""", RegexOption.IGNORE_CASE)
    private val DESKTOP_ITEM = Regex("""^https?://item\.jd\.com/(\d+)\.html""", RegexOption.IGNORE_CASE)

    fun hostOf(url: String?): String? =
        try { url?.let { Uri.parse(it).host?.lowercase() } } catch (_: Exception) { null }

    fun isJdHost(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        return h == "jd.com" || h.endsWith(".jd.com") || h == "3.cn" || h.endsWith(".3.cn") ||
            h.endsWith(".jd.hk") || h == "jd.hk" || h.endsWith(".jingxi.com")
    }

    fun isJdUrl(url: String?): Boolean {
        if (url == null) return false
        val uri = try { Uri.parse(url) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase()
        return (scheme == "http" || scheme == "https") && isJdHost(uri.host)
    }

    /** Pull the first JD link out of shared text (e.g. "【京东】... https://u.jd.com/xyz ..."). */
    fun extractJdUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val all = URL_IN_TEXT.findAll(text).map { it.value.trimEnd('.', ',', '!', '?') }.toList()
        return all.firstOrNull { isJdUrl(it) }
    }

    /** Prefer the mobile site for desktop links (item.jd.com/123.html -> item.m.jd.com/product/123.html). */
    fun toMobile(url: String): String {
        DESKTOP_ITEM.find(url)?.let { return "https://item.m.jd.com/product/${it.groupValues[1]}.html" }
        val uri = try { Uri.parse(url) } catch (_: Exception) { return url }
        val host = uri.host?.lowercase()
        if ((host == "www.jd.com" || host == "jd.com") && (uri.path.isNullOrEmpty() || uri.path == "/")) return HOME
        return url
    }

    fun isHome(url: String?): Boolean {
        val uri = try { Uri.parse(url ?: return false) } catch (_: Exception) { return false }
        val host = uri.host?.lowercase() ?: return false
        if (host != "m.jd.com") return false
        val path = uri.path.orEmpty()
        return path.isEmpty() || path == "/" || path == "/index.html"
    }

    enum class Tab { HOME, CATEGORIES, CART, MY_JD }

    /** Which bottom-nav tab a URL belongs to, or null for "other" pages. */
    fun tabFor(url: String?): Tab? {
        if (url == null) return null
        if (isHome(url)) return Tab.HOME
        val uri = try { Uri.parse(url) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path?.lowercase().orEmpty()
        return when {
            // so.m.jd.com/category/all.html currently redirects to minicaty-pro.pf.jd.com
            (host == "so.m.jd.com" && path.startsWith("/category")) || host.startsWith("minicaty") -> Tab.CATEGORIES
            (host == "p.m.jd.com" && path.contains("cart")) || host == "cart.m.jd.com" -> Tab.CART
            // home.m.jd.com/myJd/newhome.action currently redirects to my.m.jd.com
            host == "home.m.jd.com" || host == "my.m.jd.com" -> Tab.MY_JD
            else -> null
        }
    }
}
