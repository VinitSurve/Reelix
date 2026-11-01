package app.reelix.data.repository

import java.util.Locale

/** Normalises tags so that "#Productivity", "productivity " and "PRODUCTIVITY" are one tag. */
object TagNormalizer {
    private const val MAX_LENGTH = 40
    private val DISALLOWED = Regex("""[^\p{L}\p{N} _-]""")
    private val WHITESPACE = Regex("""\s+""")

    fun normalize(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw.trim()
            .trimStart('#')
            .lowercase(Locale.ROOT)
            .replace(DISALLOWED, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .take(MAX_LENGTH)
            .trim()
        return cleaned.ifEmpty { null }
    }

    fun normalizeAll(raw: Iterable<String>): List<String> = raw.mapNotNull(::normalize).distinct()

    /** Hashtags are legitimately part of a caption, so they become EXTRACTED tags. */
    fun hashtags(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return Regex("""#([\p{L}\p{N}_]{2,40})""").findAll(text)
            .mapNotNull { normalize(it.groupValues[1]) }
            .distinct()
            .take(30)
            .toList()
    }
}

/** Converts free user input into a safe FTS4 MATCH expression (implicit AND of prefix terms). */
object FtsQueryBuilder {
    private val TOKEN_SPLIT = Regex("""[^\p{L}\p{N}]+""")
    private const val MAX_TOKENS = 8

    fun build(query: String?): String? {
        if (query.isNullOrBlank()) return null
        val tokens = query.lowercase(Locale.ROOT)
            .split(TOKEN_SPLIT)
            .filter { it.isNotBlank() }
            .take(MAX_TOKENS)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }
}

