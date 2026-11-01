package app.reelix.core.url

import app.reelix.domain.model.ContentType
import app.reelix.domain.model.InstagramLink
import java.net.URI
import java.util.Locale

/**
 * Finds, validates and normalises Instagram links.
 *
 * The original text of each link is preserved; normalisation only removes things that do not change
 * which piece of content is referenced (scheme case, host aliases, tracking query strings, fragments,
 * username prefixes and the `/reels/` alias).
 */
object InstagramUrlParser {

    private const val MAX_INPUT_LENGTH = 20_000
    private const val MAX_URL_LENGTH = 2_048

    private val INSTAGRAM_HOSTS = setOf(
        "instagram.com",
        "www.instagram.com",
        "m.instagram.com",
        "instagr.am",
        "www.instagr.am",
    )

    private val SCHEME_URL = Regex("""(?i)https?://[^\s<>"'`]+""")
    private val BARE_URL = Regex("""(?i)(?<![\w./@-])(?:www\.|m\.)?(?:instagram\.com|instagr\.am)/[^\s<>"'`]*""")
    private val SHORTCODE = Regex("""^[A-Za-z0-9_-]{4,80}$""")
    private val USERNAME = Regex("""^[A-Za-z0-9._]{1,30}$""")
    private val SAFE_SEGMENT = Regex("""^[A-Za-z0-9._~-]{1,100}$""")
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}'\"…»”’"

    private val TYPE_SEGMENTS = mapOf(
        "reel" to ContentType.REEL,
        "reels" to ContentType.REEL,
        "p" to ContentType.POST,
        "tv" to ContentType.IGTV,
    )

    /** Extracts every distinct valid Instagram link from free text, in order of appearance. */
    fun extractAll(text: String?): List<InstagramLink> {
        if (text.isNullOrBlank()) return emptyList()
        val input = if (text.length > MAX_INPUT_LENGTH) text.substring(0, MAX_INPUT_LENGTH) else text

        val candidates = mutableListOf<Pair<Int, String>>()
        val covered = mutableListOf<IntRange>()
        SCHEME_URL.findAll(input).forEach { match ->
            candidates += match.range.first to match.value
            covered += match.range
        }
        BARE_URL.findAll(input).forEach { match ->
            if (covered.none { it.first <= match.range.first && match.range.first <= it.last }) {
                candidates += match.range.first to match.value
            }
        }

        return candidates
            .sortedBy { it.first }
            .mapNotNull { parse(trimTrailingPunctuation(it.second)) }
            .distinctBy { it.normalizedUrl }
    }

    /** Parses a single URL string. Returns null if it is not a supported Instagram link. */
    fun parse(raw: String?): InstagramLink? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_LENGTH) return null
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return null

        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = try {
            URI(withScheme)
        } catch (_: Exception) {
            return null
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (uri.rawUserInfo != null) return null
        if (uri.port != -1 && uri.port != 80 && uri.port != 443) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        if (host !in INSTAGRAM_HOSTS) return null

        val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null

        val (type, normalizedPath, shortcode) = classify(segments) ?: return null
        return InstagramLink(
            originalUrl = trimmed,
            normalizedUrl = "https://www.instagram.com/$normalizedPath/",
            contentType = type,
            shortcode = shortcode,
        )
    }

    fun isInstagramHost(host: String?): Boolean =
        host?.lowercase(Locale.ROOT)?.trimEnd('.') in INSTAGRAM_HOSTS

    private data class Classified(val type: ContentType, val path: String, val shortcode: String?)

    private fun classify(segments: List<String>): Classified? {
        val first = segments[0].lowercase(Locale.ROOT)

        // /reel/{code}, /reels/{code}, /p/{code}, /tv/{code}
        TYPE_SEGMENTS[first]?.let { type ->
            val code = segments.getOrNull(1)?.takeIf { SHORTCODE.matches(it) } ?: return null
            return Classified(type, "${canonicalSegment(type)}/$code", code)
        }

        // /share/{code} and /share/{reel|p}/{code} — Instagram share redirect links.
        if (first == "share") {
            val second = segments.getOrNull(1) ?: return null
            val secondType = TYPE_SEGMENTS[second.lowercase(Locale.ROOT)]
            return if (secondType != null) {
                val code = segments.getOrNull(2)?.takeIf { SHORTCODE.matches(it) } ?: return null
                Classified(secondType, "share/${canonicalSegment(secondType)}/$code", null)
            } else {
                if (!SHORTCODE.matches(second)) return null
                Classified(ContentType.UNKNOWN, "share/$second", null)
            }
        }

        // /{username}/reel/{code}, /{username}/p/{code}
        if (segments.size >= 3 && USERNAME.matches(segments[0])) {
            val type = TYPE_SEGMENTS[segments[1].lowercase(Locale.ROOT)]
            val code = segments[2]
            if (type != null && SHORTCODE.matches(code)) {
                return Classified(type, "${canonicalSegment(type)}/$code", code)
            }
        }

        // Any other Instagram page (profile, story, etc.) — keep the path but do not guess a type.
        if (segments.size > 6 || segments.any { !SAFE_SEGMENT.matches(it) }) return null
        if (first in setOf("accounts", "explore", "direct", "about", "legal", "developer")) return null
        return Classified(ContentType.UNKNOWN, segments.joinToString("/"), null)
    }

    private fun canonicalSegment(type: ContentType): String = when (type) {
        ContentType.REEL -> "reel"
        ContentType.POST -> "p"
        ContentType.IGTV -> "tv"
        ContentType.AUDIO, ContentType.UNKNOWN -> ""
    }

    private fun trimTrailingPunctuation(value: String): String {
        var end = value.length
        while (end > 0 && TRAILING_PUNCTUATION.indexOf(value[end - 1]) >= 0) end--
        return value.substring(0, end)
    }
}

