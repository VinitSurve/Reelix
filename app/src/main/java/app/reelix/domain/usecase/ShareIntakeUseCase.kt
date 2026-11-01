package app.reelix.domain.usecase

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import app.reelix.core.ReelixLog
import app.reelix.core.safeDescription
import app.reelix.core.url.InstagramUrlParser
import app.reelix.core.url.ShareIntentReader
import app.reelix.data.files.MediaImportResult
import app.reelix.data.files.MediaStorage
import app.reelix.data.repository.SavedItemRepository
import app.reelix.data.settings.SettingsRepository
import app.reelix.domain.model.InstagramLink
import app.reelix.domain.model.LinkSaveOutcome
import app.reelix.domain.model.MediaSaveOutcome
import app.reelix.domain.model.SaveSource
import app.reelix.domain.model.ShareSaveResult
import app.reelix.processing.ProcessingScheduler
import kotlinx.coroutines.CancellationException

/**
 * Coordinates the critical share-sheet & manual link/media-intake path.
 *
 * Guarantees:
 * - Never blocks on network, extraction, transcription or AI.
 * - Streams shared media into app-private storage before scheduling background work.
 * - Persists every valid Instagram URL or media reference to Room *before* scheduling work.
 * - Deduplicates both within the same share payload and against existing database items.
 */
class ShareIntakeUseCase(
    private val repository: SavedItemRepository,
    private val scheduler: ProcessingScheduler,
    private val mediaStorage: MediaStorage? = null,
    private val contentResolver: ContentResolver? = null,
    private val settingsRepository: SettingsRepository? = null,
) {
    suspend fun handleIntent(intent: Intent?): ShareSaveResult {
        if (intent == null) return ShareSaveResult.NothingShared

        val mediaUris = ShareIntentReader.readMediaUris(intent)
        val texts = ShareIntentReader.readTexts(intent)

        if (mediaUris.isNotEmpty()) {
            val associatedUrl = texts
                .flatMap { InstagramUrlParser.extractAll(it) }
                .firstOrNull()
                ?.originalUrl
            return handleMediaUris(mediaUris, source = SaveSource.MEDIA_SHARE, associatedUrl = associatedUrl)
        }

        if (texts.isEmpty()) return ShareSaveResult.NothingShared
        return handleSharedTexts(texts, source = SaveSource.SHARE)
    }

    suspend fun handleMediaUris(
        uris: List<Uri>,
        source: SaveSource = SaveSource.MEDIA_SHARE,
        associatedUrl: String? = null,
    ): ShareSaveResult {
        if (uris.isEmpty()) return ShareSaveResult.NothingShared
        val storage = mediaStorage ?: return ShareSaveResult.Error("Media storage is not initialized")
        val resolver = contentResolver ?: return ShareSaveResult.Error("Content resolver is not available")

        val maxBytes = settingsRepository?.current()?.maxMediaImportSizeBytes ?: (200L * 1024 * 1024)

        return try {
            val outcomes = uris.map { uri ->
                when (val result = storage.importFromUri(uri, resolver, maxBytes)) {
                    is MediaImportResult.Success -> {
                        repository.saveMediaItem(result.media, associatedUrl = associatedUrl, source = source)
                    }
                    is MediaImportResult.FileTooLarge -> {
                        MediaSaveOutcome.Failed(
                            fileName = uri.lastPathSegment ?: "media",
                            reason = "File exceeds ${result.maxBytes / 1024 / 1024}MB limit (${result.sizeBytes / 1024 / 1024}MB)",
                        )
                    }
                    is MediaImportResult.InsufficientStorage -> {
                        MediaSaveOutcome.Failed(
                            fileName = uri.lastPathSegment ?: "media",
                            reason = "Insufficient device storage (${result.availableBytes / 1024 / 1024}MB free)",
                        )
                    }
                    is MediaImportResult.UnsupportedFormat -> {
                        MediaSaveOutcome.Failed(
                            fileName = uri.lastPathSegment ?: "media",
                            reason = "Unsupported media format: ${result.reason}",
                        )
                    }
                    is MediaImportResult.Failure -> {
                        MediaSaveOutcome.Failed(
                            fileName = uri.lastPathSegment ?: "media",
                            reason = result.error,
                        )
                    }
                }
            }

            if (outcomes.any { it is MediaSaveOutcome.Saved }) {
                runCatching { scheduler.scheduleImmediate() }
                    .onFailure { ReelixLog.w("Background scheduling deferred after media import", it) }
            }

            ShareSaveResult.MediaHandled(outcomes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReelixLog.w("Failed to import shared media", e)
            ShareSaveResult.Error("Could not import media (${e.safeDescription()})")
        }
    }

    suspend fun handleManualText(rawText: String, allowCopy: Boolean = false): ShareSaveResult {
        if (rawText.isBlank()) return ShareSaveResult.NothingShared
        return handleSharedTexts(listOf(rawText), source = SaveSource.MANUAL, allowCopy = allowCopy)
    }

    suspend fun saveAnotherCopy(link: InstagramLink, source: SaveSource = SaveSource.SHARE): LinkSaveOutcome {
        val outcome = repository.saveLink(link, source = source, allowCopy = true)
        if (outcome is LinkSaveOutcome.Saved) {
            runCatching { scheduler.scheduleImmediate() }
                .onFailure { ReelixLog.w("Scheduling failed after saving copy", it) }
        }
        return outcome
    }

    suspend fun handleSharedTexts(
        texts: List<String>,
        source: SaveSource = SaveSource.SHARE,
        allowCopy: Boolean = false,
    ): ShareSaveResult {
        if (texts.all { it.isBlank() }) return ShareSaveResult.NothingShared

        val links = texts
            .flatMap { InstagramUrlParser.extractAll(it) }
            .distinctBy { it.normalizedUrl }

        if (links.isEmpty()) return ShareSaveResult.NoInstagramLink

        return try {
            val outcomes = links.map { link ->
                repository.saveLink(link, source = source, allowCopy = allowCopy)
            }
            if (outcomes.any { it is LinkSaveOutcome.Saved }) {
                runCatching { scheduler.scheduleImmediate() }
                    .onFailure { ReelixLog.w("Background scheduling deferred", it) }
            }
            ShareSaveResult.Handled(outcomes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReelixLog.w("Failed to persist shared link", e)
            ShareSaveResult.Error("Could not save link (${e.safeDescription()})")
        }
    }
}
