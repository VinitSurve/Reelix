package app.reelix.data.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.reelix.data.db.entity.SavedItemEntity
import app.reelix.domain.model.ContentType
import app.reelix.domain.model.ProcessingStatus
import app.reelix.domain.model.StageStatus
import kotlinx.coroutines.flow.Flow

data class SavedItemListRow(
    @Embedded val item: SavedItemEntity,
    val latestSummary: String?,
    val latestTopic: String?,
    val aiCategory: String?,
    val tagNames: String?,
)

data class CountsRow(
    val total: Int,
    val awaiting: Int,
    val mediaAvailable: Int,
    val transcribed: Int,
    val aiEnriched: Int,
    val attention: Int,
)

private const val LIST_COLUMNS = """
    si.*,
    (SELECT pr.summary FROM processing_results pr
        WHERE pr.savedItemId = si.id AND pr.summary IS NOT NULL
        ORDER BY pr.generatedAt DESC, pr.id DESC LIMIT 1) AS latestSummary,
    (SELECT pr.mainTopic FROM processing_results pr
        WHERE pr.savedItemId = si.id AND pr.mainTopic IS NOT NULL
        ORDER BY pr.generatedAt DESC, pr.id DESC LIMIT 1) AS latestTopic,
    (SELECT pr.category FROM processing_results pr
        WHERE pr.savedItemId = si.id AND pr.category IS NOT NULL
        ORDER BY pr.generatedAt DESC, pr.id DESC LIMIT 1) AS aiCategory,
    (SELECT group_concat(t.name, ',') FROM saved_item_tags st
        JOIN tags t ON t.id = st.tagId WHERE st.savedItemId = si.id) AS tagNames
"""

private const val ORDERING = """
    ORDER BY
        CASE WHEN :ascending = 1 THEN si.createdAt END ASC,
        CASE WHEN :ascending = 0 THEN si.createdAt END DESC,
        si.id ASC
"""

@Dao
interface SavedItemDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: SavedItemEntity)

    @Update
    suspend fun update(item: SavedItemEntity)

    @Query("SELECT * FROM saved_items WHERE id = :id")
    suspend fun getById(id: String): SavedItemEntity?

    @Query("SELECT * FROM saved_items WHERE id = :id")
    fun observeById(id: String): Flow<SavedItemEntity?>

    @Query("SELECT * FROM saved_items WHERE normalizedUrl = :normalizedUrl ORDER BY copyIndex ASC LIMIT 1")
    suspend fun findByNormalizedUrl(normalizedUrl: String): SavedItemEntity?

    @Query("SELECT MAX(copyIndex) FROM saved_items WHERE normalizedUrl = :normalizedUrl")
    suspend fun maxCopyIndex(normalizedUrl: String): Int?

    @Query("SELECT COUNT(*) FROM saved_items WHERE id = :id")
    suspend fun exists(id: String): Int

    @Query("DELETE FROM saved_items WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT id FROM saved_items")
    suspend fun allIds(): List<String>

    @Query("SELECT thumbnailPath FROM saved_items WHERE thumbnailPath IS NOT NULL")
    suspend fun allThumbnailPaths(): List<String>

    @Query("SELECT mediaPath FROM saved_items WHERE mediaPath IS NOT NULL")
    suspend fun allMediaPaths(): List<String>

    @Query("SELECT audioPath FROM saved_items WHERE audioPath IS NOT NULL")
    suspend fun allAudioPaths(): List<String>

    @Query("SELECT SUM(mediaSizeBytes) FROM saved_items WHERE mediaSizeBytes IS NOT NULL")
    suspend fun totalMediaBytes(): Long?

    @Query(
        """
        SELECT COUNT(*) AS total,
            COALESCE(SUM(CASE WHEN processingStatus IN ('QUEUED','PROCESSING','WAITING_RETRY') THEN 1 ELSE 0 END), 0) AS awaiting,
            COALESCE(SUM(CASE WHEN mediaAvailable = 1 OR mediaPath IS NOT NULL THEN 1 ELSE 0 END), 0) AS mediaAvailable,
            COALESCE(SUM(CASE WHEN transcriptionStatus = 'SUCCEEDED' THEN 1 ELSE 0 END), 0) AS transcribed,
            COALESCE(SUM(CASE WHEN analysisStatus = 'SUCCEEDED' THEN 1 ELSE 0 END), 0) AS aiEnriched,
            COALESCE(SUM(CASE WHEN processingStatus = 'NEEDS_ATTENTION' THEN 1 ELSE 0 END), 0) AS attention
        FROM saved_items
        """,
    )
    fun observeCounts(): Flow<CountsRow>

    @Query(
        "SELECT $LIST_COLUMNS FROM saved_items si " +
            "WHERE si.contentType IN (:types) " +
            "AND (" +
            "   :filterMode = 'ALL' " +
            "   OR (:filterMode = 'MEDIA' AND (si.mediaAvailable = 1 OR si.mediaPath IS NOT NULL)) " +
            "   OR (:filterMode = 'TRANSCRIBED' AND si.transcriptionStatus = 'SUCCEEDED') " +
            "   OR (:filterMode = 'AI' AND si.analysisStatus = 'SUCCEEDED') " +
            "   OR (:filterMode = 'AWAITING' AND si.processingStatus IN ('QUEUED','PROCESSING','WAITING_RETRY')) " +
            "   OR (:filterMode = 'ATTENTION' AND si.processingStatus = 'NEEDS_ATTENTION') " +
            ") " +
            ORDERING + " LIMIT :limit",
    )
    fun observeFilteredList(
        filterMode: String,
        types: Collection<ContentType>,
        ascending: Boolean,
        limit: Int,
    ): Flow<List<SavedItemListRow>>

    @Query(
        "SELECT $LIST_COLUMNS FROM saved_items si " +
            "WHERE si.id IN (SELECT item_id FROM saved_item_fts WHERE saved_item_fts MATCH :match) " +
            "AND si.contentType IN (:types) " +
            "AND (" +
            "   :filterMode = 'ALL' " +
            "   OR (:filterMode = 'MEDIA' AND (si.mediaAvailable = 1 OR si.mediaPath IS NOT NULL)) " +
            "   OR (:filterMode = 'TRANSCRIBED' AND si.transcriptionStatus = 'SUCCEEDED') " +
            "   OR (:filterMode = 'AI' AND si.analysisStatus = 'SUCCEEDED') " +
            "   OR (:filterMode = 'AWAITING' AND si.processingStatus IN ('QUEUED','PROCESSING','WAITING_RETRY')) " +
            "   OR (:filterMode = 'ATTENTION' AND si.processingStatus = 'NEEDS_ATTENTION') " +
            ") " +
            ORDERING + " LIMIT :limit",
    )
    fun searchFiltered(
        match: String,
        filterMode: String,
        types: Collection<ContentType>,
        ascending: Boolean,
        limit: Int,
    ): Flow<List<SavedItemListRow>>

    // ---- Queue management ----

    @Query(
        """
        SELECT id FROM saved_items
        WHERE processingStatus = 'QUEUED'
           OR (processingStatus = 'WAITING_RETRY' AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now))
        ORDER BY createdAt ASC LIMIT :limit
        """,
    )
    suspend fun dueItemIds(now: Long, limit: Int): List<String>

    @Query(
        """
        SELECT COUNT(*) FROM saved_items
        WHERE processingStatus = 'QUEUED'
           OR (processingStatus = 'WAITING_RETRY' AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now))
        """,
    )
    suspend fun countDue(now: Long): Int

    @Query("SELECT MIN(nextAttemptAt) FROM saved_items WHERE processingStatus = 'WAITING_RETRY' AND nextAttemptAt > :now")
    suspend fun earliestFutureRetry(now: Long): Long?

    /** Atomically claims a due item for processing. Returns 1 if claimed. */
    @Query(
        """
        UPDATE saved_items SET processingStatus = 'PROCESSING', updatedAt = :now
        WHERE id = :id AND (
            processingStatus = 'QUEUED'
            OR (processingStatus = 'WAITING_RETRY' AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now))
        )
        """,
    )
    suspend fun claim(id: String, now: Long): Int

    /** Items left in PROCESSING by a killed process are returned to the queue. */
    @Query("UPDATE saved_items SET processingStatus = 'QUEUED', updatedAt = :now WHERE processingStatus = 'PROCESSING'")
    suspend fun resetInterrupted(now: Long): Int

    @Query(
        """
        UPDATE saved_items SET processingStatus = :status, nextAttemptAt = :nextAttemptAt,
            lastError = :lastError, updatedAt = :now
        WHERE id = :id
        """,
    )
    suspend fun updateProcessingStatus(
        id: String,
        status: ProcessingStatus,
        nextAttemptAt: Long?,
        lastError: String?,
        now: Long,
    )

    @Query(
        """
        UPDATE saved_items SET processingStatus = 'COMPLETED', nextAttemptAt = NULL, lastError = NULL,
            lastProcessedAt = :now, updatedAt = :now
        WHERE id = :id
        """,
    )
    suspend fun markCompleted(id: String, now: Long)

    @Query("UPDATE saved_items SET retryCount = retryCount + 1, updatedAt = :now WHERE id = :id")
    suspend fun incrementRetryCount(id: String, now: Long)

    @Query("UPDATE saved_items SET extractionStatus = :status, updatedAt = :now WHERE id = :id")
    suspend fun setExtractionStatus(id: String, status: StageStatus, now: Long)

    @Query("UPDATE saved_items SET audioExtractionStatus = :status, updatedAt = :now WHERE id = :id")
    suspend fun setAudioExtractionStatus(id: String, status: StageStatus, now: Long)

    @Query(
        """
        UPDATE saved_items SET audioPath = :audioPath, audioSizeBytes = :audioSizeBytes,
            audioExtractionStatus = :status, updatedAt = :now WHERE id = :id
        """,
    )
    suspend fun updateAudioExtraction(id: String, audioPath: String?, audioSizeBytes: Long?, status: StageStatus, now: Long)

    @Query("UPDATE saved_items SET transcriptionStatus = :status, updatedAt = :now WHERE id = :id")
    suspend fun setTranscriptionStatus(id: String, status: StageStatus, now: Long)

    @Query("UPDATE saved_items SET analysisStatus = :status, updatedAt = :now WHERE id = :id")
    suspend fun setAnalysisStatus(id: String, status: StageStatus, now: Long)

    @Query("UPDATE saved_items SET mediaAvailable = :available, updatedAt = :now WHERE id = :id")
    suspend fun setMediaAvailable(id: String, available: Boolean, now: Long)

    @Query(
        """
        UPDATE saved_items SET mediaAvailable = :available, mediaPath = :mediaPath,
            mediaMimeType = :mediaMimeType, mediaSizeBytes = :mediaSizeBytes,
            mediaDurationMs = :mediaDurationMs, mediaFileName = :mediaFileName,
            updatedAt = :now WHERE id = :id
        """,
    )
    suspend fun updateMediaDetails(
        id: String,
        available: Boolean,
        mediaPath: String?,
        mediaMimeType: String?,
        mediaSizeBytes: Long?,
        mediaDurationMs: Long?,
        mediaFileName: String?,
        now: Long,
    )

    @Query("SELECT id FROM saved_items WHERE analysisStatus = 'NOT_CONFIGURED' OR analysisStatus = 'FAILED'")
    suspend fun idsAwaitingAnalysisProvider(): List<String>
}
