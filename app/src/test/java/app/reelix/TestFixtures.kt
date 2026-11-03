package app.reelix

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.reelix.core.Clock
import app.reelix.data.db.ReelixDatabase
import app.reelix.data.files.ImportedMedia
import app.reelix.data.files.MediaImportResult
import app.reelix.data.files.MediaStorage
import app.reelix.data.files.StorageInspector
import app.reelix.data.files.StorageSummary
import app.reelix.data.files.ThumbnailStore
import app.reelix.data.media.AudioExtractionResult
import app.reelix.data.media.AudioExtractor
import app.reelix.data.repository.SavedItemRepository
import app.reelix.data.repository.SearchIndexer
import app.reelix.data.settings.AppSettings
import app.reelix.data.settings.InMemorySecretStore
import app.reelix.data.settings.SettingsRepository
import app.reelix.di.AppContainer
import app.reelix.domain.usecase.ShareIntakeUseCase
import app.reelix.integrations.acquisition.ContentAcquisitionProvider
import app.reelix.integrations.acquisition.UrlOnlyAcquisitionProvider
import app.reelix.integrations.analysis.AnalysisProviderResolver
import app.reelix.integrations.analysis.ConnectionTestResult
import app.reelix.integrations.metadata.MetadataExtractor
import app.reelix.integrations.metadata.MetadataOutcome
import app.reelix.integrations.transcription.ModelImportOutcome
import app.reelix.integrations.transcription.ModelStatus
import app.reelix.integrations.transcription.TranscriptionModelManager
import app.reelix.integrations.transcription.TranscriptionProvider
import app.reelix.integrations.transcription.UnavailableTranscriptionProvider
import app.reelix.processing.BackoffPolicy
import app.reelix.processing.DefaultStageHandlers
import app.reelix.processing.ProcessingPipeline
import app.reelix.processing.ProcessingRepository
import app.reelix.processing.RecordingProcessingScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

class MutableClock(var currentMillis: Long = 1_730_000_000_000L) : Clock {
    override fun now(): Long = currentMillis
    fun advanceBy(deltaMs: Long) {
        currentMillis += deltaMs
    }
}

class FakeSettingsRepository(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    private val secrets = InMemorySecretStore()
    var nextConnectionTestResult: ConnectionTestResult =
        ConnectionTestResult.Success("Connected to Ollama", listOf("llama3.2"))

    override val settings: Flow<AppSettings> = state.asStateFlow()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.update { current ->
            val updated = transform(current)
            updated.copy(
                analysis = updated.analysis.copy(hasApiKey = secrets.get("api_key") != null),
            )
        }
    }

    override suspend fun setApiKey(apiKey: String?) {
        if (apiKey.isNullOrBlank()) secrets.remove("api_key") else secrets.put("api_key", apiKey.trim())
        state.update { it.copy(analysis = it.analysis.copy(hasApiKey = secrets.get("api_key") != null)) }
    }

    override fun apiKey(): String? = secrets.get("api_key")

    override suspend fun recordRun(at: Long, summary: String) {
        state.update { it.copy(lastRunAt = at, lastRunSummary = summary) }
    }

    override suspend fun testAiConnection(baseUrl: String, model: String, apiKey: String?): ConnectionTestResult =
        nextConnectionTestResult

    override fun cloudDriveAccessToken(): String? = secrets.get("cloud_drive_token")
    override fun cloudDriveRefreshToken(): String? = secrets.get("cloud_drive_refresh_token")

    override suspend fun setCloudDriveCredentials(
        accessToken: String?,
        refreshToken: String?,
        clientId: String?,
        clientSecret: String?,
    ) {
        if (accessToken.isNullOrBlank()) secrets.remove("cloud_drive_token") else secrets.put("cloud_drive_token", accessToken.trim())
        if (refreshToken.isNullOrBlank()) secrets.remove("cloud_drive_refresh_token") else secrets.put("cloud_drive_refresh_token", refreshToken.trim())
        state.update {
            it.copy(
                hasCloudDriveToken = secrets.get("cloud_drive_token") != null,
                hasCloudDriveRefreshToken = secrets.get("cloud_drive_refresh_token") != null,
            )
        }
    }

    override suspend fun saveCloudDriveAccessToken(accessToken: String) {
        secrets.put("cloud_drive_token", accessToken.trim())
        state.update {
            it.copy(hasCloudDriveToken = true)
        }
    }

    override suspend fun clearCloudDriveCredentials() {
        secrets.remove("cloud_drive_token")
        secrets.remove("cloud_drive_refresh_token")
        state.update {
            it.copy(
                hasCloudDriveToken = false,
                hasCloudDriveRefreshToken = false,
                cloudProcessingEnabled = false,
            )
        }
    }

    override suspend fun refreshCloudDriveAccessToken(): String? = cloudDriveAccessToken()

    override suspend fun getValidDriveAccessToken(): String? = cloudDriveAccessToken()

    override suspend fun clear() {
        secrets.remove("api_key")
        secrets.remove("cloud_drive_token")
        secrets.remove("cloud_drive_refresh_token")
        state.value = AppSettings()
    }
}

class FakeThumbnailStore(private val rootDir: File? = null) : ThumbnailStore {
    val deletedPaths = mutableListOf<String>()
    var clearedAll = false
    var nextDownloadResult: ((url: String, itemId: String) -> String?) = { _, itemId ->
        if (rootDir != null) {
            rootDir.mkdirs()
            val f = File(rootDir, "$itemId.img")
            f.writeBytes(byteArrayOf(1, 2, 3, 4))
            f.absolutePath
        } else {
            "/tmp/$itemId.img"
        }
    }

    override suspend fun download(url: String, itemId: String): String? = nextDownloadResult(url, itemId)

    override fun delete(path: String) {
        deletedPaths += path
        File(path).takeIf { it.exists() }?.delete()
    }

    override fun deleteAll() {
        clearedAll = true
        rootDir?.listFiles()?.forEach { it.delete() }
    }

    override fun totalBytes(): Long = rootDir?.listFiles()?.sumOf { it.length() } ?: 0L
}

class FakeMediaStorage(private val rootDir: File? = null) : MediaStorage {
    val importedUris = mutableListOf<Uri>()
    val deletedPaths = mutableListOf<String>()
    var nextImportResult: ((Uri) -> MediaImportResult)? = null

    override suspend fun importFromUri(
        uri: Uri,
        contentResolver: ContentResolver,
        maxBytes: Long,
    ): MediaImportResult {
        importedUris += uri
        return nextImportResult?.invoke(uri) ?: MediaImportResult.Success(
            ImportedMedia(
                file = File(rootDir ?: File("/tmp"), "fake_${System.currentTimeMillis()}.mp4").apply {
                    if (rootDir != null) writeBytes(byteArrayOf(0, 0, 0, 1))
                },
                mimeType = "video/mp4",
                sizeBytes = 1024L,
                durationMs = 15_000L,
                originalFileName = uri.lastPathSegment ?: "fake_video.mp4",
            ),
        )
    }

    override fun deleteFile(path: String) {
        deletedPaths += path
        File(path).takeIf { it.exists() }?.delete()
    }

    override fun deleteAll() {
        rootDir?.listFiles()?.forEach { it.delete() }
    }

    override fun totalBytes(): Long = rootDir?.listFiles()?.sumOf { it.length() } ?: 0L
    override fun getMediaDirectory(): File = rootDir ?: File("/tmp/reelix_media")
}

class FakeAudioExtractor : AudioExtractor {
    var nextResult: ((File, File) -> AudioExtractionResult)? = null

    override suspend fun extractAudio(sourceMediaFile: File, outputWavFile: File): AudioExtractionResult {
        return nextResult?.invoke(sourceMediaFile, outputWavFile) ?: run {
            outputWavFile.parentFile?.mkdirs()
            outputWavFile.writeBytes(ByteArray(44))
            AudioExtractionResult.Success(
                audioFile = outputWavFile,
                sampleRate = 16000,
                channels = 1,
                durationMs = 15_000L,
            )
        }
    }
}

class FakeTranscriptionModelManager : TranscriptionModelManager {
    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.NotInstalled)
    override val modelStatus: Flow<ModelStatus> = _status.asStateFlow()
    var nextImportOutcome: ModelImportOutcome = ModelImportOutcome.Success(
        ModelStatus.Ready("ggml-tiny.bin", 75_000_000L, "GGML", "/tmp/ggml-tiny.bin"),
    )

    override fun currentStatus(): ModelStatus = _status.value
    override suspend fun importModel(uri: Uri, contentResolver: ContentResolver): ModelImportOutcome {
        if (nextImportOutcome is ModelImportOutcome.Success) {
            _status.value = (nextImportOutcome as ModelImportOutcome.Success).status
        }
        return nextImportOutcome
    }
    override suspend fun deleteModel(): Boolean {
        _status.value = ModelStatus.NotInstalled
        return true
    }
    override fun getModelFile(): File? = null
}

class TestAppContainer(
    context: Context,
    override val clock: MutableClock = MutableClock(),
    val fakeSettings: FakeSettingsRepository = FakeSettingsRepository(),
    val fakeThumbnails: FakeThumbnailStore = FakeThumbnailStore(),
    val fakeMediaStorage: FakeMediaStorage = FakeMediaStorage(),
    val fakeAudioExtractor: FakeAudioExtractor = FakeAudioExtractor(),
    val fakeModelManager: FakeTranscriptionModelManager = FakeTranscriptionModelManager(),
    val fakeScheduler: RecordingProcessingScheduler = RecordingProcessingScheduler(),
    val fakeCloudClient: app.reelix.cloud.MockCloudSyncClient = app.reelix.cloud.MockCloudSyncClient(),
    acquisitionProvider: ContentAcquisitionProvider = UrlOnlyAcquisitionProvider(),
    metadataExtractor: MetadataExtractor = object : MetadataExtractor {
        override val id: String = "unavailable-test"
        override suspend fun extract(link: app.reelix.domain.model.InstagramLink): MetadataOutcome =
            MetadataOutcome.NotAvailable("No public preview in offline test")
    },
    override val transcriptionProvider: TranscriptionProvider = UnavailableTranscriptionProvider(),
    analysisResolver: AnalysisProviderResolver = AnalysisProviderResolver { null },
    backoffPolicy: BackoffPolicy = BackoffPolicy(baseDelayMs = 10_000L, maxDelayMs = 120_000L, maxAttempts = 3),
) : AppContainer {

    override val database: ReelixDatabase = ReelixDatabase.inMemory(context)
    override val thumbnails: ThumbnailStore = fakeThumbnails
    override val mediaStorage: MediaStorage = fakeMediaStorage
    override val audioExtractor: AudioExtractor = fakeAudioExtractor
    override val transcriptionModelManager: TranscriptionModelManager = fakeModelManager

    override val storageInspector: StorageInspector = object : StorageInspector {
        override fun inspect(): StorageSummary = StorageSummary(
            databaseBytes = 4096L,
            thumbnailBytes = thumbnails.totalBytes(),
            mediaBytes = mediaStorage.totalBytes(),
            modelBytes = 0L,
        )
    }
    override val settingsRepository: SettingsRepository = fakeSettings

    val indexer = SearchIndexer(database)

    override val savedItemRepository: SavedItemRepository = SavedItemRepository(
        db = database,
        indexer = indexer,
        thumbnails = thumbnails,
        mediaStorage = mediaStorage,
        clock = clock,
    )

    override val processingRepository: ProcessingRepository = ProcessingRepository(
        db = database,
        items = savedItemRepository,
        indexer = indexer,
        clock = clock,
    )

    override val pipeline: ProcessingPipeline = ProcessingPipeline(
        repository = processingRepository,
        handlers = DefaultStageHandlers.build(
            repository = processingRepository,
            acquisitionProvider = acquisitionProvider,
            metadataExtractor = metadataExtractor,
            audioExtractor = audioExtractor,
            transcriptionProvider = transcriptionProvider,
            analysisResolver = analysisResolver,
            thumbnails = thumbnails,
            settings = settingsRepository,
        ),
        backoff = backoffPolicy,
    )

    override val processingScheduler = fakeScheduler

    override val shareIntakeUseCase: ShareIntakeUseCase = ShareIntakeUseCase(
        repository = savedItemRepository,
        scheduler = processingScheduler,
        mediaStorage = mediaStorage,
        contentResolver = context.contentResolver,
        settingsRepository = settingsRepository,
    )

    override val cloudSyncClient: app.reelix.cloud.CloudSyncClient = fakeCloudClient

    override val cloudSyncRepository: app.reelix.cloud.CloudSyncRepository = app.reelix.cloud.CloudSyncRepository(
        db = database,
        savedItemRepository = savedItemRepository,
        cloudClient = cloudSyncClient,
        settingsRepository = settingsRepository,
        indexer = indexer,
        clock = clock,
    )

    val fakeGoogleDriveAuthManager: FakeGoogleDriveAuthManager = FakeGoogleDriveAuthManager()
    override val googleDriveAuthManager: app.reelix.cloud.GoogleDriveAuthManager = fakeGoogleDriveAuthManager

    fun close() {
        database.close()
    }
}

class FakeGoogleDriveAuthManager(
    var initialOutcome: app.reelix.cloud.DriveAuthOutcome = app.reelix.cloud.DriveAuthOutcome.Success("fake_test_token_abc123"),
    var initialTokenResult: app.reelix.cloud.DriveTokenResult = app.reelix.cloud.DriveTokenResult.Success("fake_test_token_abc123"),
    var silentRefreshToken: String? = "fake_refreshed_token_xyz789",
) : app.reelix.cloud.GoogleDriveAuthManager {
    var startAuthorizationCalled = false
    var extractTokenCalled = false
    var silentRefreshCalled = false

    override suspend fun startAuthorization(context: Context): app.reelix.cloud.DriveAuthOutcome {
        startAuthorizationCalled = true
        return initialOutcome
    }

    override fun extractTokenFromResult(
        context: Context,
        resultCode: Int,
        data: Intent?,
    ): app.reelix.cloud.DriveTokenResult {
        extractTokenCalled = true
        return initialTokenResult
    }

    override suspend fun silentRefresh(context: Context): String? {
        silentRefreshCalled = true
        return silentRefreshToken
    }
}
