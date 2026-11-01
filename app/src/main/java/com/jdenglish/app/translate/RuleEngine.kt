package com.jdenglish.app.translate

/**
 * Pattern rules for strings with numbers/prices/dates etc.
 * Pure Kotlin, no Android deps (unit-testable on JVM).
 * OWNER: Lexicon. Stub; must keep this signature.
 */
class RuleEngine(private val glossaryLookup: (String) -> String?) {
    /** Returns natural English, or null if no rule matches the whole string. */
    fun apply(zh: String): String? = null
}
