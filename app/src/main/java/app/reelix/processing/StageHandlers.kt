package app.reelix.processing

import app.reelix.core.url.InstagramUrlParser
import app.reelix.data.db.entity.ProcessingResultEntity
import app.reelix.data.files.ThumbnailStore
import app.reelix.data.media.AudioExtractionResult
import app.reelix.data.media.AudioExtractor
import app.reelix.data.repository.TagNormalizer
import app.reelix.data.settings.SettingsRepository
import app.reelix.domain.model.InstagramLink
import app.reelix.domain.model.ProcessingStage
import app.reelix.domain.model.SaveSource
import app.reelix.domain.model.StageStatus
import app.reelix.domain.model.TagSource
import app.reelix.integrations.acquisition.AcquisitionOutcome
import app.reelix.integrations.acquisition.ContentAcquisitionProvider
import app.reelix.integrations.analysis.AnalysisInput
import app.reelix.integrations.analysis.AnalysisOutcome
import app.reelix.integrations.analysis.AnalysisProviderResolver
import app.reelix.integrations.metadata.MetadataExtractor
import app.reelix.integrations.metadata.MetadataOutcome
import app.reelix.integrations.transcription.TranscriptionOutcome
import app.reelix.integrations.transcription.TranscriptionProvider
import java.io.File

class ValidationStageHandler(
    private val repository: ProcessingRepository,
) : StageHandler {
    override val stage = ProcessingStage.VALIDATION

    override suspend fun run(itemId: String): StageOutcome {
        val item = repository.item(itemId) ?: return StageOutcome.Skipped("Item no longer exists")
        if (item.mediaAvailable || item.source == SaveSource.MEDIA_SHARE) {
            val file = item.mediaPath?.let { File(it) }
            return if (file != null && file.exists()) {
                StageOutcome.Succeeded("Validated shared media file (${item.mediaMimeType ?: "unknown"})")
            } else {
                StageOutcome.PermanentFailure("Shared media file is missing")
            }
        }
        val link = parseItemLink(item.originalUrl, item.normalizedUrl)
            ?: return StageOutcome.PermanentFailure("Saved URL is not a valid Instagram link")
        return StageOutcome.Succeeded("Validated ${link.contentType.label} link")
    }
}

class MediaVerificationStageHandler(
    private val repository: ProcessingRepository,
) : StageHandler {
    override val stage = ProcessingStage.MEDIA_VERIFICATION

    override suspend fun run(itemId: String): StageOutcome {
        val item = repository.item(itemId) ?: return StageOutcome.Skipped("Item no longer exists")
        if (item.source != SaveSource.MEDIA_SHARE && !item.mediaAvailable) {
            return StageOutcome.Skipped("URL-only item (no local media file)")
        }
        val path = item.mediaPath ?: return StageOutcome.PermanentFailure("Media file path missing")
        val file = File(path)
        if (!file.exists() || file.length() == 0L) {
            return StageOutcome.PermanentFailure("Imported media file missing or corrupted")
        }
        return StageOutcome.Succeeded("Verified media file (${file.length() / 1024} KB)")
    }
}

class AcquisitionStageHandler(
    private val repository: ProcessingRepository,
    private val provider: ContentAcquisitionProvider,
) : StageHandler {
    override val stage = ProcessingStage.ACQUISITION

    override suspend fun run(itemId: String): StageOutcome {
        val item = repository.item(itemId) ?: return StageOutcome.Skipped("Item no longer exists")
        if (item.mediaAvailable || item.source == SaveSource.MEDIA_SHARE) {
            return StageOutcome.Succeeded("Media already in private storage")
        }
        val link = parseItemLink(item.originalUrl, item.normalizedUrl)
            ?: return StageOutcome.PermanentFailure("Invalid Instagram URL")

        return when (val outcome = provider.acquire(link)) {
            is AcquisitionOutcome.MediaAvailable -> {
                repository.setMediaAvailable(itemId, true)
                StageOutcome.Succeeded("Media acquired (${outcome.mimeType})")
            }
            is AcquisitionOutcome.UrlOnly -> {
                repository.setMediaAvailable(itemId, false)
                StageOutcome.Unavailable(outcome.reason)
            }
            is AcquisitionOutcome.TransientFailure -> StageOutcome.TransientFailure(outcome.error)
            is AcquisitionOutcome.PermanentFailure -> StageOutcome.PermanentFailure(outcome.error)
        }
    }
}

class MetadataStageHandler(
    private val repository: ProcessingRepository,
    private val extractor: MetadataExtractor,
    private val thumbnails: ThumbnailStore,
    private val settings: SettingsRepository,
) : StageHandler {
    override val stage = ProcessingStage.METADATA

    override suspend fun run(itemId: String): StageOutcome {
        val item = repository.item(itemId) ?: return StageOutcome.Skipped("Item no longer exists")
        if (item.source == SaveSource.MEDIA_SHARE) {
            return StageOutcome.Skipped("Shared media has no public web preview")
        }
        val prefs = settings.current()
        if (!prefs.fetchLinkPreviews) {
            return StageOutcome.Skipped("Public link-preview fetching is disabled in Settings")
        }
        val link = parseItemLink(item.originalUrl, item.normalizedUrl)
            ?: return StageOutcome.PermanentFailure("Invalid Instagram URL")

        return when (val outcome = extractor.extract(link)) {
            is MetadataOutcome.Found -> {
                val meta = outcome.metadata
                val localThumb = if (prefs.downloadThumbnails && item.thumbnailPath == null && meta.imageUrl != null) {
                    thumbnails.download(meta.imageUrl, itemId)
                } else {
                    null
                }
                repository.applyExtractedMetadata(
                    id = itemId,
                    title = meta.title,
                    caption = meta.caption,
                    authorName = meta.authorName,
                    thumbnailUrl = meta.imageUrl,
                    thumbnailPath = localThumb,
                )
                StageOutcome.Succeeded("Extracted public link preview")
            }
            is MetadataOutcome.NotAvailable -> StageOutcome.Unavailable(outcome.reason)
            is MetadataOutcome.TransientFailure -> StageOutcome.TransientFailure(outcome.error)
        }
    }
}

class AudioExtractionStageHandler(
    private val repository: ProcessingRepository,
    private val audioExtractor: AudioExtractor,
) : StageHandler {
    override val stage = ProcessingStage.AUDIO_EXTRACTION

    override suspend fun run(itemId: String): StageOutcome {
        val item = repository.item(itemId) ?: return StageOutcome.Skipped("Item no longer exists")
        if (!item.mediaAvailable || item.mediaPath == null) {
            return StageOutcome.Unavailable("No local media file available in URL-only mode")
        }
        if (item.audioPath != null && File(item.audioPath).exists()) {
            return StageOutcome.Skipped("Audio already extracted")
        }
        val sourceFile = File(item.mediaPath)
        if (!sourceFile.exists()) {
            return StageOutcome.PermanentFailure("Source media file was deleted or cannot be found")
        }
        val audioFile = File(sourceFile.parentFile, "${sourceFile.nameWithoutExtension}_audio.wav")
        return when (val result = audioExtractor.extractAudio(sourceFile, audioFile)) {
            is AudioExtractionResult.Success -> {
                repository.updateAudioExtraction(itemId, result.audioFile.absolutePath, result.audioFile.length(), StageStatus.SUCCEEDED)
                StageOutcome.Succeeded("Audio extracted (16kHz mono WAV, ${result.durationMs / 1000}s)")
            }
            is AudioExtractionResult.NoAudioTrack -> {
                repository.updateAudioExtraction(itemId, null, null, StageStatus.SKIPPED)
                StageOutcome.Skipped(result.message)
            }
            is AudioExtractionResult.CorruptedMedia -> {
                repository.updateAudioExtraction(itemId, null, null, StageStatus.FAILED)
                StageOutcome.PermanentFailure(result.message)
            }
            is AudioExtractionResult.InsufficientStorage -> {
                StageOutcome.TransientFailure(result.message)
            }
            is AudioExtractionResult.Failure -> {
                repository.updateAudioExtraction(itemId, null, null, StageStatus.FAILED)
                StageOutcome.PermanentFailure(result.error)
            }
        }
    }
}

class TranscriptionStageHandler(
    private val repository: ProcessingRepository,
    private val provider: TranscriptionProvider,
) : StageHandler {
    override val stage = ProcessingStage.TRANSCRIPTION

    override suspend fun run(itemId: String): StageOutcome {
        val item = repository.item(itemId) ?: return StageOutcome.Skipped("Item no longer exists")
        if (!item.userTranscript.isNullOrBlank() && !item.mediaAvailable) {
            return StageOutcome.Skipped("Using user-provided transcript")
        }
        val audioFile = item.audioPath?.let { File(it) }?.takeIf { it.exists() }
            ?: item.mediaPath?.let { File(it) }?.takeIf { it.exists() && item.mediaMimeType?.startsWith("audio/") == true }

        if (audioFile == null) {
            return if (!item.mediaAvailable) {
                StageOutcome.Unavailable("Audio unavailable when Instagram shares only a URL — paste a transcript manually if needed")
            } else {
                StageOutcome.Unavailable("No audio track available for transcription")
            }
        }

        if (!provider.isAvailable) {
            return StageOutcome.NotConfigured("Offline transcription model not loaded — import a Whisper model in Settings")
        }

        return when (val outcome = provider.transcribe(audioFile)) {
            is TranscriptionOutcome.Success -> {
                repository.saveResult(
                    ProcessingResultEntity(
                        savedItemId = itemId,
                        stage = ProcessingStage.TRANSCRIPTION,
                        transcript = outcome.transcript,
                        providerId = provider.id,
                        modelName = outcome.modelName,
                        generatedAt = repository.now(),
                    ),
                )
                StageOutcome.Succeeded("Audio transcribed (${outcome.modelName ?: provider.displayName})")
            }
            is TranscriptionOutcome.ModelNotConfigured -> StageOutcome.NotConfigured(outcome.message)
            is TranscriptionOutcome.TransientFailure -> StageOutcome.TransientFailure(outcome.error)
            is TranscriptionOutcome.PermanentFailure -> StageOutcome.PermanentFailure(outcome.error)
        }
    }
}

class VisualAnalysisStageHandler(
    private val repository: ProcessingRepository,
) : StageHandler {
    override val stage = ProcessingStage.VISUAL_ANALYSIS

    override suspend fun run(itemId: String): StageOutcome {
        val item = repository.item(itemId) ?: return StageOutcome.Skipped("Item no longer exists")
        return if (!item.mediaAvailable) {
            StageOutcome.Skipped("Optional visual frame analysis skipped (no video file)")
        } else {
            StageOutcome.NotConfigured("Optional visual analyser is not configured")
        }
    }
}

class AnalysisStageHandler(
    private val repository: ProcessingRepository,
    private val providerResolver: AnalysisProviderResolver,
) : StageHandler {
    override val stage = ProcessingStage.ANALYSIS

    override suspend fun run(itemId: String): StageOutcome {
        val item = repository.item(itemId) ?: return StageOutcome.Skipped("Item no longer exists")
        val provider = providerResolver.current()
            ?: return StageOutcome.NotConfigured("Optional AI enrichment is not configured — item remains searchable offline")

        val transcript = item.userTranscript
            ?: repository.results(itemId).firstOrNull { !it.transcript.isNullOrBlank() }?.transcript

        val input = AnalysisInput(
            contentType = item.contentType,
            title = item.title ?: item.extractedTitle,
            caption = item.userCaption ?: item.caption,
            transcript = transcript,
            notes = item.userNotes,
        )
        if (!input.hasEnoughText) {
            return StageOutcome.Skipped("Not enough text available yet — add a caption, transcript or notes to summarise")
        }

        return when (val outcome = provider.analyze(input)) {
            is AnalysisOutcome.Success -> {
                val r = outcome.result
                repository.saveResult(
                    ProcessingResultEntity(
                        savedItemId = itemId,
                        stage = ProcessingStage.ANALYSIS,
                        summary = r.summary,
                        detailedSummary = r.detailedSummary,
                        mainTopic = r.mainTopic,
                        category = r.category,
                        keywords = r.keywords.joinToString(", ").ifBlank { null },
                        suggestedTags = r.tags.joinToString(",").ifBlank { null },
                        keyTakeaways = r.keyTakeaways.joinToString("\n").ifBlank { null },
                        importantConcepts = r.importantConcepts.joinToString("\n").ifBlank { null },
                        actionableSteps = r.actionableSteps.joinToString("\n").ifBlank { null },
                        providerId = provider.id,
                        modelName = r.modelName,
                        generatedAt = repository.now(),
                    ),
                )
                StageOutcome.Succeeded("Generated AI summary (${r.modelName ?: provider.displayName})")
            }
            is AnalysisOutcome.NoResult -> StageOutcome.Skipped(outcome.reason)
            is AnalysisOutcome.TransientFailure -> StageOutcome.TransientFailure(outcome.error)
            is AnalysisOutcome.PermanentFailure -> StageOutcome.PermanentFailure(outcome.error)
        }
    }
}

class TaggingStageHandler(
    private val repository: ProcessingRepository,
) : StageHandler {
    override val stage = ProcessingStage.TAGGING

    override suspend fun run(itemId: String): StageOutcome {
        val item = repository.item(itemId) ?: return StageOutcome.Skipped("Item no longer exists")
        val captionText = listOfNotNull(item.caption, item.userCaption).joinToString("\n")
        val hashtags = TagNormalizer.hashtags(captionText)
        repository.replaceGeneratedTags(itemId, TagSource.EXTRACTED, hashtags)

        val latestAnalysis = repository.results(itemId).firstOrNull { it.stage == ProcessingStage.ANALYSIS }
        val aiTags = latestAnalysis?.suggestedTags
            ?.split(',')
            ?.let { TagNormalizer.normalizeAll(it) }
            .orEmpty()
        repository.replaceGeneratedTags(itemId, TagSource.AI, aiTags)

        val count = hashtags.size + aiTags.size
        return if (count > 0) {
            StageOutcome.Succeeded("Applied $count tag(s)")
        } else {
            StageOutcome.Skipped("No hashtags or AI tags found")
        }
    }
}

class IndexingStageHandler(
    private val repository: ProcessingRepository,
) : StageHandler {
    override val stage = ProcessingStage.INDEXING

    override suspend fun run(itemId: String): StageOutcome {
        repository.reindex(itemId)
        return StageOutcome.Succeeded("Indexed for offline full-text search")
    }
}

class CompletionStageHandler(
    private val repository: ProcessingRepository,
) : StageHandler {
    override val stage = ProcessingStage.COMPLETION

    override suspend fun run(itemId: String): StageOutcome {
        if (!repository.exists(itemId)) return StageOutcome.Skipped("Item no longer exists")
        return StageOutcome.Succeeded("Item processing finished")
    }
}

object DefaultStageHandlers {
    fun build(
        repository: ProcessingRepository,
        acquisitionProvider: ContentAcquisitionProvider,
        metadataExtractor: MetadataExtractor,
        audioExtractor: AudioExtractor,
        transcriptionProvider: TranscriptionProvider,
        analysisResolver: AnalysisProviderResolver,
        thumbnails: ThumbnailStore,
        settings: SettingsRepository,
    ): List<StageHandler> = listOf(
        ValidationStageHandler(repository),
        MediaVerificationStageHandler(repository),
        AcquisitionStageHandler(repository, acquisitionProvider),
        MetadataStageHandler(repository, metadataExtractor, thumbnails, settings),
        AudioExtractionStageHandler(repository, audioExtractor),
        TranscriptionStageHandler(repository, transcriptionProvider),
        VisualAnalysisStageHandler(repository),
        AnalysisStageHandler(repository, analysisResolver),
        TaggingStageHandler(repository),
        IndexingStageHandler(repository),
        CompletionStageHandler(repository),
    )
}

private fun parseItemLink(originalUrl: String, normalizedUrl: String): InstagramLink? =
    InstagramUrlParser.parse(originalUrl) ?: InstagramUrlParser.parse(normalizedUrl)
