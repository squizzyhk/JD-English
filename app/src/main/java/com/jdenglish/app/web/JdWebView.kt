package com.jdenglish.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import kotlin.math.abs

/**
 * WebView that knows whether the page content is really at the top.
 *
 * JD's H5 pages often scroll an inner container instead of the document, so
 * `scrollY == 0` alone would let pull-to-refresh hijack normal scrolling. We
 * additionally track whether the last gesture left the content at the top:
 * - an over-scroll past the top (reported by Chromium for both document and
 *   chained inner scrollers) means "at top";
 * - a finger moving up (content scrolling down) without that clears it;
 * - the document scrolling back to 0 or a new page load sets it again.
 */
class JdWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle,
) : WebView(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    @Volatile private var contentAtTop = true

    /** True when a downward pull should trigger pull-to-refresh. */
    fun isAtTopForRefresh(): Boolean = scrollY <= 0 && contentAtTop

    /** Call when a new page starts loading. */
    fun resetScrollTracking() {
        contentAtTop = true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> downY = event.y
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - downY
                if (dy < -touchSlop) {
                    // Finger moving up: content scrolls down, so we're no longer at the top.
                    contentAtTop = false
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        if (clampedY && scrollY <= 0) contentAtTop = true
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (t <= 0 && oldt > 0) contentAtTop = true
        else if (t > 0 && abs(t - oldt) > 0) contentAtTop = false
    }
}
