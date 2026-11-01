package com.tauru.astrbotphoneagent.logic

/**
 * Pulls user-visible strings out of a UI dump, for the screen_text action.
 *
 * One parser covers both sources: the uiautomator XML stores each label in a
 * text="…" attribute, and the `dumpsys activity top` view hierarchy prints the
 * same text="…" fields on one line per view. Pure JVM code, so the extraction
 * rules are unit tested without a device.
 */
internal object UiTextParser {
    private val TEXT = Regex("""text="([^"]{1,120})"""")

    /** Texts in document order, trimmed, blanks dropped, capped for the LLM. */
    fun parse(raw: String, limit: Int = 80): List<String> = TEXT.findAll(raw)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(limit)
        .toList()
}
