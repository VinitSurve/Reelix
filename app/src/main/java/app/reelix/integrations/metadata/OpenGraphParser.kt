package app.reelix.integrations.metadata

import java.util.Locale

/** Minimal, dependency-free parser for OpenGraph / standard meta tags in an HTML document head. */
object OpenGraphParser {

    private val META_TAG = Regex("""<meta\s+[^>]*>""", setOf(RegexOption.IGNORE_CASE))
    private val ATTRIBUTE = Regex(
        """([a-zA-Z:_-]+)\s*=\s*("([^"]*)"|'([^']*)')""",
    )
    private val NUMERIC_ENTITY = Regex("""&#(x?[0-9a-fA-F]+);""")
    private val NAMED_ENTITIES = mapOf(
        "&quot;" to "\"", "&apos;" to "'", "&lt;" to "<", "&gt;" to ">", "&nbsp;" to " ",
        // Must be last so "&amp;quot;" decodes to "&quot;" rather than '"'.
        "&amp;" to "&",
    )

    /** Returns a map of lower-cased property/name to the first content value found. */
    fun parse(html: String): Map<String, String> {
        val head = html.substringBefore("</head>", html).take(MAX_HEAD_CHARS)
        val result = LinkedHashMap<String, String>()
        META_TAG.findAll(head).forEach { tag ->
            val attributes = ATTRIBUTE.findAll(tag.value).associate { m ->
                m.groupValues[1].lowercase(Locale.ROOT) to (m.groups[3]?.value ?: m.groups[4]?.value.orEmpty())
            }
            val key = (attributes["property"] ?: attributes["name"])?.lowercase(Locale.ROOT) ?: return@forEach
            val content = attributes["content"] ?: return@forEach
            if (key !in result) result[key] = decodeEntities(content).trim()
        }
        return result
    }

    fun decodeEntities(value: String): String {
        var out = NUMERIC_ENTITY.replace(value) { m ->
            val raw = m.groupValues[1]
            val code = if (raw.startsWith("x") || raw.startsWith("X")) raw.drop(1).toIntOrNull(16) else raw.toIntOrNull()
            if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else m.value
        }
        NAMED_ENTITIES.forEach { (entity, replacement) -> out = out.replace(entity, replacement) }
        return out
    }

    private const val MAX_HEAD_CHARS = 512 * 1024
}
