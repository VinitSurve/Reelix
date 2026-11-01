package app.reelix.processing

import androidx.room.withTransaction
import app.reelix.core.Clock
import app.reelix.data.db.ReelixDatabase
import app.reelix.data.db.entity.ProcessingEventEntity
import app.reelix.data.db.entity.ProcessingJobEntity
import app.reelix.data.db.entity.ProcessingResultEntity
import app.reelix.data.db.entity.SavedItemEntity
import app.reelix.data.repository.SavedItemRepository
import app.reelix.data.repository.SearchIndexer
import app.reelix.domain.model.ProcessingStage
import app.reelix.domain.model.ProcessingStatus
import app.reelix.domain.model.StageStatus
import app.reelix.domain.model.TagSource

/** Persistence operations used by the processing pipeline and its stages. */
class ProcessingRepository(
    private val db: ReelixDatabase,
    private val items: SavedItemRepository,
    private val indexer: SearchIndexer,
    private val clock: Clock,
) {
    private val itemDao = db.savedItemDao()
    private val processingDao = db.processingDao()

    fun now(): Long = clock.now()

    suspend fun resetInterrupted(): Int = itemDao.resetInterrupted(clock.now())
    suspend fun dueItemIds(limit: Int): List<String> = itemDao.dueItemIds(clock.now(), limit)
    suspend fun countDue(): Int = itemDao.countDue(clock.now())
    suspend fun earliestFutureRetry(): Long? = itemDao.earliestFutureRetry(clock.now())
    suspend fun claim(id: String): Boolean = itemDao.claim(id, clock.now()) == 1
    suspend fun exists(id: String): Boolean = itemDao.exists(id) > 0
    suspend fun item(id: String): SavedItemEntity? = itemDao.getById(id)

    /** Returns one job per stage, creating rows for stages added after the item was saved. */
    suspend fun ensureJobs(id: String): Map<ProcessingStage, ProcessingJobEntity> {
        val existing = processingDao.jobsFor(id).associateBy { it.stage }
        val missing = ProcessingStage.entries.filter { it !in existing }
        if (missing.isEmpty()) return existing
        val now = clock.now()
        processingDao.insertJobs(missing.map { ProcessingJobEntity(savedItemId = id, stage = it, createdAt = now, updatedAt = now) })
        return processingDao.jobsFor(id).associateBy { it.stage }
    }

    suspend fun markStageRunning(id: String, job: ProcessingJobEntity) {
        val now = clock.now()
        processingDao.updateJob(id, job.stage, StageStatus.RUNNING, job.attempts, null, null, null, now)
        setItemStageSummary(id, job.stage, StageStatus.RUNNING, now)
    }

    suspend fun settleStage(id: String, job: ProcessingJobEntity, status: StageStatus, message: String?) = db.withTransaction {
        val now = clock.now()
        processingDao.updateJob(id, job.stage, status, job.attempts, null, null, message?.take(300), now)
        setItemStageSummary(id, job.stage, status, now)
        event(id, job.stage, status.name, message, now)
    }

    suspend fun scheduleRetry(id: String, job: ProcessingJobEntity, attempts: Int, nextRetryAt: Long, error: String) =
        db.withTransaction {
            val now = clock.now()
            val safeError = error.take(300)
            processingDao.updateJob(id, job.stage, StageStatus.RETRY_SCHEDULED, attempts, nextRetryAt, safeError, null, now)
            setItemStageSummary(id, job.stage, StageStatus.RETRY_SCHEDULED, now)
            itemDao.updateProcessingStatus(id, ProcessingStatus.WAITING_RETRY, nextRetryAt, "${job.stage.label}: $safeError", now)
            event(id, job.stage, StageStatus.RETRY_SCHEDULED.name, "Attempt $attempts failed: $safeError", now)
        }

    suspend fun failStage(id: String, job: ProcessingJobEntity, attempts: Int, error: String) = db.withTransaction {
        val now = clock.now()
        val safeError = error.take(300)
        processingDao.updateJob(id, job.stage, StageStatus.FAILED, attempts, null, safeError, null, now)
        setItemStageSummary(id, job.stage, StageStatus.FAILED, now)
        itemDao.updateProcessingStatus(id, ProcessingStatus.NEEDS_ATTENTION, null, "${job.stage.label}: $safeError", now)
        event(id, job.stage, StageStatus.FAILED.name, safeError, now)
    }

    suspend fun markCompleted(id: String) = db.withTransaction {
        val now = clock.now()
        itemDao.markCompleted(id, now)
        event(id, ProcessingStage.COMPLETION, "COMPLETED", null, now)
        processingDao.trimEvents(id, MAX_EVENTS_PER_ITEM)
    }

    // ---- Stage outputs ----

    suspend fun setMediaAvailable(id: String, available: Boolean) = itemDao.setMediaAvailable(id, available, clock.now())

    suspend fun updateAudioExtraction(id: String, audioPath: String?, audioSizeBytes: Long?, status: StageStatus) = db.withTransaction {
        itemDao.updateAudioExtraction(id, audioPath, audioSizeBytes, status, clock.now())
        indexer.reindex(id)
    }

    /** Fills extracted fields that are still empty. Existing data is never overwritten. */
    suspend fun applyExtractedMetadata(
        id: String,
        title: String?,
        caption: String?,
        authorName: String?,
        thumbnailUrl: String?,
        thumbnailPath: String?,
    ) = db.withTransaction {
        val item = itemDao.getById(id) ?: return@withTransaction
        itemDao.update(
            item.copy(
                extractedTitle = item.extractedTitle ?: title,
                caption = item.caption ?: caption,
                authorName = item.authorName ?: authorName,
                thumbnailUrl = item.thumbnailUrl ?: thumbnailUrl,
                thumbnailPath = item.thumbnailPath ?: thumbnailPath,
                updatedAt = clock.now(),
            ),
        )
        indexer.reindex(id)
    }

    /** Results are appended, never overwritten, so earlier outputs remain as history. */
    suspend fun saveResult(result: ProcessingResultEntity) = db.withTransaction {
        processingDao.insertResult(result)
        indexer.reindex(result.savedItemId)
    }

    suspend fun results(id: String): List<ProcessingResultEntity> = processingDao.resultsFor(id)

    suspend fun replaceGeneratedTags(id: String, source: TagSource, names: List<String>) = db.withTransaction {
        items.replaceTagsLocked(id, names, source, clock.now())
        indexer.reindex(id)
    }

    suspend fun reindex(id: String) = db.withTransaction { indexer.reindex(id) }

    private suspend fun setItemStageSummary(id: String, stage: ProcessingStage, status: StageStatus, now: Long) {
        when (stage) {
            ProcessingStage.METADATA -> itemDao.setExtractionStatus(id, status, now)
            ProcessingStage.AUDIO_EXTRACTION -> itemDao.setAudioExtractionStatus(id, status, now)
            ProcessingStage.TRANSCRIPTION -> itemDao.setTranscriptionStatus(id, status, now)
            ProcessingStage.ANALYSIS -> itemDao.setAnalysisStatus(id, status, now)
            else -> Unit
        }
    }

    private suspend fun event(id: String, stage: ProcessingStage?, status: String, message: String?, now: Long) {
        processingDao.insertEvent(
            ProcessingEventEntity(savedItemId = id, stage = stage, status = status, message = message?.take(300), createdAt = now),
        )
    }

    companion object {
        const val MAX_EVENTS_PER_ITEM = 100
    }
}

