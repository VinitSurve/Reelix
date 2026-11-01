package app.reelix.core.url

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens only validated Instagram HTTP(S) links externally. Rejects custom schemes, file://,
 * javascript:, intent:// and non-Instagram hosts.
 */
object SafeUrlOpener {

    fun openInstagramUrl(context: Context, rawUrl: String): Boolean {
        val parsed = InstagramUrlParser.parse(rawUrl) ?: return false
        val target = if (parsed.originalUrl.startsWith("http://", ignoreCase = true) ||
            parsed.originalUrl.startsWith("https://", ignoreCase = true)
        ) {
            parsed.originalUrl
        } else {
            parsed.normalizedUrl
        }
        val uri = runCatching { Uri.parse(target) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return false
        if (!InstagramUrlParser.isInstagramHost(uri.host)) return false

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

