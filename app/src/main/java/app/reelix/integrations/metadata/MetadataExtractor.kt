package app.reelix.integrations.metadata

import app.reelix.core.safeDescription
import app.reelix.core.url.InstagramUrlParser
import app.reelix.domain.model.InstagramLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Extracts descriptive metadata (title, caption, preview image) for a link. */
interface MetadataExtractor {
    val id: String
    suspend fun extract(link: InstagramLink): MetadataOutcome
}

data class ExtractedMetadata(
    val title: String?,
    val caption: String?,
    val authorName: String?,
    val imageUrl: String?,
)

sealed interface MetadataOutcome {
    data class Found(val metadata: ExtractedMetadata) : MetadataOutcome
    data class NotAvailable(val reason: String) : MetadataOutcome
    data class TransientFailure(val error: String) : MetadataOutcome
}

/** Fetches a page's HTML. Abstracted so the extractor can be tested without network access. */
fun interface PageFetcher {
    suspend fun fetch(url: String): PageResponse
}

data class PageResponse(val code: Int, val finalUrl: String, val body: String?)

class OkHttpPageFetcher(
    private val client: OkHttpClient,
    private val maxBytes: Long = 1_500_000,
) : PageFetcher {
    override suspend fun fetch(url: String): PageResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            // Honest identification; Reelix does not impersonate browsers or crawlers.
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en")
            .build()
        client.newCall(request).execute().use { response ->
            val source = response.body?.source()
            val body = source?.let {
                it.request(maxBytes)
                val available = minOf(it.buffer.size, maxBytes)
                it.buffer.readUtf8(available)
            }
            PageResponse(response.code, response.request.url.toString(), body)
        }
    }

    companion object {
        const val USER_AGENT = "Reelix/0.1 (Android; personal link preview)"
    }
}

/**
 * Best-effort link-preview metadata from the public page's OpenGraph tags — the same information a
 * messaging app shows when a link is pasted. It never logs in, never uses private endpoints and
 * treats login walls / generic pages as "not available" rather than inventing data.
 */
class OpenGraphMetadataExtractor(private val fetcher: PageFetcher) : MetadataExtractor {

    override val id: String = "opengraph-link-preview"

    override suspend fun extract(link: InstagramLink): MetadataOutcome {
        val response = try {
            fetcher.fetch(link.normalizedUrl)
        } catch (e: IOException) {
            return MetadataOutcome.TransientFailure("Network error (${e.javaClass.simpleName})")
        } catch (e: IllegalArgumentException) {
            return MetadataOutcome.NotAvailable("Invalid URL: ${e.safeDescription()}")
        }

        when {
            response.code == 429 || response.code >= 500 ->
                return MetadataOutcome.TransientFailure("Instagram returned HTTP ${response.code}")
            response.code == 404 || response.code == 410 ->
                return MetadataOutcome.NotAvailable("Content not found or removed (HTTP ${response.code})")
            response.code !in 200..299 ->
                return MetadataOutcome.NotAvailable("Preview not available (HTTP ${response.code})")
        }

        val finalHost = runCatching { java.net.URI(response.finalUrl).host }.getOrNull()
        if (!InstagramUrlParser.isInstagramHost(finalHost)) {
            return MetadataOutcome.NotAvailable("Redirected away from Instagram")
        }
        if (response.finalUrl.contains("/accounts/login") || response.finalUrl.contains("/challenge")) {
            return MetadataOutcome.NotAvailable("Instagram requires login to show a preview of this content")
        }

        val tags = OpenGraphParser.parse(response.body.orEmpty())
        return interpret(tags)
    }

    companion object {
        private val GENERIC_TITLES = setOf("instagram", "instagram photos and videos", "login • instagram", "")
        private val GENERIC_DESCRIPTION_MARKERS = listOf(
            "create an account or log in to instagram",
            "log in to instagram",
            "welcome back to instagram",
            "sign up to see photos and videos",
        )
        private val ON_INSTAGRAM = Regex("""^(.+?) on Instagram(?::|$)""")
        private val QUOTED_CAPTION = Regex(""":\s*["“](.+)["”]\s*\.?\s*$""", RegexOption.DOT_MATCHES_ALL)

        /** Turns raw meta tags into metadata, rejecting generic/login-wall pages. */
        fun interpret(tags: Map<String, String>): MetadataOutcome {
            val title = (tags["og:title"] ?: tags["twitter:title"])?.trim()
            val description = (tags["og:description"] ?: tags["description"] ?: tags["twitter:description"])?.trim()
            val image = (tags["og:image"] ?: tags["twitter:image"])?.trim()?.takeIf { it.startsWith("https://") }

            val titleIsGeneric = title == null || title.lowercase() in GENERIC_TITLES
            val descriptionIsGeneric = description == null ||
                GENERIC_DESCRIPTION_MARKERS.any { description.lowercase().contains(it) }
            if (titleIsGeneric && descriptionIsGeneric) {
                return MetadataOutcome.NotAvailable("Instagram did not provide a public preview for this link")
            }

            val author = title?.let { ON_INSTAGRAM.find(it)?.groupValues?.get(1)?.trim() }
            val caption = description?.takeUnless { descriptionIsGeneric }?.let { desc ->
                QUOTED_CAPTION.find(desc)?.groupValues?.get(1)?.trim() ?: desc
            }
            return MetadataOutcome.Found(
                ExtractedMetadata(
                    title = title?.takeUnless { titleIsGeneric }?.take(300),
                    caption = caption?.take(10_000),
                    authorName = author?.take(100),
                    imageUrl = image,
                ),
            )
        }
    }
}

