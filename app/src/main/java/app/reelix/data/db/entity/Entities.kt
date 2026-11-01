package app.reelix.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import app.reelix.domain.model.ContentType
import app.reelix.domain.model.ProcessingStage
import app.reelix.domain.model.ProcessingStatus
import app.reelix.domain.model.SaveSource
import app.reelix.domain.model.StageStatus
import app.reelix.domain.model.TagSource

/**
 * A saved Instagram link or imported media item. Fields are grouped by provenance:
 * - user-provided: [title], [userCaption], [userTranscript], [userNotes], [category]
 * - extracted/media: [extractedTitle], [caption], [authorName], [thumbnailPath], [thumbnailUrl],
 *   [mediaPath], [mediaMimeType], [mediaSizeBytes], [mediaDurationMs], [mediaFileName],
 *   [audioPath], [audioSizeBytes], [audioExtractionStatus]
 * - AI-generated data lives in [ProcessingResultEntity].
 */
@Entity(
    tableName = "saved_items",
    indices = [
        Index(value = ["normalizedUrl", "copyIndex"], unique = true),
        Index(value = ["processingStatus", "nextAttemptAt"]),
        Index(value = ["createdAt"]),
    ],
)
data class SavedItemEntity(
    @PrimaryKey val id: String,
    val originalUrl: String,
    val normalizedUrl: String,
    val shortcode: String?,
    val contentType: ContentType,
    val copyIndex: Int = 0,
    val source: SaveSource = SaveSource.SHARE,
    // User-provided
    val title: String? = null,
    val userCaption: String? = null,
    val userTranscript: String? = null,
    val userNotes: String? = null,
    val category: String? = null,
    // Extracted & media
    val extractedTitle: String? = null,
    val caption: String? = null,
    val authorName: String? = null,
    val thumbnailPath: String? = null,
    val thumbnailUrl: String? = null,
    val mediaAvailable: Boolean = false,
    val mediaPath: String? = null,
    val mediaMimeType: String? = null,
    val mediaSizeBytes: Long? = null,
    val mediaDurationMs: Long? = null,
    val mediaFileName: String? = null,
    val audioPath: String? = null,
    val audioSizeBytes: Long? = null,
    val audioExtractionStatus: StageStatus = StageStatus.PENDING,
    // Processing state
    val processingStatus: ProcessingStatus = ProcessingStatus.QUEUED,
    val extractionStatus: StageStatus = StageStatus.PENDING,
    val transcriptionStatus: StageStatus = StageStatus.PENDING,
    val analysisStatus: StageStatus = StageStatus.PENDING,
    val createdAt: Long,
    val updatedAt: Long,
    val lastProcessedAt: Long? = null,
    val nextAttemptAt: Long? = null,
    val lastError: String? = null,
    val retryCount: Int = 0,
)

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "saved_item_tags",
    primaryKeys = ["savedItemId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = SavedItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["savedItemId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tagId")],
)
data class SavedItemTagEntity(
    val savedItemId: String,
    val tagId: Long,
    val source: TagSource,
    val createdAt: Long,
)

@Entity(
    tableName = "processing_jobs",
    foreignKeys = [
        ForeignKey(
            entity = SavedItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["savedItemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["savedItemId", "stage"], unique = true), Index("status")],
)
data class ProcessingJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val savedItemId: String,
    val stage: ProcessingStage,
    val status: StageStatus = StageStatus.PENDING,
    val attempts: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val nextRetryAt: Long? = null,
    val lastError: String? = null,
    val message: String? = null,
)

@Entity(
    tableName = "processing_results",
    foreignKeys = [
        ForeignKey(
            entity = SavedItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["savedItemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["savedItemId", "generatedAt"])],
)
data class ProcessingResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val savedItemId: String,
    val stage: ProcessingStage,
    val transcript: String? = null,
    val summary: String? = null,
    val detailedSummary: String? = null,
    val mainTopic: String? = null,
    /** Newline-separated. */
    val keyTakeaways: String? = null,
    /** Newline-separated. */
    val importantConcepts: String? = null,
    /** Newline-separated. */
    val actionableSteps: String? = null,
    /** Comma-separated. */
    val keywords: String? = null,
    /** Comma-separated tag suggestions. */
    val suggestedTags: String? = null,
    val category: String? = null,
    val providerId: String,
    val modelName: String?,
    val generatedAt: Long,
)

@Entity(
    tableName = "processing_events",
    foreignKeys = [
        ForeignKey(
            entity = SavedItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["savedItemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["savedItemId", "createdAt"])],
)
data class ProcessingEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val savedItemId: String,
    val stage: ProcessingStage?,
    val status: String,
    val message: String?,
    val createdAt: Long,
)

/** Full-text index over all searchable fields including transcripts, detailed summaries & concepts. */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["item_id"])
@Entity(tableName = "saved_item_fts")
data class SavedItemFtsEntity(
    @ColumnInfo(name = "item_id") val itemId: String,
    val title: String,
    val caption: String,
    val transcript: String,
    val summary: String,
    val detailedSummary: String,
    val tags: String,
    val category: String,
    val notes: String,
    val keywords: String,
    val concepts: String,
    val actionableSteps: String,
)
