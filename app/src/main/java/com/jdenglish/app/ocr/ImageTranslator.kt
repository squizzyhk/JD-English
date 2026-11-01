package com.jdenglish.app.ocr

import android.content.Context
import com.jdenglish.app.translate.TranslationEngine

/**
 * Downloads an image (with the WebView's cookies + Referer), runs ML Kit Chinese OCR,
 * groups lines into blocks, translates via TranslationEngine (ctx="image"), samples
 * fg/bg colors, and returns the JSON described in docs/ARCHITECTURE.md ("Image result JSON").
 * OWNER: OCR. Stub; must keep this signature.
 */
class ImageTranslator(private val context: Context, private val engine: TranslationEngine) {
    /** Never throws; returns {"error":...} JSON on failure. */
    suspend fun translateImage(url: String, pageUrl: String?, userAgent: String?): String =
        "{\"w\":0,\"h\":0,\"blocks\":[]}"
}
