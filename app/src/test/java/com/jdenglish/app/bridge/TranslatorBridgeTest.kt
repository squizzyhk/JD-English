package com.jdenglish.app.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslatorBridgeTest {
    @Test fun docOfExtractsTopFrameNonce() {
        assertEquals("fab12cd_", TranslatorBridge.docOf("fab12cd_17"))
        assertEquals("fab12cd_", TranslatorBridge.docOf("fab12cd_i18"))
        assertEquals("fab12cd_", TranslatorBridge.docOf("Rfab12cd_19"))
        assertEquals("", TranslatorBridge.docOf("R7"))
    }
}
