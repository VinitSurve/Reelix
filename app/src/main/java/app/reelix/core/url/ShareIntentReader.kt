package app.reelix.core.url

import android.content.Intent
import android.net.Uri

/**
 * Reads every piece of text or media URI an incoming share intent may carry. It never throws:
 * malformed or hostile intents simply yield an empty list.
 */
object ShareIntentReader {

    private const val MAX_TEXT_LENGTH = 20_000
    private const val MAX_MEDIA_ITEMS = 20

    fun readTexts(intent: Intent?): List<String> {
        if (intent == null) return emptyList()
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return emptyList()

        val texts = LinkedHashSet<String>()
        fun add(value: CharSequence?) {
            val text = value?.toString()?.trim()
            if (!text.isNullOrEmpty()) texts += text.take(MAX_TEXT_LENGTH)
        }

        runCatching { add(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)) }
        runCatching { intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.forEach(::add) }
        runCatching { intent.getStringArrayListExtra(Intent.EXTRA_TEXT)?.forEach(::add) }
        runCatching { add(intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)) }
        runCatching {
            val clip = intent.clipData ?: return@runCatching
            for (i in 0 until minOf(clip.itemCount, 20)) {
                val item = clip.getItemAt(i)
                add(item.text)
                item.uri?.takeIf { it.scheme == "http" || it.scheme == "https" }?.let { add(it.toString()) }
            }
        }
        runCatching {
            intent.data?.takeIf { it.scheme == "http" || it.scheme == "https" }?.let { add(it.toString()) }
        }
        return texts.toList()
    }

    /**
     * Extracts media [Uri]s carried by an [Intent.ACTION_SEND] or [Intent.ACTION_SEND_MULTIPLE] intent.
     * Checks EXTRA_STREAM, ClipData, and Intent.data.
     */
    fun readMediaUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return emptyList()

        val uris = LinkedHashSet<Uri>()

        fun addUri(uri: Uri?) {
            if (uri != null && (uri.scheme == "content" || uri.scheme == "file")) {
                uris += uri
            }
        }

        // Single stream
        runCatching {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::addUri)
        }

        // Multiple streams
        runCatching {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach(::addUri)
        }

        // Clip data (frequently used by modern Android sharesheets for grant permissions)
        runCatching {
            val clip = intent.clipData ?: return@runCatching
            for (i in 0 until minOf(clip.itemCount, MAX_MEDIA_ITEMS)) {
                addUri(clip.getItemAt(i).uri)
            }
        }

        // Intent data fallback
        runCatching {
            intent.data?.let(::addUri)
        }

        return uris.take(MAX_MEDIA_ITEMS).toList()
    }
}


