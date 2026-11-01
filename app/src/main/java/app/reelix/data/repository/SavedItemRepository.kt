package app.reelix.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import app.reelix.core.Clock
import app.reelix.data.db.ReelixDatabase
import app.reelix.data.db.dao.SavedItemListRow
import app.reelix.data.db.entity.ProcessingEventEntity
import app.reelix.data.db.entity.ProcessingJobEntity
import app.reelix.data.db.entity.ProcessingResultEntity
import app.reelix.data.db.entity.SavedItemEntity
import app.reelix.data.db.entity.SavedItemTagEntity
import app.reelix.data.db.entity.TagEntity
import app.reelix.data.files.ImportedMedia
import app.reelix.data.files.MediaStorage
import app.reelix.data.files.ThumbnailStore
import app.reelix.domain.model.AiResultInfo
import app.reelix.domain.model.ContentType
import app.reelix.domain.model.InstagramLink
import app.reelix.domain.model.ItemDetails
import app.reelix.domain.model.ItemListEntry
import app.reelix.domain.model.ItemQuery
import app.reelix.domain.model.LibraryCounts
import app.reelix.domain.model.LinkSaveOutcome
import app.reelix.domain.model.MediaFileInfo
import app.reelix.domain.model.MediaSaveOutcome
import app.reelix.domain.model.ProcessingEventInfo
import app.reelix.domain.model.ProcessingStage
import app.reelix.domain.model.ProcessingStatus
import app.reelix.domain.model.SaveSource
import app.reelix.domain.model.SortOrder
import app.reelix.domain.model.StageInfo
import app.reelix.domain.model.StageStatus
import app.reelix.domain.model.StatusFilter
import app.reelix.domain.model.TagInfo
import app.reelix.domain.model.TagSource
import app.reelix.domain.model.UserMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Single source of truth for saved items, imported media, metadata, tags, and search. */
class SavedItemRepository(
    private val db: ReelixDatabase,
    private val indexer: SearchIndexer,
    private val thumbnails: ThumbnailStore,
    private val mediaStorage: MediaStorage,
    private val clock: Clock = Clock.SYSTEM,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val itemDao = db.savedItemDao()
    private val tagDao = db.tagDao()
    private val processingDao = db.processingDao()
    private val saveMutex = Mutex()

    // ---------- Saving Links ----------

    suspend fun saveLink(
        link: InstagramLink,
        source: SaveSource = SaveSource.SHARE,
        allowCopy: Boolean = false,
    ): LinkSaveOutcome = saveMutex.withLock {
        try {
            db.withTransaction { insertLinkLocked(link, source, allowCopy) }
        } catch (e: SQLiteConstraintException) {
            val existing = itemDao.findByNormalizedUrl(link.normalizedUrl) ?: throw e
            LinkSaveOutcome.Duplicate(link, existing.id)
        }
    }

    private suspend fun insertLinkLocked(link: InstagramLink, source: SaveSource, allowCopy: Boolean): LinkSaveOutcome {
        val existing = itemDao.findByNormalizedUrl(link.normalizedUrl)
        if (existing != null && !allowCopy) return LinkSaveOutcome.Duplicate(link, existing.id)

        val now = clock.now()
        val copyIndex = if (existing == null) 0 else (itemDao.maxCopyIndex(link.normalizedUrl) ?: 0) + 1
        val id = idGenerator()
        itemDao.insert(
            SavedItemEntity(
                id = id,
                originalUrl = link.originalUrl,
                normalizedUrl = link.normalizedUrl,
                shortcode = link.shortcode,
                contentType = link.contentType,
                copyIndex = copyIndex,
                source = source,
                processingStatus = ProcessingStatus.QUEUED,
                createdAt = now,
                updatedAt = now,
            ),
        )
        processingDao.insertJobs(
            ProcessingStage.entries.map { stage ->
                ProcessingJobEntity(savedItemId = id, stage = stage, createdAt = now, updatedAt = now)
            },
        )
        processingDao.insertEvent(
            ProcessingEventEntity(
                savedItemId = id,
                stage = null,
                status = "SAVED",
                message = if (copyIndex > 0) "Saved as copy #${copyIndex + 1}" else "Saved via ${source.name.lowercase()}",
                createdAt = now,
            ),
        )
        indexer.reindex(id)
        return LinkSaveOutcome.Saved(link, id)
    }

    // ---------- Saving Media ----------

    suspend fun saveMediaItem(
        media: ImportedMedia,
        associatedUrl: String? = null,
        source: SaveSource = SaveSource.MEDIA_SHARE,
    ): MediaSaveOutcome = saveMutex.withLock {
        val now = clock.now()
        val id = idGenerator()
        val isVideo = media.mimeType.startsWith("video/")
        val contentType = if (isVideo) ContentType.REEL else ContentType.AUDIO
        val pseudoUrl = associatedUrl ?: "media://${media.file.name}"

        db.withTransaction {
            itemDao.insert(
                SavedItemEntity(
                    id = id,
                    originalUrl = pseudoUrl,
                    normalizedUrl = pseudoUrl,
                    shortcode = null,
                    contentType = contentType,
                    copyIndex = 0,
                    source = source,
                    mediaAvailable = true,
                    mediaPath = media.file.absolutePath,
                    mediaMimeType = media.mimeType,
                    mediaSizeBytes = media.sizeBytes,
                    mediaDurationMs = media.durationMs,
                    mediaFileName = media.originalFileName,
                    processingStatus = ProcessingStatus.QUEUED,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            processingDao.insertJobs(
                ProcessingStage.entries.map { stage ->
                    val initialStatus = if (stage == ProcessingStage.MEDIA_VERIFICATION) StageStatus.SUCCEEDED else StageStatus.PENDING
                    ProcessingJobEntity(
                        savedItemId = id,
                        stage = stage,
                        status = initialStatus,
                        message = if (stage == ProcessingStage.MEDIA_VERIFICATION) "Media imported (${media.mimeType})" else null,
                        createdAt = now,
                        updatedAt = now,
                    )
                },
            )
            processingDao.insertEvent(
                ProcessingEventEntity(
                    savedItemId = id,
                    stage = ProcessingStage.MEDIA_VERIFICATION,
                    status = "MEDIA_IMPORTED",
                    message = "Imported ${media.originalFileName} (${media.sizeBytes / 1024} KB)",
                    createdAt = now,
                ),
            )
            indexer.reindex(id)
        }
        MediaSaveOutcome.Saved(
            itemId = id,
            fileName = media.originalFileName,
            mimeType = media.mimeType,
            sizeBytes = media.sizeBytes,
        )
    }

    // ---------- Reading ----------

    fun observeCounts(): Flow<LibraryCounts> = itemDao.observeCounts()
        .map { LibraryCounts(it.total, it.awaiting, it.mediaAvailable, it.transcribed, it.aiEnriched, it.attention) }
        .distinctUntilChanged()

    fun observeItems(query: ItemQuery, limit: Int = 500): Flow<List<ItemListEntry>> =
        itemDao.observeFilteredList(
            filterMode = query.statusFilter.toFilterMode(),
            types = query.contentTypes.ifEmpty { ContentType.entries.toSet() },
            ascending = query.sortOrder == SortOrder.OLDEST,
            limit = limit,
        ).map { rows -> rows.map { it.toListEntry() } }

    fun search(text: String, query: ItemQuery, limit: Int = 200): Flow<List<ItemListEntry>> {
        val match = FtsQueryBuilder.build(text) ?: return flowOf(emptyList())
        return itemDao.searchFiltered(
            match = match,
            filterMode = query.statusFilter.toFilterMode(),
            types = query.contentTypes.ifEmpty { ContentType.entries.toSet() },
            ascending = query.sortOrder == SortOrder.OLDEST,
            limit = limit,
        ).map { rows -> rows.map { it.toListEntry() } }
    }

    fun observeTopTags(limit: Int = 20): Flow<List<String>> =
        tagDao.observeTopTags(limit).map { rows -> rows.map { it.name } }

    fun observeDetails(id: String): Flow<ItemDetails?> = combine(
        itemDao.observeById(id),
        tagDao.observeTagsForItem(id),
        processingDao.observeJobsFor(id),
        processingDao.observeResultsFor(id),
        processingDao.observeEvents(id, EVENT_DISPLAY_LIMIT),
    ) { item, tags, jobs, results, events ->
        item ?: return@combine null
        val analysis = results.firstOrNull { it.stage == ProcessingStage.ANALYSIS }
        val transcript = results.firstOrNull { !it.transcript.isNullOrBlank() }?.transcript

        val mediaInfo = item.mediaPath?.let { path ->
            MediaFileInfo(
                path = path,
                mimeType = item.mediaMimeType,
                sizeBytes = item.mediaSizeBytes,
                durationMs = item.mediaDurationMs,
                originalFileName = item.mediaFileName,
                audioPath = item.audioPath,
                audioSizeBytes = item.audioSizeBytes,
            )
        }

        ItemDetails(
            id = item.id,
            originalUrl = item.originalUrl,
            normalizedUrl = item.normalizedUrl,
            contentType = item.contentType,
            copyIndex = item.copyIndex,
            createdAt = item.createdAt,
            updatedAt = item.updatedAt,
            lastProcessedAt = item.lastProcessedAt,
            processingStatus = item.processingStatus,
            extractionStatus = item.extractionStatus,
            audioExtractionStatus = item.audioExtractionStatus,
            transcriptionStatus = item.transcriptionStatus,
            analysisStatus = item.analysisStatus,
            lastError = item.lastError,
            retryCount = item.retryCount,
            mediaInfo = mediaInfo,
            userTitle = item.title,
            userCaption = item.userCaption,
            userTranscript = item.userTranscript,
            userNotes = item.userNotes,
            userCategory = item.category,
            extractedTitle = item.extractedTitle,
            extractedCaption = item.caption,
            authorName = item.authorName,
            thumbnailPath = item.thumbnailPath,
            aiResult = analysis?.toInfo(),
            generatedTranscript = transcript,
            tags = tags.map { TagInfo(it.id, it.name, it.source) },
            stages = jobs.sortedBy { it.stage.ordinal }.map {
                StageInfo(it.stage, it.status, it.attempts, it.updatedAt, it.nextRetryAt, it.lastError ?: it.message)
            },
            events = events.map { ProcessingEventInfo(it.stage, it.status, it.message, it.createdAt) },
        )
    }

    suspend fun getItem(id: String): SavedItemEntity? = itemDao.getById(id)

    // ---------- User edits ----------

    suspend fun updateUserMetadata(id: String, metadata: UserMetadata): Boolean = db.withTransaction {
        val item = itemDao.getById(id) ?: return@withTransaction false
        val now = clock.now()
        itemDao.update(
            item.copy(
                title = metadata.title.clean(MAX_TITLE),
                userCaption = metadata.caption.clean(MAX_TEXT),
                userTranscript = metadata.transcript.clean(MAX_TEXT),
                userNotes = metadata.notes.clean(MAX_TEXT),
                category = metadata.category.clean(MAX_CATEGORY),
                updatedAt = now,
            ),
        )
        replaceTagsLocked(id, TagNormalizer.normalizeAll(metadata.tags), TagSource.USER, now)
        processingDao.insertEvent(
            ProcessingEventEntity(savedItemId = id, stage = null, status = "EDITED", message = "Details edited", createdAt = now),
        )
        indexer.reindex(id)
        true
    }

    suspend fun removeTag(itemId: String, tagId: Long) = db.withTransaction {
        tagDao.deleteRef(itemId, tagId)
        tagDao.deleteOrphanTags()
        indexer.reindex(itemId)
    }

    suspend fun replaceTagsLocked(itemId: String, names: List<String>, source: TagSource, now: Long) {
        tagDao.deleteRefsBySource(itemId, source)
        for (name in names) {
            val tagId = tagDao.insertTag(TagEntity(name = name)).takeIf { it > 0 }
                ?: tagDao.findByName(name)?.id
                ?: continue
            val ref = SavedItemTagEntity(itemId, tagId, source, now)
            if (source == TagSource.USER) tagDao.insertCrossRefReplace(ref) else tagDao.insertCrossRefIgnore(ref)
        }
        tagDao.deleteOrphanTags()
    }

    // ---------- Queue control ----------

    suspend fun retry(id: String): Boolean = db.withTransaction {
        itemDao.getById(id) ?: return@withTransaction false
        val now = clock.now()
        processingDao.resetUnsuccessfulJobs(id, now)
        itemDao.updateProcessingStatus(id, ProcessingStatus.QUEUED, null, null, now)
        itemDao.incrementRetryCount(id, now)
        processingDao.insertEvent(
            ProcessingEventEntity(savedItemId = id, stage = null, status = "RETRY_REQUESTED", message = "Retry requested", createdAt = now),
        )
        true
    }

    suspend fun reprocess(id: String, stages: Collection<ProcessingStage> = REPROCESS_STAGES): Boolean =
        db.withTransaction {
            itemDao.getById(id) ?: return@withTransaction false
            val now = clock.now()
            processingDao.resetJobs(id, stages, now)
            processingDao.resetUnsuccessfulJobs(id, now)
            itemDao.updateProcessingStatus(id, ProcessingStatus.QUEUED, null, null, now)
            processingDao.insertEvent(
                ProcessingEventEntity(savedItemId = id, stage = null, status = "REPROCESS_REQUESTED", message = "Re-processing requested", createdAt = now),
            )
            true
        }

    suspend fun queueItemsAwaitingAnalysis(): Int {
        val ids = itemDao.idsAwaitingAnalysisProvider()
        ids.forEach { reprocess(it, listOf(ProcessingStage.ANALYSIS, ProcessingStage.TAGGING, ProcessingStage.INDEXING, ProcessingStage.COMPLETION)) }
        return ids.size
    }

    // ---------- Deletion ----------

    suspend fun delete(id: String): Boolean {
        val item = itemDao.getById(id)
        val thumbnail = item?.thumbnailPath
        val mediaPath = item?.mediaPath
        val audioPath = item?.audioPath

        val deleted = db.withTransaction {
            db.searchDao().deleteForItem(id)
            val count = itemDao.delete(id)
            tagDao.deleteOrphanTags()
            count > 0
        }
        if (deleted) {
            if (thumbnail != null) thumbnails.delete(thumbnail)
            if (mediaPath != null) mediaStorage.deleteFile(mediaPath)
            if (audioPath != null) mediaStorage.deleteFile(audioPath)
        }
        return deleted
    }

    suspend fun clearAll() {
        db.clearAllTables()
        db.searchDao().deleteAll()
        thumbnails.deleteAll()
        mediaStorage.deleteAll()
    }

    suspend fun countDue(): Int = itemDao.countDue(clock.now())

    suspend fun latestResults(id: String): List<ProcessingResultEntity> = processingDao.resultsFor(id)

    private fun SavedItemListRow.toListEntry(): ItemListEntry = ItemListEntry(
        id = item.id,
        originalUrl = item.originalUrl,
        contentType = item.contentType,
        displayTitle = item.title?.takeIf { it.isNotBlank() }
            ?: item.extractedTitle?.takeIf { it.isNotBlank() }
            ?: latestTopic
            ?: item.mediaFileName,
        captionPreview = (item.userCaption ?: item.caption)?.take(240),
        summary = latestSummary,
        tags = tagNames?.split(',')?.filter { it.isNotBlank() }?.sorted().orEmpty(),
        category = item.category ?: aiCategory,
        processingStatus = item.processingStatus,
        extractionStatus = item.extractionStatus,
        audioExtractionStatus = item.audioExtractionStatus,
        transcriptionStatus = item.transcriptionStatus,
        analysisStatus = item.analysisStatus,
        isMediaAvailable = item.mediaAvailable || !item.mediaPath.isNullOrBlank(),
        thumbnailPath = item.thumbnailPath,
        createdAt = item.createdAt,
    )

    private fun ProcessingResultEntity.toInfo() = AiResultInfo(
        summary = summary,
        detailedSummary = detailedSummary,
        mainTopic = mainTopic,
        keyTakeaways = keyTakeaways?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
        importantConcepts = importantConcepts?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
        actionableSteps = actionableSteps?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
        keywords = keywords?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
        category = category,
        modelName = modelName,
        generatedAt = generatedAt,
    )

    private fun StatusFilter.toFilterMode(): String = when (this) {
        StatusFilter.ALL -> "ALL"
        StatusFilter.MEDIA_AVAILABLE -> "MEDIA"
        StatusFilter.TRANSCRIBED -> "TRANSCRIBED"
        StatusFilter.AI_ENRICHED -> "AI"
        StatusFilter.AWAITING -> "AWAITING"
        StatusFilter.ATTENTION -> "ATTENTION"
    }

    private fun String?.clean(max: Int): String? = this?.trim()?.take(max)?.ifEmpty { null }

    companion object {
        const val EVENT_DISPLAY_LIMIT = 50
        private const val MAX_TITLE = 300
        private const val MAX_CATEGORY = 60
        private const val MAX_TEXT = 100_000
        val REPROCESS_STAGES = listOf(
            ProcessingStage.METADATA,
            ProcessingStage.MEDIA_VERIFICATION,
            ProcessingStage.AUDIO_EXTRACTION,
            ProcessingStage.TRANSCRIPTION,
            ProcessingStage.ANALYSIS,
            ProcessingStage.TAGGING,
            ProcessingStage.INDEXING,
            ProcessingStage.COMPLETION,
        )
    }
}
