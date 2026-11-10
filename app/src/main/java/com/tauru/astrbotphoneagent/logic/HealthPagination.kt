package com.tauru.astrbotphoneagent.logic

/** API 33's IPC can use an empty token instead of null for the final page. */
internal suspend fun <T> readHealthPages(
    maxPages: Int = 10,
    readPage: suspend (String?) -> Pair<List<T>, String?>,
): List<T> {
    require(maxPages > 0)
    val records = mutableListOf<T>()
    val seenTokens = mutableSetOf<String>()
    var token: String? = null
    repeat(maxPages) {
        val (page, next) = readPage(token)
        records += page
        if (next.isNullOrEmpty()) return records
        check(seenTokens.add(next)) { "health_page_cycle" }
        token = next
    }
    // Never upload an incomplete summary as if it covered the full time range.
    error("health_read_limit")
}
