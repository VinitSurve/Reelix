package app.reelix.integrations.transcription

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.reelix.core.ReelixLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface ModelStatus {
    data object NotInstalled : ModelStatus
    data class Ready(
        val fileName: String,
        val sizeBytes: Long,
        val modelFormat: String,
        val localPath: String,
    ) : ModelStatus
    data class Error(val message: String) : ModelStatus
}

sealed interface ModelImportOutcome {
    data class Success(val status: ModelStatus.Ready) : ModelImportOutcome
    data class InvalidFormat(val reason: String) : ModelImportOutcome
    data class FileTooSmall(val sizeBytes: Long) : ModelImportOutcome
    data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) : ModelImportOutcome
    data class Failure(val error: String) : ModelImportOutcome
}

interface TranscriptionModelManager {
    val modelStatus: Flow<ModelStatus>
    fun currentStatus(): ModelStatus
    suspend fun importModel(uri: Uri, contentResolver: ContentResolver): ModelImportOutcome
    suspend fun deleteModel(): Boolean
    fun getModelFile(): File?
}

class LocalWhisperModelManager(
    private val context: Context,
    private val modelsDir: File = File(context.filesDir, "models/transcription"),
) : TranscriptionModelManager {

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.NotInstalled)
    override val modelStatus: Flow<ModelStatus> = _status.asStateFlow()

    init {
        modelsDir.mkdirs()
        refreshStatus()
    }

    override fun currentStatus(): ModelStatus = _status.value

    override fun getModelFile(): File? {
        val f = File(modelsDir, MODEL_FILE_NAME)
        return if (f.exists() && f.length() >= MIN_MODEL_SIZE_BYTES) f else null
    }

    fun refreshStatus() {
        val file = File(modelsDir, MODEL_FILE_NAME)
        if (!file.exists()) {
            _status.value = ModelStatus.NotInstalled
            return
        }

        if (file.length() < MIN_MODEL_SIZE_BYTES) {
            _status.value = ModelStatus.Error("Model file is incomplete (${file.length() / 1024} KB)")
            return
        }

        val format = detectModelFormat(file)
        if (format == null) {
            _status.value = ModelStatus.Error("File is not a valid GGML/GGUF Whisper model")
            return
        }

        _status.value = ModelStatus.Ready(
            fileName = file.name,
            sizeBytes = file.length(),
            modelFormat = format,
            localPath = file.absolutePath,
        )
    }

    override suspend fun importModel(uri: Uri, contentResolver: ContentResolver): ModelImportOutcome = withContext(Dispatchers.IO) {
        val originalName = queryDisplayName(contentResolver, uri) ?: "whisper_model.bin"
        val reportedSize = queryFileSize(contentResolver, uri)

        if (reportedSize != null && reportedSize < MIN_MODEL_SIZE_BYTES) {
            return@withContext ModelImportOutcome.FileTooSmall(reportedSize)
        }

        val requiredSpace = (reportedSize ?: 100_000_000L) + 50L * 1024 * 1024
        val usableSpace = modelsDir.usableSpace
        if (usableSpace < requiredSpace) {
            return@withContext ModelImportOutcome.InsufficientStorage(requiredSpace, usableSpace)
        }

        modelsDir.mkdirs()
        val tempFile = File(modelsDir, "model_import.tmp")
        try {
            var stream: InputStream? = null
            try {
                stream = contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                return@withContext ModelImportOutcome.Failure("Cannot read model URI: ${e.message}")
            }

            if (stream == null) {
                return@withContext ModelImportOutcome.Failure("Could not open input stream")
            }

            var totalCopied = 0L
            val buffer = ByteArray(BUFFER_SIZE)
            FileOutputStream(tempFile).use { out ->
                stream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        totalCopied += read
                        out.write(buffer, 0, read)
                    }
                }
            }

            if (totalCopied < MIN_MODEL_SIZE_BYTES) {
                tempFile.delete()
                return@withContext ModelImportOutcome.FileTooSmall(totalCopied)
            }

            val format = detectModelFormat(tempFile)
            if (format == null) {
                tempFile.delete()
                return@withContext ModelImportOutcome.InvalidFormat(
                    "The selected file does not appear to be a valid Whisper GGML or GGUF model binary. Expected GGML magic (0x67676d6c) or GGUF header.",
                )
            }

            val destFile = File(modelsDir, MODEL_FILE_NAME)
            if (destFile.exists()) destFile.delete()
            if (!tempFile.renameTo(destFile)) {
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }

            refreshStatus()

            val readyStatus = ModelStatus.Ready(
                fileName = originalName,
                sizeBytes = destFile.length(),
                modelFormat = format,
                localPath = destFile.absolutePath,
            )
            _status.value = readyStatus
            ModelImportOutcome.Success(readyStatus)
        } catch (e: Exception) {
            tempFile.delete()
            ReelixLog.w("Model import failed", e)
            ModelImportOutcome.Failure("Model import failed: ${e.message}")
        }
    }

    override suspend fun deleteModel(): Boolean = withContext(Dispatchers.IO) {
        val file = File(modelsDir, MODEL_FILE_NAME)
        val deleted = if (file.exists()) file.delete() else false
        refreshStatus()
        deleted
    }

    private fun detectModelFormat(file: File): String? {
        if (!file.exists() || file.length() < 16) return null
        return runCatching {
            val header = ByteArray(8)
            file.inputStream().use { it.read(header) }
            val magic = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int

            when {
                // ggml (0x67676d6c) or ggmf (0x67676d66) or ggjt (0x67676a74)
                magic == 0x67676d6c || magic == 0x67676d66 || magic == 0x67676a74 -> "Whisper GGML"
                // GGUF (0x46554747 in little-endian = ASCII 'GGUF')
                header[0] == 'G'.code.toByte() && header[1] == 'G'.code.toByte() &&
                    header[2] == 'U'.code.toByte() && header[3] == 'F'.code.toByte() -> "Whisper GGUF"
                // If user imported a valid tiny/base model with generic binary header > 30MB
                file.length() > 30L * 1024 * 1024 -> "Whisper binary"
                else -> null
            }
        }.getOrNull()
    }

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
        const val MODEL_FILE_NAME = "whisper_model.bin"
        private const val BUFFER_SIZE = 64 * 1024
        // Tiny is ~75MB, Base is ~142MB. Set minimal sanity check to 10MB to avoid tiny corrupt files.
        const val MIN_MODEL_SIZE_BYTES = 10L * 1024 * 1024
    }
}

