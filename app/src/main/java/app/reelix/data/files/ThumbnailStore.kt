package app.reelix.data.files

import app.reelix.core.ReelixLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** Local storage for preview images obtained from public link-preview metadata. */
interface ThumbnailStore {
    /** Downloads an image and returns its local path, or null if it could not be stored. */
    suspend fun download(url: String, itemId: String): String?
    fun delete(path: String)
    fun deleteAll()
    fun totalBytes(): Long
}

class FileThumbnailStore(
    private val directory: File,
    private val client: OkHttpClient,
    private val maxBytes: Long = 3L * 1024 * 1024,
) : ThumbnailStore {

    override suspend fun download(url: String, itemId: String): String? = withContext(Dispatchers.IO) {
        val httpUrl = url.toHttpUrlOrNull() ?: return@withContext null
        if (!httpUrl.isHttps) return@withContext null
        val safeId = itemId.filter { it.isLetterOrDigit() || it == '-' }.take(64)
        if (safeId.isEmpty()) return@withContext null
        runCatching {
            directory.mkdirs()
            val request = Request.Builder().url(httpUrl).header("Accept", "image/*").build()
            client.newCall(request).execute().use { response ->
                val body = response.body ?: return@use null
                val type = response.header("Content-Type").orEmpty()
                if (!response.isSuccessful || !type.startsWith("image/")) return@use null
                if (body.contentLength() > maxBytes) return@use null
                val target = File(directory, "$safeId.img")
                val temp = File(directory, "$safeId.tmp")
                val total = body.byteStream().use { input -> copyLimited(input, temp) }
                if (total <= 0L || !temp.renameTo(target)) {
                    temp.delete()
                    null
                } else {
                    target.absolutePath
                }
            }
        }.onFailure { ReelixLog.w("Thumbnail download failed", it) }.getOrNull()
    }

    /** Copies at most [maxBytes]; returns bytes written, or -1 if the limit was exceeded. */
    private fun copyLimited(input: java.io.InputStream, target: File): Long {
        var total = 0L
        target.outputStream().use { output ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return -1
                output.write(buffer, 0, read)
            }
        }
        return total
    }

    override fun delete(path: String) {
        val file = File(path)
        // Only ever delete files inside our own directory.
        if (file.canonicalPath.startsWith(directory.canonicalPath + File.separator)) file.delete()
    }

    override fun deleteAll() {
        directory.listFiles()?.forEach { it.delete() }
    }

    override fun totalBytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L
}
