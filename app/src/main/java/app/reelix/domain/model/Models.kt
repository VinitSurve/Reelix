package app.reelix.domain.model

import java.io.File

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
) {
    val isTranscribed: Boolean get() = transcriptionStatus == StageStatus.SUCCEEDED
    val isAiEnriched: Boolean get() = analysisStatus == StageStatus.SUCCEEDED
    val isAudioExtracted: Boolean get() = audioExtractionStatus == StageStatus.SUCCEEDED
    val isNeedsAttention: Boolean get() = processingStatus == ProcessingStatus.NEEDS_ATTENTION

    /** Honest status badge label reflecting actual pipeline achievements rather than generic "Processed". */
    val primaryStatusLabel: String
        get() = when {
            isNeedsAttention -> "Needs attention"
            isAiEnriched -> "AI enriched"
            isTranscribed -> "Transcribed"
            isAudioExtracted -> "Audio extracted"
            isMediaAvailable -> "Media available"
            extractionStatus == StageStatus.SUCCEEDED -> "Metadata extracted"
            processingStatus.isAwaiting -> processingStatus.label
            else -> "Saved"
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
    val tags: List<TagInfo>,
    val stages: List<StageInfo>,
    val events: List<ProcessingEventInfo>,
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

/** Editable, user-owned metadata. */
data class UserMetadata(
    val title: String?,
    val caption: String?,
    val transcript: String?,
    val notes: String?,
    val category: String?,
    val tags: List<String>,
)
