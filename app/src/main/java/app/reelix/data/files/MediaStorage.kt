package app.reelix.data.files

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import app.reelix.core.ReelixLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.UUID

data class ImportedMedia(
    val file: File,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val originalFileName: String,
)

sealed interface MediaImportResult {
    data class Success(val media: ImportedMedia) : MediaImportResult
    data class UnsupportedFormat(val reason: String) : MediaImportResult
    data class FileTooLarge(val sizeBytes: Long, val maxBytes: Long) : MediaImportResult
    data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) : MediaImportResult
    data class Failure(val error: String) : MediaImportResult
}

interface MediaStorage {
    suspend fun importFromUri(
        uri: Uri,
        contentResolver: ContentResolver,
        maxBytes: Long,
    ): MediaImportResult

    fun deleteFile(path: String)
    fun deleteAll()
    fun totalBytes(): Long
    fun getMediaDirectory(): File
}

class AppPrivateMediaStorage(
    private val context: Context,
    private val mediaDir: File = File(context.filesDir, "media"),
) : MediaStorage {

    init {
        mediaDir.mkdirs()
    }

    override fun getMediaDirectory(): File = mediaDir

    override suspend fun importFromUri(
        uri: Uri,
        contentResolver: ContentResolver,
        maxBytes: Long,
    ): MediaImportResult = withContext(Dispatchers.IO) {
        val originalName = queryDisplayName(contentResolver, uri) ?: "shared_media_${System.currentTimeMillis()}"
        val reportedSize = queryFileSize(contentResolver, uri)

        if (reportedSize != null && reportedSize > maxBytes) {
            return@withContext MediaImportResult.FileTooLarge(reportedSize, maxBytes)
        }

        // Check device storage availability: require at least file size + 50MB reserve
        val requiredSpace = (reportedSize ?: 20_000_000L) + MIN_FREE_STORAGE_RESERVE_BYTES
        val usableSpace = mediaDir.usableSpace
        if (usableSpace < requiredSpace) {
            return@withContext MediaImportResult.InsufficientStorage(requiredSpace, usableSpace)
        }

        val tempFile = File(mediaDir, "import_${UUID.randomUUID()}.tmp")
        try {
            var stream: InputStream? = null
            try {
                stream = contentResolver.openInputStream(uri)
            } catch (e: SecurityException) {
                return@withContext MediaImportResult.Failure("Permission denied reading shared file: ${e.message}")
            } catch (e: Exception) {
                return@withContext MediaImportResult.Failure("Failed to open shared media: ${e.message}")
            }

            if (stream == null) {
                return@withContext MediaImportResult.Failure("Could not read shared media stream")
            }

            var totalCopied = 0L
            val buffer = ByteArray(BUFFER_SIZE)
            FileOutputStream(tempFile).use { out ->
                stream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        totalCopied += read
                        if (totalCopied > maxBytes) {
                            tempFile.delete()
                            return@withContext MediaImportResult.FileTooLarge(totalCopied, maxBytes)
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }

            if (totalCopied == 0L) {
                tempFile.delete()
                return@withContext MediaImportResult.Failure("Shared file was empty (0 bytes)")
            }

            // Identify MIME type reliably
            val resolvedMime = resolveMimeType(contentResolver, uri, originalName, tempFile)
            if (!isSupportedMime(resolvedMime)) {
                tempFile.delete()
                return@withContext MediaImportResult.UnsupportedFormat(
                    "Format '$resolvedMime' is not supported. Please share standard video or audio files (e.g. MP4, MOV, MKV, M4A, MP3, WAV).",
                )
            }

            val ext = extensionForMime(resolvedMime, originalName)
            val destFile = File(mediaDir, "media_${UUID.randomUUID()}.$ext")
            if (!tempFile.renameTo(destFile)) {
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }

            // Extract duration safely if possible
            val durationMs = extractDurationMs(destFile)

            MediaImportResult.Success(
                ImportedMedia(
                    file = destFile,
                    mimeType = resolvedMime,
                    sizeBytes = destFile.length(),
                    durationMs = durationMs,
                    originalFileName = originalName,
                ),
            )
        } catch (e: Exception) {
            tempFile.delete()
            ReelixLog.w("Media import failed", e)
            MediaImportResult.Failure("Import failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun deleteFile(path: String) {
        val f = File(path)
        if (f.canonicalPath.startsWith(mediaDir.canonicalPath + File.separator)) {
            f.delete()
        }
    }

    override fun deleteAll() {
        mediaDir.listFiles()?.forEach { it.delete() }
    }

    override fun totalBytes(): Long =
        mediaDir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun resolveMimeType(
        resolver: ContentResolver,
        uri: Uri,
        fileName: String,
        file: File,
    ): String {
        val declared = runCatching { resolver.getType(uri) }.getOrNull()
        if (!declared.isNullOrBlank() && declared != "application/octet-stream") {
            return declared.lowercase(Locale.ROOT)
        }

        val nameLower = fileName.lowercase(Locale.ROOT)
        return when {
            nameLower.endsWith(".mp4") -> "video/mp4"
            nameLower.endsWith(".mov") -> "video/quicktime"
            nameLower.endsWith(".mkv") -> "video/x-matroska"
            nameLower.endsWith(".webm") -> "video/webm"
            nameLower.endsWith(".3gp") -> "video/3gpp"
            nameLower.endsWith(".m4a") -> "audio/mp4"
            nameLower.endsWith(".mp3") -> "audio/mpeg"
            nameLower.endsWith(".wav") -> "audio/wav"
            nameLower.endsWith(".aac") -> "audio/aac"
            nameLower.endsWith(".ogg") -> "audio/ogg"
            nameLower.endsWith(".flac") -> "audio/flac"
            else -> inspectMagicBytes(file) ?: "application/octet-stream"
        }
    }

    private fun inspectMagicBytes(file: File): String? {
        if (!file.exists() || file.length() < 12) return null
        return runCatching {
            val header = ByteArray(12)
            file.inputStream().use { it.read(header) }
            when {
                // MP4/MOV: bytes 4-7 are 'ftyp'
                header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte() -> "video/mp4"
                // WAV: RIFF...WAVE
                header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() -> "audio/wav"
                // MP3: ID3 or sync word
                header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte() -> "audio/mpeg"
                (header[0].toInt() and 0xFF) == 0xFF && (header[1].toInt() and 0xE0) == 0xE0 -> "audio/mpeg"
                // OGG: OggS
                header[0] == 'O'.code.toByte() && header[1] == 'g'.code.toByte() &&
                    header[2] == 'g'.code.toByte() && header[3] == 'S'.code.toByte() -> "audio/ogg"
                // Matroska / MKV: 0x1A 0x45 0xDF 0xA3
                header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                    header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte() -> "video/x-matroska"
                else -> null
            }
        }.getOrNull()
    }

    private fun isSupportedMime(mime: String): Boolean =
        mime.startsWith("video/") || mime.startsWith("audio/")

    private fun extensionForMime(mime: String, originalName: String): String {
        val origExt = originalName.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 5 }
        if (origExt != null) return origExt.lowercase(Locale.ROOT)
        return when (mime) {
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            "video/x-matroska" -> "mkv"
            "video/webm" -> "webm"
            "audio/mp4" -> "m4a"
            "audio/mpeg" -> "mp3"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/aac" -> "aac"
            "audio/ogg" -> "ogg"
            "audio/flac" -> "flac"
            else -> if (mime.startsWith("video/")) "mp4" else "m4a"
        }
    }

    private fun extractDurationMs(file: File): Long? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            dur?.toLongOrNull()
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun queryFileSize(resolver: ContentResolver, uri: Uri): Long? {
        if (uri.scheme == "file") return runCatching { File(uri.path.orEmpty()).length() }.getOrNull()
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
                } else null
            }
        }.getOrNull()
    }

    companion object {
        private const val BUFFER_SIZE = 32 * 1024
        private const val MIN_FREE_STORAGE_RESERVE_BYTES = 50L * 1024 * 1024 // 50MB reserve
    }
}

