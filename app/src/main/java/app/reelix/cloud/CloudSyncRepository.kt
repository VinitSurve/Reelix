package app.reelix.cloud

import app.reelix.core.Clock
import app.reelix.core.ReelixLog
import app.reelix.data.db.ReelixDatabase
import app.reelix.data.db.entity.ProcessingEventEntity
import app.reelix.data.db.entity.ProcessingResultEntity
import app.reelix.data.repository.SavedItemRepository
import app.reelix.data.repository.SearchIndexer
import app.reelix.data.repository.TagNormalizer
import app.reelix.data.settings.SettingsRepository
import app.reelix.domain.model.ProcessingStage
import app.reelix.domain.model.ProcessingStatus
import app.reelix.domain.model.StageStatus
import app.reelix.domain.model.TagSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CloudSyncRepository(
    private val db: ReelixDatabase,
    private val savedItemRepository: SavedItemRepository,
    private val cloudClient: CloudSyncClient,
    private val settingsRepository: SettingsRepository,
    private val indexer: SearchIndexer,
    private val clock: Clock,
) {
    private val itemDao = db.savedItemDao()
    private val processingDao = db.processingDao()

    suspend fun uploadPendingItem(itemId: String): Boolean = withContext(Dispatchers.IO) {
        val item = itemDao.getById(itemId) ?: return@withContext false
        if (!cloudClient.isConfigured()) {
            ReelixLog.w("Cloud client is not configured; skipping upload for $itemId")
            return@withContext false
        }

        val mediaFile = item.mediaPath?.let { File(it) }?.takeIf { it.exists() }
        var mediaInfo: CloudMediaInfo? = null

        if (mediaFile != null) {
            val mimeType = item.mediaMimeType ?: "video/mp4"
            val uploadRes = cloudClient.uploadMedia(
                file = mediaFile,
                mimeType = mimeType,
                originalFileName = item.mediaFileName ?: mediaFile.name,
                itemId = itemId,
            )
            when (uploadRes) {
                is CloudUploadResult.Success -> {
                    mediaInfo = CloudMediaInfo(
                        fileName = item.mediaFileName ?: mediaFile.name,
                        mimeType = mimeType,
                        driveFileId = uploadRes.driveFileId,
                        sizeBytes = uploadRes.sizeBytes,
                        durationMs = item.mediaDurationMs,
                    )
                }
                is CloudUploadResult.Failure -> {
                    ReelixLog.w("Failed to upload media for item $itemId: ${uploadRes.error}")
                    return@withContext false
                }
            }
        }

        val manifest = CloudJobManifest(
            itemId = itemId,
            source = item.source.name,
            originalUrl = item.originalUrl,
            caption = item.caption ?: item.userCaption,
            userNotes = item.userNotes,
            status = "QUEUED",
            createdAt = item.createdAt,
            updatedAt = clock.now(),
            media = mediaInfo,
            stages = CloudStageStates(
                acquisition = if (mediaInfo != null) "SKIPPED" else "PENDING",
                audioExtraction = "PENDING",
                transcription = "PENDING",
                geminiAnalysis = "PENDING",
            ),
        )

        val submitted = cloudClient.submitJobManifest(manifest)
        if (submitted) {
            val now = clock.now()
            itemDao.updateProcessingStatus(
                id = itemId,
                status = ProcessingStatus.PROCESSING,
                nextAttemptAt = null,
                lastError = null,
                now = now,
            )
            processingDao.insertEvent(
                ProcessingEventEntity(
                    savedItemId = itemId,
                    stage = null,
                    status = "CLOUD_OFFLOADED",
                    message = "Job submitted to cloud queue for GitHub Actions processing",
                    createdAt = now,
                ),
            )
            return@withContext true
        }
        return@withContext false
    }

    suspend fun syncCompletedJobs(): Int = withContext(Dispatchers.IO) {
        if (!cloudClient.isConfigured()) return@withContext 0

        val completed = cloudClient.listCompletedJobs()
        var syncedCount = 0

        for (manifest in completed) {
            val item = itemDao.getById(manifest.itemId) ?: continue
            // Only update if not already marked COMPLETED
            if (item.processingStatus == ProcessingStatus.COMPLETED && item.analysisStatus == StageStatus.SUCCEEDED) {
                continue
            }

            val now = clock.now()
            val existingResults = processingDao.resultsFor(manifest.itemId)

            // 0. Cloud-acquired media synchronization (idempotent; preserves existing valid local device media)
            val hasValidLocalMedia = item.mediaPath?.let { File(it).exists() } == true
            if (!hasValidLocalMedia && manifest.media != null && manifest.stages.acquisition == "SUCCEEDED") {
                if (!item.mediaAvailable || item.mediaFileName != manifest.media.fileName) {
                    itemDao.updateMediaDetails(
                        id = manifest.itemId,
                        available = true,
                        mediaPath = item.mediaPath,
                        mediaMimeType = manifest.media.mimeType.ifBlank { item.mediaMimeType ?: "video/mp4" },
                        mediaSizeBytes = manifest.media.sizeBytes.takeIf { it > 0 } ?: item.mediaSizeBytes,
                        mediaDurationMs = manifest.media.durationMs ?: item.mediaDurationMs,
                        mediaFileName = manifest.media.fileName.ifBlank { item.mediaFileName },
                        now = now,
                    )
                }
            }

            // 1. Audio and Transcription (idempotent: avoid duplicate result inserts)
            val hasExistingTranscript = existingResults.any { it.stage == ProcessingStage.TRANSCRIPTION }
            if (!hasExistingTranscript && manifest.results.transcript != null) {
                processingDao.insertResult(
                    ProcessingResultEntity(
                        savedItemId = manifest.itemId,
                        stage = ProcessingStage.TRANSCRIPTION,
                        transcript = manifest.results.transcript,
                        providerId = "github-actions-whisper",
                        modelName = manifest.results.transcriptModel ?: "base",
                        generatedAt = now,
                    ),
                )
                itemDao.setTranscriptionStatus(manifest.itemId, StageStatus.SUCCEEDED, now)
            } else if (manifest.results.transcript != null && item.transcriptionStatus != StageStatus.SUCCEEDED) {
                itemDao.setTranscriptionStatus(manifest.itemId, StageStatus.SUCCEEDED, now)
            }

            if (manifest.stages.audioExtraction == "SUCCEEDED") {
                itemDao.setAudioExtractionStatus(manifest.itemId, StageStatus.SUCCEEDED, now)
            } else if (manifest.stages.audioExtraction == "FAILED") {
                itemDao.setAudioExtractionStatus(manifest.itemId, StageStatus.FAILED, now)
            }

            if (manifest.stages.transcription == "FAILED") {
                itemDao.setTranscriptionStatus(manifest.itemId, StageStatus.FAILED, now)
            }

            // 2. Gemini Analysis (idempotent: avoid duplicate result inserts)
            val analysis = manifest.results.analysis
            val hasExistingAnalysis = existingResults.any { it.stage == ProcessingStage.ANALYSIS }
            if (!hasExistingAnalysis && analysis != null) {
                processingDao.insertResult(
                    ProcessingResultEntity(
                        savedItemId = manifest.itemId,
                        stage = ProcessingStage.ANALYSIS,
                        summary = analysis.summary,
                        detailedSummary = analysis.detailedSummary,
                        mainTopic = analysis.mainTopic,
                        category = analysis.category,
                        keywords = analysis.keywords.joinToString(", ").ifBlank { null },
                        suggestedTags = analysis.tags.joinToString(",").ifBlank { null },
                        keyTakeaways = analysis.keyTakeaways.joinToString("\n").ifBlank { null },
                        importantConcepts = analysis.importantConcepts.joinToString("\n").ifBlank { null },
                        actionableSteps = analysis.actionableSteps.joinToString("\n").ifBlank { null },
                        providerId = "github-actions-gemini",
                        modelName = analysis.modelName,
                        generatedAt = analysis.generatedAt,
                    ),
                )
                itemDao.setAnalysisStatus(manifest.itemId, StageStatus.SUCCEEDED, now)

                // Apply AI tags
                val aiTags = TagNormalizer.normalizeAll(analysis.tags)
                if (aiTags.isNotEmpty()) {
                    savedItemRepository.replaceTagsLocked(manifest.itemId, aiTags, TagSource.AI, now)
                }
            } else if (analysis != null && item.analysisStatus != StageStatus.SUCCEEDED) {
                itemDao.setAnalysisStatus(manifest.itemId, StageStatus.SUCCEEDED, now)
            } else if (manifest.stages.geminiAnalysis == "FAILED") {
                itemDao.setAnalysisStatus(manifest.itemId, StageStatus.FAILED, now)
            }

            // 3. Honest state determination based on cloud outcome
            when (manifest.status) {
                "COMPLETED" -> {
                    val hasAnalysis = analysis != null && (analysis.summary.isNotBlank() || analysis.keyTakeaways.isNotEmpty())
                    val expectedAudio = manifest.media != null && manifest.stages.audioExtraction != "SKIPPED"
                    val hasTranscript = manifest.results.transcript?.isNotBlank() == true

                    if (hasAnalysis && (!expectedAudio || hasTranscript)) {
                        // Truly completed
                        itemDao.markCompleted(manifest.itemId, now)
                        indexer.reindex(manifest.itemId)
                        processingDao.insertEvent(
                            ProcessingEventEntity(
                                savedItemId = manifest.itemId,
                                stage = ProcessingStage.COMPLETION,
                                status = "CLOUD_SYNCED",
                                message = "Synchronized complete transcripts & Gemini analysis from GitHub Actions cloud worker",
                                createdAt = now,
                            ),
                        )
                    } else {
                        // Incomplete or empty results
                        val reason = if (!hasAnalysis) "Missing or empty analysis from cloud worker" else "Missing transcript from cloud worker"
                        itemDao.updateProcessingStatus(
                            id = manifest.itemId,
                            status = ProcessingStatus.NEEDS_ATTENTION,
                            nextAttemptAt = null,
                            lastError = reason,
                            now = now,
                        )
                        indexer.reindex(manifest.itemId)
                        processingDao.insertEvent(
                            ProcessingEventEntity(
                                savedItemId = manifest.itemId,
                                stage = ProcessingStage.COMPLETION,
                                status = "CLOUD_INCOMPLETE",
                                message = "Cloud processing incomplete ($reason); attention required",
                                createdAt = now,
                            ),
                        )
                    }
                }
                "PARTIAL" -> {
                    val errorMsg = manifest.errorMessage ?: "Partial cloud processing; some stages incomplete"
                    itemDao.updateProcessingStatus(
                        id = manifest.itemId,
                        status = ProcessingStatus.NEEDS_ATTENTION,
                        nextAttemptAt = null,
                        lastError = errorMsg,
                        now = now,
                    )
                    indexer.reindex(manifest.itemId)
                    processingDao.insertEvent(
                        ProcessingEventEntity(
                            savedItemId = manifest.itemId,
                            stage = ProcessingStage.COMPLETION,
                            status = "CLOUD_PARTIAL",
                            message = "Synchronized partial cloud results: $errorMsg",
                            createdAt = now,
                        ),
                    )
                }
                "FAILED" -> {
                    val errorMsg = manifest.errorMessage ?: "Cloud processing failed"
                    itemDao.updateProcessingStatus(
                        id = manifest.itemId,
                        status = ProcessingStatus.NEEDS_ATTENTION,
                        nextAttemptAt = null,
                        lastError = errorMsg,
                        now = now,
                    )
                    indexer.reindex(manifest.itemId)
                    processingDao.insertEvent(
                        ProcessingEventEntity(
                            savedItemId = manifest.itemId,
                            stage = ProcessingStage.COMPLETION,
                            status = "CLOUD_FAILED",
                            message = "Cloud processing failed: $errorMsg",
                            createdAt = now,
                        ),
                    )
                }
            }
            syncedCount++
        }

        syncedCount
    }
}
