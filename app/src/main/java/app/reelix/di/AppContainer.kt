package app.reelix.di

import android.content.Context
import app.reelix.core.Clock
import app.reelix.data.db.ReelixDatabase
import app.reelix.data.files.AndroidStorageInspector
import app.reelix.data.files.AppPrivateMediaStorage
import app.reelix.data.files.FileThumbnailStore
import app.reelix.data.files.MediaStorage
import app.reelix.data.files.StorageInspector
import app.reelix.data.files.ThumbnailStore
import app.reelix.data.media.AndroidMediaCodecAudioExtractor
import app.reelix.data.media.AudioExtractor
import app.reelix.data.repository.SavedItemRepository
import app.reelix.data.repository.SearchIndexer
import app.reelix.data.settings.DataStoreSettingsRepository
import app.reelix.data.settings.KeystoreSecretStore
import app.reelix.data.settings.SecretStore
import app.reelix.data.settings.SettingsRepository
import app.reelix.data.settings.settingsDataStore
import app.reelix.domain.usecase.ShareIntakeUseCase
import app.reelix.integrations.acquisition.ContentAcquisitionProvider
import app.reelix.integrations.acquisition.UrlOnlyAcquisitionProvider
import app.reelix.integrations.analysis.AnalysisProviderResolver
import app.reelix.integrations.analysis.SettingsAnalysisProviderResolver
import app.reelix.integrations.metadata.MetadataExtractor
import app.reelix.integrations.metadata.OkHttpPageFetcher
import app.reelix.integrations.metadata.OpenGraphMetadataExtractor
import app.reelix.integrations.transcription.LocalWhisperModelManager
import app.reelix.integrations.transcription.LocalWhisperTranscriptionProvider
import app.reelix.integrations.transcription.TranscriptionModelManager
import app.reelix.integrations.transcription.TranscriptionProvider
import app.reelix.processing.DefaultStageHandlers
import app.reelix.processing.ProcessingPipeline
import app.reelix.processing.ProcessingRepository
import app.reelix.processing.ProcessingScheduler
import app.reelix.processing.WorkManagerProcessingScheduler
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

interface AppContainer {
    val clock: Clock
    val database: ReelixDatabase
    val thumbnails: ThumbnailStore
    val mediaStorage: MediaStorage
    val audioExtractor: AudioExtractor
    val transcriptionModelManager: TranscriptionModelManager
    val transcriptionProvider: TranscriptionProvider
    val storageInspector: StorageInspector
    val settingsRepository: SettingsRepository
    val savedItemRepository: SavedItemRepository
    val processingRepository: ProcessingRepository
    val pipeline: ProcessingPipeline
    val processingScheduler: ProcessingScheduler
    val shareIntakeUseCase: ShareIntakeUseCase
    val cloudSyncClient: app.reelix.cloud.CloudSyncClient
    val cloudSyncRepository: app.reelix.cloud.CloudSyncRepository
}

class DefaultAppContainer(
    context: Context,
    override val clock: Clock = Clock.SYSTEM,
    secretStoreProvider: (() -> SecretStore)? = null,
) : AppContainer {

    private val appContext = context.applicationContext

    private val secretStore: SecretStore by lazy {
        secretStoreProvider?.invoke() ?: KeystoreSecretStore(appContext)
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override val database: ReelixDatabase by lazy { ReelixDatabase.build(appContext) }

    override val thumbnails: ThumbnailStore by lazy {
        FileThumbnailStore(
            directory = File(appContext.filesDir, "thumbnails"),
            client = httpClient,
        )
    }

    override val mediaStorage: MediaStorage by lazy {
        AppPrivateMediaStorage(appContext)
    }

    override val audioExtractor: AudioExtractor by lazy {
        AndroidMediaCodecAudioExtractor()
    }

    override val transcriptionModelManager: TranscriptionModelManager by lazy {
        LocalWhisperModelManager(appContext)
    }

    override val transcriptionProvider: TranscriptionProvider by lazy {
        LocalWhisperTranscriptionProvider(transcriptionModelManager)
    }

    override val storageInspector: StorageInspector by lazy {
        AndroidStorageInspector(
            context = appContext,
            thumbnails = thumbnails,
            mediaStorage = mediaStorage,
            modelManager = transcriptionModelManager,
        )
    }

    override val settingsRepository: SettingsRepository by lazy {
        DataStoreSettingsRepository(
            dataStore = appContext.settingsDataStore(),
            secretStore = secretStore,
        )
    }

    private val searchIndexer: SearchIndexer by lazy { SearchIndexer(database) }

    override val savedItemRepository: SavedItemRepository by lazy {
        SavedItemRepository(
            db = database,
            indexer = searchIndexer,
            thumbnails = thumbnails,
            mediaStorage = mediaStorage,
            clock = clock,
        )
    }

    private val acquisitionProvider: ContentAcquisitionProvider by lazy { UrlOnlyAcquisitionProvider() }
    private val metadataExtractor: MetadataExtractor by lazy {
        OpenGraphMetadataExtractor(OkHttpPageFetcher(httpClient))
    }
    private val analysisResolver: AnalysisProviderResolver by lazy {
        SettingsAnalysisProviderResolver(settingsRepository, httpClient)
    }

    override val processingRepository: ProcessingRepository by lazy {
        ProcessingRepository(
            db = database,
            items = savedItemRepository,
            indexer = searchIndexer,
            clock = clock,
        )
    }

    override val pipeline: ProcessingPipeline by lazy {
        ProcessingPipeline(
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
        )
    }

    override val processingScheduler: ProcessingScheduler by lazy {
        WorkManagerProcessingScheduler(
            context = appContext,
            settings = settingsRepository,
        )
    }

    override val shareIntakeUseCase: ShareIntakeUseCase by lazy {
        ShareIntakeUseCase(
            repository = savedItemRepository,
            scheduler = processingScheduler,
            mediaStorage = mediaStorage,
            contentResolver = appContext.contentResolver,
            settingsRepository = settingsRepository,
        )
    }

    override val cloudSyncClient: app.reelix.cloud.CloudSyncClient by lazy {
        app.reelix.cloud.GoogleDriveCloudClient(
            client = httpClient,
            folderIdProvider = { settingsRepository.current().cloudDriveFolderId },
            tokenProvider = { settingsRepository.getValidDriveAccessToken() },
            tokenRefresher = { settingsRepository.refreshCloudDriveAccessToken() },
        )
    }

    override val cloudSyncRepository: app.reelix.cloud.CloudSyncRepository by lazy {
        app.reelix.cloud.CloudSyncRepository(
            db = database,
            savedItemRepository = savedItemRepository,
            cloudClient = cloudSyncClient,
            settingsRepository = settingsRepository,
            indexer = searchIndexer,
            clock = clock,
        )
    }
}
