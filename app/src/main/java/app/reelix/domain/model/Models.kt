package app.reelix.domain.model

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** A validated Instagram link. [originalUrl] is preserved verbatim; [normalizedUrl] is used for de-duplication. */
data class InstagramLink(
    val originalUrl: String,
    val normalizedUrl: String,
    val contentType: ContentType,
    val shortcode: String?,
)

/** Result of trying to save one link. */
sealed interface LinkSaveOutcome {
    val link: InstagramLink
    val itemId: String

    data class Saved(override val link: InstagramLink, override val itemId: String) : LinkSaveOutcome
    data class Duplicate(override val link: InstagramLink, override val itemId: String) : LinkSaveOutcome
}

/** Result of trying to save shared media. */
sealed interface MediaSaveOutcome {
    val fileName: String

    data class Saved(val itemId: String, override val fileName: String, val mimeType: String, val sizeBytes: Long) : MediaSaveOutcome
    data class Duplicate(val itemId: String, override val fileName: String) : MediaSaveOutcome
    data class Failed(override val fileName: String, val reason: String) : MediaSaveOutcome
}

/** Result of handling a share intent or a pasted block of text. */
sealed interface ShareSaveResult {
    data object NothingShared : ShareSaveResult
    data object NoInstagramLink : ShareSaveResult
    data class Handled(val outcomes: List<LinkSaveOutcome>) : ShareSaveResult {
        val saved: List<LinkSaveOutcome.Saved> get() = outcomes.filterIsInstance<LinkSaveOutcome.Saved>()
        val duplicates: List<LinkSaveOutcome.Duplicate> get() = outcomes.filterIsInstance<LinkSaveOutcome.Duplicate>()
    }
    data class MediaHandled(val outcomes: List<MediaSaveOutcome>) : ShareSaveResult {
        val saved: List<MediaSaveOutcome.Saved> get() = outcomes.filterIsInstance<MediaSaveOutcome.Saved>()
        val duplicates: List<MediaSaveOutcome.Duplicate> get() = outcomes.filterIsInstance<MediaSaveOutcome.Duplicate>()
        val failed: List<MediaSaveOutcome.Failed> get() = outcomes.filterIsInstance<MediaSaveOutcome.Failed>()
    }
    data class Error(val message: String) : ShareSaveResult
}

data class LibraryCounts(
    val total: Int = 0,
    val awaiting: Int = 0,
    val mediaAvailable: Int = 0,
    val transcribed: Int = 0,
    val aiEnriched: Int = 0,
    val needsAttention: Int = 0,
)

enum class SortOrder(val label: String) { NEWEST("Newest first"), OLDEST("Oldest first") }

data class ItemQuery(
    val statusFilter: StatusFilter = StatusFilter.ALL,
    val contentTypes: Set<ContentType> = ContentType.entries.toSet(),
    val sortOrder: SortOrder = SortOrder.NEWEST,
)

/** Compact projection used by lists and search results with honest status semantics. */
data class ItemListEntry(
    val id: String,
    val originalUrl: String,
    val contentType: ContentType,
    val displayTitle: String?,
    val captionPreview: String?,
    val summary: String?,
    val tags: List<String>,
    val category: String?,
    val processingStatus: ProcessingStatus,
    val extractionStatus: StageStatus,
    val audioExtractionStatus: StageStatus,
    val transcriptionStatus: StageStatus,
    val analysisStatus: StageStatus,
    val isMediaAvailable: Boolean,
    val thumbnailPath: String?,
    val createdAt: Long,
    val generation: Int = 1,
    // Multi-media (v4)
    val postType: String = "REEL",
    val mediaItemCount: Int = 1,
) {
    val isTranscribed: Boolean get() = transcriptionStatus == StageStatus.SUCCEEDED
    val isAiEnriched: Boolean get() = analysisStatus == StageStatus.SUCCEEDED
    val isAudioExtracted: Boolean get() = audioExtractionStatus == StageStatus.SUCCEEDED
    val isNeedsAttention: Boolean get() = processingStatus == ProcessingStatus.NEEDS_ATTENTION

    /** Honest status badge label reflecting actual pipeline achievements rather than generic "Processed". */
    val primaryStatusLabel: String
        get() = when {
            processingStatus == ProcessingStatus.NEEDS_ATTENTION -> {
                if (!isMediaAvailable) "Needs Attention" else "Needs Attention"
            }
            processingStatus == ProcessingStatus.COMPLETED -> {
                if (isAiEnriched) "Completed" else "Completed without AI analysis"
            }
            processingStatus == ProcessingStatus.PROCESSING -> {
                when {
                    analysisStatus == StageStatus.RUNNING -> "AI analyzing"
                    transcriptionStatus == StageStatus.RUNNING -> "Transcribing"
                    audioExtractionStatus == StageStatus.RUNNING -> "Processing"
                    extractionStatus == StageStatus.RUNNING -> "Downloading"
                    !isMediaAvailable -> "Downloading"
                    else -> "Processing"
                }
            }
            processingStatus == ProcessingStatus.UPLOADING -> "Uploading"
            processingStatus == ProcessingStatus.WAITING_UPLOAD -> "Waiting to upload"
            processingStatus == ProcessingStatus.QUEUED -> "Queued"
            processingStatus == ProcessingStatus.WAITING_RETRY -> "Retry scheduled"
            processingStatus == ProcessingStatus.SAVED_LOCALLY -> "Saved locally"
            isAiEnriched -> "Completed"
            isTranscribed -> "Completed without AI analysis"
            else -> processingStatus.label
        }
}

data class TagInfo(val id: Long, val name: String, val source: TagSource)

data class StageInfo(
    val stage: ProcessingStage,
    val status: StageStatus,
    val attempts: Int,
    val updatedAt: Long,
    val nextRetryAt: Long?,
    val lastError: String?,
)

data class ProcessingEventInfo(
    val stage: ProcessingStage?,
    val status: String,
    val message: String?,
    val createdAt: Long,
)

data class AiResultInfo(
    val summary: String?,
    val detailedSummary: String?,
    val mainTopic: String?,
    val keyTakeaways: List<String>,
    val importantConcepts: List<String>,
    val actionableSteps: List<String>,
    val keywords: List<String>,
    val category: String?,
    val modelName: String?,
    val generatedAt: Long,
)

data class MediaFileInfo(
    val path: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val originalFileName: String?,
    val audioPath: String?,
    val audioSizeBytes: Long?,
) {
    val fileExists: Boolean get() = File(path).exists()
    val audioFileExists: Boolean get() = audioPath?.let { File(it).exists() } ?: false
}

/** Everything the detail screen needs, grouped by provenance. */
data class ItemDetails(
    val id: String,
    val originalUrl: String,
    val normalizedUrl: String,
    val contentType: ContentType,
    val copyIndex: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val lastProcessedAt: Long?,
    val processingStatus: ProcessingStatus,
    val extractionStatus: StageStatus,
    val audioExtractionStatus: StageStatus,
    val transcriptionStatus: StageStatus,
    val analysisStatus: StageStatus,
    val lastError: String?,
    val retryCount: Int,
    // Media Information
    val mediaInfo: MediaFileInfo?,
    // User-provided
    val userTitle: String?,
    val userCaption: String?,
    val userTranscript: String?,
    val userNotes: String?,
    val userCategory: String?,
    // Extracted
    val extractedTitle: String?,
    val extractedCaption: String?,
    val authorName: String?,
    val thumbnailPath: String?,
    // AI-generated
    val aiResult: AiResultInfo?,
    val generatedTranscript: String?,
    val ocrText: String? = null,
    val tags: List<TagInfo>,
    val stages: List<StageInfo>,
    val events: List<ProcessingEventInfo>,
    val generation: Int = 1,
    // Multi-media (v4)
    val postType: String = "REEL",
    val mediaItems: List<MediaItemInfo> = emptyList(),
) {
    val displayTitle: String?
        get() = userTitle?.takeIf { it.isNotBlank() }
            ?: extractedTitle?.takeIf { it.isNotBlank() }
            ?: aiResult?.mainTopic?.takeIf { it.isNotBlank() }
            ?: mediaInfo?.originalFileName?.takeIf { it.isNotBlank() }

    val effectiveTranscript: String?
        get() = userTranscript?.takeIf { it.isNotBlank() } ?: generatedTranscript?.takeIf { it.isNotBlank() }

    val isMediaAvailable: Boolean get() = mediaInfo?.fileExists == true
    val isTranscribed: Boolean get() = transcriptionStatus == StageStatus.SUCCEEDED
    val isAiEnriched: Boolean get() = analysisStatus == StageStatus.SUCCEEDED
    val isNeedsAttention: Boolean get() = processingStatus == ProcessingStatus.NEEDS_ATTENTION
}

// ─── Multi-media types ───────────────────────────────────────────────────────

/** Type of a single slide/item in a multi-media post. */
enum class MediaItemType { IMAGE, VIDEO, UNKNOWN }

/** Serialisable info about one slide in a carousel or single-media post. */
data class MediaItemInfo(
    val index: Int,
    val mediaType: MediaItemType,
    val fileName: String?,
    val mimeType: String?,
    val driveFileId: String?,
    val sizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val contentHash: String?,
    val ocrText: String?,
    val localPath: String?,
    val originalUrl: String?,
    val thumbnailUrl: String?,
) {
    companion object {
        fun fromJson(obj: JSONObject): MediaItemInfo = MediaItemInfo(
            index = obj.optInt("index", 0),
            mediaType = when (obj.optString("media_type", "UNKNOWN").uppercase()) {
                "IMAGE" -> MediaItemType.IMAGE
                "VIDEO" -> MediaItemType.VIDEO
                else -> MediaItemType.UNKNOWN
            },
            fileName = obj.optString("file_name").takeIf { it.isNotBlank() },
            mimeType = obj.optString("mime_type").takeIf { it.isNotBlank() },
            driveFileId = obj.optString("drive_file_id").takeIf { it.isNotBlank() },
            sizeBytes = if (obj.has("size_bytes")) obj.optLong("size_bytes") else null,
            width = if (obj.has("width")) obj.optInt("width") else null,
            height = if (obj.has("height")) obj.optInt("height") else null,
            durationMs = if (obj.has("duration_ms")) obj.optLong("duration_ms") else null,
            contentHash = obj.optString("content_hash").takeIf { it.isNotBlank() },
            ocrText = obj.optString("ocr_text").takeIf { it.isNotBlank() },
            localPath = obj.optString("local_path").takeIf { it.isNotBlank() },
            originalUrl = obj.optString("original_url").takeIf { it.isNotBlank() },
            thumbnailUrl = obj.optString("thumbnail_url").takeIf { it.isNotBlank() },
        )

        fun listFromJson(json: String?): List<MediaItemInfo> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { fromJson(it) } }
            } catch (_: Exception) { emptyList() }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("index", index)
        put("media_type", mediaType.name)
        fileName?.let { put("file_name", it) }
        mimeType?.let { put("mime_type", it) }
        driveFileId?.let { put("drive_file_id", it) }
        sizeBytes?.let { put("size_bytes", it) }
        width?.let { put("width", it) }
        height?.let { put("height", it) }
        durationMs?.let { put("duration_ms", it) }
        contentHash?.let { put("content_hash", it) }
        ocrText?.let { put("ocr_text", it) }
        localPath?.let { put("local_path", it) }
        originalUrl?.let { put("original_url", it) }
        thumbnailUrl?.let { put("thumbnail_url", it) }
    }
}

fun List<MediaItemInfo>.toJsonString(): String =
    JSONArray(map { it.toJson() }).toString()

/** Editable, user-owned metadata. */
data class UserMetadata(
    val title: String?,
    val caption: String?,
    val transcript: String?,
    val notes: String?,
    val category: String?,
    val tags: List<String>,
)
