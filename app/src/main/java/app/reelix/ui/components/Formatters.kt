package app.reelix.ui.components

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formatters {
    private val dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

    fun formatDate(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateFormatter)

    fun formatDateTime(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateTimeFormatter)

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.ENGLISH, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.ENGLISH, "%.2f MB", mb)
        return String.format(Locale.ENGLISH, "%.2f GB", mb / 1024.0)
    }

    /** Formats the real URL path cleanly when no title or caption has been extracted or entered yet. */
    fun cleanUrlDisplay(url: String): String =
        url.substringBefore('?')
            .substringBefore('#')
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')
}

