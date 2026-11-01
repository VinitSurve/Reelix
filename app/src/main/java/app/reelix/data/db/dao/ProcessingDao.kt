package app.reelix.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.reelix.data.db.entity.ProcessingEventEntity
import app.reelix.data.db.entity.ProcessingJobEntity
import app.reelix.data.db.entity.ProcessingResultEntity
import app.reelix.data.db.entity.SavedItemFtsEntity
import app.reelix.domain.model.ProcessingStage
import app.reelix.domain.model.StageStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ProcessingDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertJobs(jobs: List<ProcessingJobEntity>)

    @Query("SELECT * FROM processing_jobs WHERE savedItemId = :itemId")
    suspend fun jobsFor(itemId: String): List<ProcessingJobEntity>

    @Query("SELECT * FROM processing_jobs WHERE savedItemId = :itemId")
    fun observeJobsFor(itemId: String): Flow<List<ProcessingJobEntity>>

    @Query(
        """
        UPDATE processing_jobs SET status = :status, attempts = :attempts, nextRetryAt = :nextRetryAt,
            lastError = :lastError, message = :message, updatedAt = :now
        WHERE savedItemId = :itemId AND stage = :stage
        """,
    )
    suspend fun updateJob(
        itemId: String,
        stage: ProcessingStage,
        status: StageStatus,
        attempts: Int,
        nextRetryAt: Long?,
        lastError: String?,
        message: String?,
        now: Long,
    )

    @Query(
        """
        UPDATE processing_jobs SET status = 'PENDING', attempts = 0, nextRetryAt = NULL, lastError = NULL, updatedAt = :now
        WHERE savedItemId = :itemId AND status NOT IN ('SUCCEEDED', 'SKIPPED')
        """,
    )
    suspend fun resetUnsuccessfulJobs(itemId: String, now: Long)

    @Query(
        """
        UPDATE processing_jobs SET status = 'PENDING', attempts = 0, nextRetryAt = NULL, lastError = NULL, updatedAt = :now
        WHERE savedItemId = :itemId AND stage IN (:stages)
        """,
    )
    suspend fun resetJobs(itemId: String, stages: Collection<ProcessingStage>, now: Long)

    @Insert
    suspend fun insertEvent(event: ProcessingEventEntity)

    @Query("SELECT * FROM processing_events WHERE savedItemId = :itemId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeEvents(itemId: String, limit: Int): Flow<List<ProcessingEventEntity>>

    @Query(
        """
        DELETE FROM processing_events WHERE savedItemId = :itemId AND id NOT IN
            (SELECT id FROM processing_events WHERE savedItemId = :itemId ORDER BY createdAt DESC, id DESC LIMIT :keep)
        """,
    )
    suspend fun trimEvents(itemId: String, keep: Int)

    @Insert
    suspend fun insertResult(result: ProcessingResultEntity): Long

    @Query("SELECT * FROM processing_results WHERE savedItemId = :itemId ORDER BY generatedAt DESC, id DESC")
    suspend fun resultsFor(itemId: String): List<ProcessingResultEntity>

    @Query("SELECT * FROM processing_results WHERE savedItemId = :itemId ORDER BY generatedAt DESC, id DESC")
    fun observeResultsFor(itemId: String): Flow<List<ProcessingResultEntity>>
}

@Dao
interface SearchDao {
    @Insert
    suspend fun insert(row: SavedItemFtsEntity)

    @Query("DELETE FROM saved_item_fts WHERE item_id = :itemId")
    suspend fun deleteForItem(itemId: String)

    @Query("DELETE FROM saved_item_fts")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM saved_item_fts")
    suspend fun count(): Int
}

