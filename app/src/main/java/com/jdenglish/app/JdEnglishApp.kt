package com.jdenglish.app

import android.app.Application
import android.util.Log
import androidx.preference.PreferenceManager
import com.jdenglish.app.translate.TranslationEngine

class JdEnglishApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Make sure XML defaults (enabled=true, engine=mlkit, ...) exist before anyone reads them.
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        try {
            TranslationEngine.get(this).warmUp()
        } catch (t: Throwable) {
            Log.w("JDT.App", "Translation warm-up failed", t)
        }
    }
}
