package app.reelix.ui.screens.settings

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.reelix.BuildConfig
import app.reelix.data.files.StorageInspector
import app.reelix.data.files.StorageSummary
import app.reelix.data.repository.SavedItemRepository
import app.reelix.data.settings.AppSettings
import app.reelix.data.settings.SettingsRepository
import app.reelix.data.settings.ThemeMode
import app.reelix.domain.model.LibraryCounts
import app.reelix.integrations.analysis.ConnectionTestResult
import app.reelix.integrations.analysis.OpenAiCompatibleAnalysisProvider
import app.reelix.integrations.transcription.ModelImportOutcome
import app.reelix.integrations.transcription.ModelStatus
import app.reelix.integrations.transcription.TranscriptionModelManager
import app.reelix.processing.ProcessingScheduler
import app.reelix.processing.WorkerQueueState
import app.reelix.ui.components.ConfirmDeleteDialog
import app.reelix.ui.components.Formatters
import app.reelix.ui.components.Pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val savedItemRepository: SavedItemRepository,
    private val scheduler: ProcessingScheduler,
    private val storageInspector: StorageInspector,
    private val modelManager: TranscriptionModelManager,
    private val cloudSyncRepository: app.reelix.cloud.CloudSyncRepository? = null,
    private val cloudSyncClient: app.reelix.cloud.CloudSyncClient? = null,
    private val googleDriveAuthManager: app.reelix.cloud.GoogleDriveAuthManager? = null,
    private val appContext: Context? = null,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val counts: StateFlow<LibraryCounts> = savedItemRepository.observeCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryCounts())

    val workerState: StateFlow<WorkerQueueState> = scheduler.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkerQueueState())

    val modelStatus: StateFlow<ModelStatus> = modelManager.modelStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), modelManager.currentStatus())

    private val _storage = MutableStateFlow(StorageSummary(0L, 0L, 0L, 0L))
    val storage: StateFlow<StorageSummary> = _storage.asStateFlow()

    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    private val _connectionTest = MutableStateFlow<ConnectionTestResult?>(null)
    val connectionTest: StateFlow<ConnectionTestResult?> = _connectionTest.asStateFlow()

    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    private val _isSyncingCloud = MutableStateFlow(false)
    val isSyncingCloud: StateFlow<Boolean> = _isSyncingCloud.asStateFlow()

    private val _isConnectingDrive = MutableStateFlow(false)
    val isConnectingDrive: StateFlow<Boolean> = _isConnectingDrive.asStateFlow()

    private val _isCreatingVault = MutableStateFlow(false)
    val isCreatingVault: StateFlow<Boolean> = _isCreatingVault.asStateFlow()

    private val _isCreatingProbe = MutableStateFlow(false)
    val isCreatingProbe: StateFlow<Boolean> = _isCreatingProbe.asStateFlow()

    private val _crossClientProbeResult = MutableStateFlow<app.reelix.cloud.CrossClientProbeResult?>(null)
    val crossClientProbeResult: StateFlow<app.reelix.cloud.CrossClientProbeResult?> = _crossClientProbeResult.asStateFlow()

    private val _isVerifyingProbe = MutableStateFlow(false)
    val isVerifyingProbe: StateFlow<Boolean> = _isVerifyingProbe.asStateFlow()

    private val _crossClientProbeVerification = MutableStateFlow<app.reelix.cloud.CrossClientProbeVerification?>(null)
    val crossClientProbeVerification: StateFlow<app.reelix.cloud.CrossClientProbeVerification?> = _crossClientProbeVerification.asStateFlow()

    private val _isCleaningUpProbe = MutableStateFlow(false)
    val isCleaningUpProbe: StateFlow<Boolean> = _isCleaningUpProbe.asStateFlow()

    private val _probeError = MutableStateFlow<String?>(null)
    val probeError: StateFlow<String?> = _probeError.asStateFlow()

    init {
        refreshStorage()
        viewModelScope.launch {
            settingsRepository.settings.collect { current ->
                if (current.lastTestProbeFileId.isNotBlank()) {
                    if (_crossClientProbeResult.value?.fileId != current.lastTestProbeFileId ||
                        _crossClientProbeResult.value?.folderId != current.lastTestProbeFolderId
                    ) {
                        _crossClientProbeResult.value = app.reelix.cloud.CrossClientProbeResult(
                            folderId = current.lastTestProbeFolderId,
                            fileId = current.lastTestProbeFileId,
                            folderName = current.lastTestProbeFolderName,
                        )
                    }
                } else {
                    _crossClientProbeResult.value = null
                }
            }
        }
    }

    fun refreshStorage() {
        viewModelScope.launch {
            _storage.value = withContext(Dispatchers.IO) { storageInspector.inspect() }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(themeMode = mode) }
        }
    }

    fun setAutoProcess(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(autoProcess = enabled) }
            scheduler.syncPeriodicSchedule()
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(wifiOnly = enabled) }
            scheduler.syncPeriodicSchedule()
        }
    }

    fun setRequiresCharging(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(requiresCharging = enabled) }
            scheduler.syncPeriodicSchedule()
        }
    }

    fun setJobsPerRun(count: Int) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(jobsPerRun = count.coerceIn(AppSettings.JOBS_PER_RUN_RANGE)) }
        }
    }

    fun setMaxMediaImportSizeMb(mb: Int) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(maxMediaImportSizeMb = mb.coerceIn(AppSettings.MEDIA_IMPORT_SIZE_RANGE_MB)) }
        }
    }

    fun setFetchLinkPreviews(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(fetchLinkPreviews = enabled) }
            scheduler.syncPeriodicSchedule()
        }
    }

    fun setDownloadThumbnails(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(downloadThumbnails = enabled) }
        }
    }

    fun importTranscriptionModel(uri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            _feedback.value = "Importing transcription model..."
            when (val outcome = modelManager.importModel(uri, contentResolver)) {
                is ModelImportOutcome.Success -> {
                    refreshStorage()
                    _feedback.value = "Imported model: ${outcome.status.fileName} (${outcome.status.sizeBytes / (1024 * 1024)} MB)"
                }
                is ModelImportOutcome.InvalidFormat -> {
                    _feedback.value = "Invalid model: ${outcome.reason}"
                }
                is ModelImportOutcome.FileTooSmall -> {
                    _feedback.value = "Selected file is too small to be a valid Whisper model."
                }
                is ModelImportOutcome.InsufficientStorage -> {
                    _feedback.value = "Not enough device storage (${outcome.availableBytes / (1024 * 1024)} MB free, needs ${outcome.requiredBytes / (1024 * 1024)} MB)."
                }
                is ModelImportOutcome.Failure -> {
                    _feedback.value = "Failed to import model: ${outcome.error}"
                }
            }
        }
    }

    fun deleteTranscriptionModel() {
        viewModelScope.launch {
            modelManager.deleteModel()
            refreshStorage()
            _feedback.value = "Transcription model deleted."
        }
    }

    fun testAiConnection(baseUrl: String, model: String, apiKey: String?) {
        viewModelScope.launch {
            _isTestingConnection.value = true
            _connectionTest.value = null
            try {
                val result = settingsRepository.testAiConnection(baseUrl, model, apiKey)
                _connectionTest.value = result
            } finally {
                _isTestingConnection.value = false
            }
        }
    }

    fun saveAnalysisConfig(
        enabled: Boolean,
        baseUrl: String,
        model: String,
        newApiKeyInput: String,
    ) {
        viewModelScope.launch {
            val trimmedUrl = baseUrl.trim()
            val trimmedModel = model.trim()
            if (enabled || trimmedUrl.isNotEmpty()) {
                if (OpenAiCompatibleAnalysisProvider.parseBaseUrl(trimmedUrl) == null) {
                    _feedback.value = "Enter a valid HTTPS endpoint URL (or http://127.0.0.1 / localhost for a local server)."
                    return@launch
                }
            }
            if (enabled && trimmedModel.isEmpty()) {
                _feedback.value = "Enter a model name to enable AI enrichment."
                return@launch
            }
            if (newApiKeyInput.isNotBlank()) {
                settingsRepository.setApiKey(newApiKeyInput.trim())
            }
            settingsRepository.update { current ->
                current.copy(
                    analysis = current.analysis.copy(
                        enabled = enabled,
                        baseUrl = trimmedUrl,
                        model = trimmedModel,
                    ),
                )
            }
            scheduler.syncPeriodicSchedule()
            _feedback.value = "AI integration settings saved."
        }
    }

    fun setCloudProcessingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(cloudProcessingEnabled = enabled) }
            scheduler.syncPeriodicSchedule()
            _feedback.value = if (enabled) {
                "Cloud processing enabled. Heavy stages offloaded to GitHub Actions."
            } else {
                "Cloud processing disabled. Using local device processing."
            }
        }
    }

    fun saveCloudConfig(
        enabled: Boolean,
        folderId: String,
        tokenInput: String,
        refreshTokenInput: String = "",
    ) {
        viewModelScope.launch {
            if (tokenInput.isNotBlank() || refreshTokenInput.isNotBlank()) {
                settingsRepository.setCloudDriveCredentials(
                    accessToken = tokenInput.ifBlank { null },
                    refreshToken = refreshTokenInput.ifBlank { null },
                )
            }
            settingsRepository.update {
                it.copy(
                    cloudProcessingEnabled = enabled,
                    cloudDriveFolderId = folderId.trim(),
                )
            }
            scheduler.syncPeriodicSchedule()
            _feedback.value = "Cloud processing configuration saved in Android Keystore."
        }
    }

    fun syncCloudNow() {
        viewModelScope.launch {
            val repo = cloudSyncRepository
            if (repo == null) {
                _feedback.value = "Cloud sync repository is unavailable."
                return@launch
            }
            _isSyncingCloud.value = true
            try {
                val synced = withContext(Dispatchers.IO) { repo.syncCompletedJobs() }
                _feedback.value = if (synced > 0) {
                    "Synced $synced completed cloud job(s)."
                } else {
                    "Cloud sync complete. No new completed jobs found in Drive."
                }
            } catch (e: Exception) {
                _feedback.value = "Cloud sync error: ${e.message}"
            } finally {
                _isSyncingCloud.value = false
            }
        }
    }

    fun removeStoredApiKey() {
        viewModelScope.launch {
            settingsRepository.setApiKey(null)
            _feedback.value = "Stored API key removed."
        }
    }

    fun runQueueNow() {
        viewModelScope.launch {
            scheduler.scheduleImmediate(forceRunNow = true)
            _feedback.value = "Background processing triggered."
        }
    }

    fun queueItemsAwaitingAi() {
        viewModelScope.launch {
            val count = savedItemRepository.queueItemsAwaitingAnalysis()
            if (count > 0) {
                scheduler.scheduleImmediate(forceRunNow = true)
                _feedback.value = "Queued $count item(s) for AI enrichment."
            } else {
                _feedback.value = "No items are waiting for AI enrichment."
            }
        }
    }

    fun clearAllLocalData() {
        viewModelScope.launch {
            scheduler.cancelAll()
            savedItemRepository.clearAll()
            refreshStorage()
            _feedback.value = "All saved items, media, and thumbnails cleared."
        }
    }

    fun setFeedback(message: String) {
        _feedback.value = message
    }

    fun consumeFeedback() {
        _feedback.value = null
    }

    fun connectGoogleDrive(launchResolution: (IntentSenderRequest) -> Unit) {
        viewModelScope.launch {
            val manager = googleDriveAuthManager
            val ctx = appContext
            if (manager == null || ctx == null) {
                _feedback.value = "Google Drive authorization is unavailable."
                return@launch
            }
            _isConnectingDrive.value = true
            try {
                when (val outcome = manager.startAuthorization(ctx)) {
                    is app.reelix.cloud.DriveAuthOutcome.Success -> {
                        settingsRepository.saveCloudDriveAccessToken(outcome.accessToken)
                        _feedback.value = "Connected to Google Drive successfully."
                    }
                    is app.reelix.cloud.DriveAuthOutcome.ResolutionRequired -> {
                        launchResolution(outcome.intentSenderRequest)
                    }
                    is app.reelix.cloud.DriveAuthOutcome.Cancelled -> {
                        _feedback.value = "Google Drive authorization was cancelled."
                    }
                    is app.reelix.cloud.DriveAuthOutcome.Error -> {
                        _feedback.value = "Google Drive connection error: ${outcome.message}"
                    }
                }
            } catch (e: Exception) {
                _feedback.value = "Failed to connect Google Drive: ${e.message}"
            } finally {
                _isConnectingDrive.value = false
            }
        }
    }

    fun onAuthorizationResult(resultCode: Int, data: Intent?) {
        viewModelScope.launch {
            val manager = googleDriveAuthManager
            val ctx = appContext
            if (manager == null || ctx == null) return@launch
            _isConnectingDrive.value = true
            try {
                when (val result = manager.extractTokenFromResult(ctx, resultCode, data)) {
                    is app.reelix.cloud.DriveTokenResult.Success -> {
                        settingsRepository.saveCloudDriveAccessToken(result.accessToken)
                        _feedback.value = "Connected to Google Drive successfully."
                    }
                    is app.reelix.cloud.DriveTokenResult.Cancelled -> {
                        _feedback.value = "Google Drive authorization was cancelled."
                    }
                    is app.reelix.cloud.DriveTokenResult.Error -> {
                        _feedback.value = "Google Drive authorization failed: ${result.message}"
                    }
                }
            } catch (e: Exception) {
                _feedback.value = "Error handling authorization result: ${e.message}"
            } finally {
                _isConnectingDrive.value = false
            }
        }
    }

    fun disconnectGoogleDrive() {
        viewModelScope.launch {
            settingsRepository.clearCloudDriveCredentials()
            _feedback.value = "Google Drive disconnected."
        }
    }

    fun createReelixVault() {
        viewModelScope.launch {
            val client = cloudSyncClient
            val repo = cloudSyncRepository
            if (client == null && repo == null) {
                _feedback.value = "Google Drive client is unavailable."
                return@launch
            }
            _isCreatingVault.value = true
            try {
                val result = client?.createOrGetVaultFolder() ?: repo!!.createOrGetVaultFolder()
                result.fold(
                    onSuccess = { folderId ->
                        settingsRepository.update { current ->
                            current.copy(
                                cloudDriveFolderId = folderId,
                                cloudProcessingEnabled = true,
                            )
                        }
                        _feedback.value = "Reelix Vault ready! Folder ID configured: $folderId"
                    },
                    onFailure = { error ->
                        _feedback.value = "Failed to create Reelix Vault: ${error.message}"
                    }
                )
            } catch (e: Exception) {
                _feedback.value = "Failed to create Reelix Vault: ${e.message}"
            } finally {
                _isCreatingVault.value = false
            }
        }
    }

    fun createCrossClientTestProbe() {
        viewModelScope.launch {
            val client = cloudSyncClient
            val repo = cloudSyncRepository
            if (client == null && repo == null) {
                val err = "Google Drive client is unavailable."
                _probeError.value = err
                _feedback.value = err
                return@launch
            }
            _isCreatingProbe.value = true
            _probeError.value = null
            try {
                val result = client?.createCrossClientTestProbe() ?: repo!!.createCrossClientTestProbe()
                result.fold(
                    onSuccess = { probe ->
                        _crossClientProbeResult.value = probe
                        _probeError.value = null
                        settingsRepository.saveTestProbe(probe.folderId, probe.fileId, probe.folderName)
                        _feedback.value = "Cross-client test probe created successfully!"
                    },
                    onFailure = { error ->
                        val msg = "Failed to create test probe: ${error.message}"
                        _probeError.value = msg
                        _feedback.value = msg
                    }
                )
            } catch (e: Exception) {
                val msg = "Failed to create test probe: ${e.message}"
                _probeError.value = msg
                _feedback.value = msg
            } finally {
                _isCreatingProbe.value = false
            }
        }
    }

    fun verifyCrossClientTestProbe(customFileId: String? = null) {
        val targetFileId = customFileId?.trim()?.takeIf { it.isNotEmpty() }
            ?: _crossClientProbeResult.value?.fileId
            ?: return
        viewModelScope.launch {
            val client = cloudSyncClient
            val repo = cloudSyncRepository
            if (client == null && repo == null) {
                val err = "Google Drive client is unavailable."
                _probeError.value = err
                _feedback.value = err
                return@launch
            }
            _isVerifyingProbe.value = true
            try {
                val result = client?.verifyCrossClientTestProbe(targetFileId) ?: repo!!.verifyCrossClientTestProbe(targetFileId)
                result.fold(
                    onSuccess = { verification ->
                        _crossClientProbeVerification.value = verification
                        _probeError.value = null
                        _feedback.value = verification.message
                    },
                    onFailure = { error ->
                        val msg = "Probe verification failed: ${error.message}"
                        _probeError.value = msg
                        _feedback.value = msg
                    }
                )
            } catch (e: Exception) {
                val msg = "Error verifying test probe: ${e.message}"
                _probeError.value = msg
                _feedback.value = msg
            } finally {
                _isVerifyingProbe.value = false
            }
        }
    }

    fun cleanupCrossClientTestProbe() {
        val probe = _crossClientProbeResult.value ?: return
        viewModelScope.launch {
            val client = cloudSyncClient
            val repo = cloudSyncRepository
            if (client == null && repo == null) {
                val err = "Google Drive client is unavailable."
                _probeError.value = err
                _feedback.value = err
                return@launch
            }
            _isCleaningUpProbe.value = true
            try {
                val result = client?.cleanupCrossClientTestProbe(probe.folderId, probe.fileId) ?: repo!!.cleanupCrossClientTestProbe(probe.folderId, probe.fileId)
                result.fold(
                    onSuccess = {
                        _crossClientProbeResult.value = null
                        _crossClientProbeVerification.value = null
                        _probeError.value = null
                        settingsRepository.clearTestProbe()
                        _feedback.value = "Test probe cleaned up successfully from Drive."
                    },
                    onFailure = { error ->
                        val msg = "Failed to clean up test probe: ${error.message}"
                        _probeError.value = msg
                        _feedback.value = msg
                    }
                )
            } catch (e: Exception) {
                val msg = "Error cleaning up probe: ${e.message}"
                _probeError.value = msg
                _feedback.value = msg
            } finally {
                _isCleaningUpProbe.value = false
            }
        }
    }

    fun restoreKnownProbe() {
        _crossClientProbeResult.value = app.reelix.cloud.CrossClientProbeResult(
            folderId = AppSettings.KNOWN_TEST_PROBE_FOLDER_ID,
            fileId = AppSettings.KNOWN_TEST_PROBE_FILE_ID,
            folderName = AppSettings.KNOWN_TEST_PROBE_FOLDER_NAME,
        )
        _probeError.value = null
        viewModelScope.launch {
            settingsRepository.restoreKnownProbe()
            _feedback.value = "Restored existing cross-client test probe."
        }
    }

    fun clearProbeError() {
        _probeError.value = null
    }

    companion object {
        fun factory(
            settingsRepository: SettingsRepository,
            savedItemRepository: SavedItemRepository,
            scheduler: ProcessingScheduler,
            storageInspector: StorageInspector,
            modelManager: TranscriptionModelManager,
            cloudSyncRepository: app.reelix.cloud.CloudSyncRepository? = null,
            cloudSyncClient: app.reelix.cloud.CloudSyncClient? = null,
            googleDriveAuthManager: app.reelix.cloud.GoogleDriveAuthManager? = null,
            appContext: Context? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(
                    settingsRepository = settingsRepository,
                    savedItemRepository = savedItemRepository,
                    scheduler = scheduler,
                    storageInspector = storageInspector,
                    modelManager = modelManager,
                    cloudSyncRepository = cloudSyncRepository,
                    cloudSyncClient = cloudSyncClient,
                    googleDriveAuthManager = googleDriveAuthManager,
                    appContext = appContext,
                ) as T
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val workerState by viewModel.workerState.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val modelStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val connectionTest by viewModel.connectionTest.collectAsStateWithLifecycle()
    val isTestingConnection by viewModel.isTestingConnection.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }

    var aiEnabled by remember(settings.analysis.enabled) { mutableStateOf(settings.analysis.enabled) }
    var aiBaseUrl by remember(settings.analysis.baseUrl) { mutableStateOf(settings.analysis.baseUrl) }
    var aiModel by remember(settings.analysis.model) { mutableStateOf(settings.analysis.model) }
    var newApiKey by rememberSaveable { mutableStateOf("") }

    var cloudEnabled by remember(settings.cloudProcessingEnabled) { mutableStateOf(settings.cloudProcessingEnabled) }
    var cloudFolderId by remember(settings.cloudDriveFolderId) { mutableStateOf(settings.cloudDriveFolderId) }
    var cloudToken by rememberSaveable { mutableStateOf("") }
    var cloudRefreshToken by rememberSaveable { mutableStateOf("") }
    val isSyncingCloud by viewModel.isSyncingCloud.collectAsStateWithLifecycle()
    val isConnectingDrive by viewModel.isConnectingDrive.collectAsStateWithLifecycle()
    val isCreatingVault by viewModel.isCreatingVault.collectAsStateWithLifecycle()
    val isCreatingProbe by viewModel.isCreatingProbe.collectAsStateWithLifecycle()
    val crossClientProbeResult by viewModel.crossClientProbeResult.collectAsStateWithLifecycle()
    val isVerifyingProbe by viewModel.isVerifyingProbe.collectAsStateWithLifecycle()
    val crossClientProbeVerification by viewModel.crossClientProbeVerification.collectAsStateWithLifecycle()
    val isCleaningUpProbe by viewModel.isCleaningUpProbe.collectAsStateWithLifecycle()
    val probeError by viewModel.probeError.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    val authorizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        viewModel.onAuthorizationResult(result.resultCode, result.data)
    }

    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importTranscriptionModel(uri, context.contentResolver)
        }
    }

    LaunchedEffect(feedback) {
        val msg = feedback ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeFeedback()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Settings") })
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("settings_screen"),
            ) {
                // 1. Background Processing Status & Queue Controls
                item(key = "queue_status") {
                    SettingsCard(title = "Background processing status") {
                        val statusLabel = when {
                            workerState.isRunning -> "Running now"
                            workerState.isEnqueued -> "Scheduled in WorkManager"
                            !settings.autoProcess -> "Automatic runs paused"
                            else -> "Idle"
                        }
                        KeyValueLine("Worker state", statusLabel)
                        KeyValueLine("Items awaiting processing", counts.awaiting.toString())
                        KeyValueLine("Items needing attention", counts.needsAttention.toString())
                        KeyValueLine(
                            "Last batch run",
                            settings.lastRunAt?.let {
                                "${Formatters.formatDateTime(it)} (${settings.lastRunSummary ?: "ok"})"
                            } ?: "Not run yet",
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = viewModel::runQueueNow,
                            modifier = Modifier.fillMaxWidth().testTag("run_queue_now_button"),
                        ) {
                            Text("Run processing queue now")
                        }
                    }
                }

                // 2. Cloud Processing (GitHub Actions & Google Drive)
                item(key = "cloud_processing") {
                    SettingsCard(title = "Cloud processing (GitHub Actions & Google Drive)") {
                        Text(
                            text = "Offload CPU-heavy audio extraction (FFmpeg), speech transcription (Whisper), and AI enrichment (Gemini) to free GitHub Actions runners, using Google Drive for durable storage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        ToggleRow(
                            title = "Enable cloud offloading",
                            subtitle = "Saves phone battery and storage by processing video/audio in GitHub Actions.",
                            checked = cloudEnabled,
                            onCheckedChange = { cloudEnabled = it },
                        )
                        Spacer(Modifier.height(10.dp))

                        // Native Google Drive Connection
                        if (settings.hasCloudDriveToken) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = "Google Drive connected",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(
                                    onClick = viewModel::disconnectGoogleDrive,
                                    modifier = Modifier.testTag("disconnect_google_drive_button"),
                                ) {
                                    Text("Disconnect")
                                }
                            }
                            Spacer(Modifier.height(6.dp))

                            // Create Reelix Vault button when connected but no folder ID configured
                            if (cloudFolderId.isBlank()) {
                                Button(
                                    onClick = viewModel::createReelixVault,
                                    enabled = !isCreatingVault,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("create_reelix_vault_button"),
                                ) {
                                    if (isCreatingVault) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Creating Reelix Vault...")
                                    } else {
                                        Icon(
                                            imageVector = Icons.Outlined.CreateNewFolder,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Create Reelix Vault")
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }

                            // Cross-Client Verification Test Probe
                            Spacer(Modifier.height(4.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))

                            Text(
                                text = "Cross-Client Verification",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Create an isolated test folder and probe file in Drive to verify GitHub Actions worker access without touching your Reelix Vault.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))

                            // State 3: Error Banner (shown if probeError != null, without hiding other controls)
                            probeError?.let { err ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("probe_error_card"),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(10.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.ErrorOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = err,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("probe_error_text"),
                                        )
                                        IconButton(
                                            onClick = viewModel::clearProbeError,
                                            modifier = Modifier
                                                .size(24.dp)
                                                .testTag("dismiss_probe_error_button"),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Delete,
                                                contentDescription = "Dismiss error",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }

                            val activeProbe = crossClientProbeResult
                            if (activeProbe == null) {
                                // State 1: No saved probe
                                Text(
                                    text = "No active test probe. Create a probe or restore the existing probe.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("no_probe_status_text"),
                                )
                                Spacer(Modifier.height(8.dp))

                                Button(
                                    onClick = viewModel::createCrossClientTestProbe,
                                    enabled = !isCreatingProbe,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("create_cross_client_probe_button"),
                                ) {
                                    if (isCreatingProbe) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Creating Test Probe...")
                                    } else {
                                        Icon(
                                            imageVector = Icons.Outlined.CheckCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("Create Cross-Client Test Probe")
                                    }
                                }

                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(
                                    onClick = viewModel::restoreKnownProbe,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("restore_known_probe_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Restore Existing Test Probe")
                                }
                            } else {
                                // State 2: Probe exists
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("cross_client_probe_result_card"),
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.CheckCircle,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "Active Test Probe in Drive",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.testTag("active_probe_title"),
                                            )
                                        }
                                        Spacer(Modifier.height(6.dp))

                                        Text(
                                            text = "TEST_FOLDER_ID",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            SelectionContainer(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = activeProbe.folderId,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.testTag("probe_folder_id_text"),
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    clipboard.setText(AnnotatedString(activeProbe.folderId))
                                                    viewModel.setFeedback("Copied TEST_FOLDER_ID to clipboard")
                                                },
                                                modifier = Modifier.testTag("copy_probe_folder_id_button"),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.ContentCopy,
                                                    contentDescription = "Copy Folder ID",
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }

                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "TEST_FILE_ID",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            SelectionContainer(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = activeProbe.fileId,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.testTag("probe_file_id_text"),
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    clipboard.setText(AnnotatedString(activeProbe.fileId))
                                                    viewModel.setFeedback("Copied TEST_FILE_ID to clipboard")
                                                },
                                                modifier = Modifier.testTag("copy_probe_file_id_button"),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.ContentCopy,
                                                    contentDescription = "Copy File ID",
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }

                                        Spacer(Modifier.height(6.dp))
                                        OutlinedButton(
                                            onClick = {
                                                val formatted = "folder_id: ${activeProbe.folderId}\nfile_id: ${activeProbe.fileId}"
                                                clipboard.setText(AnnotatedString(formatted))
                                                viewModel.setFeedback("Copied both probe IDs to clipboard")
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("copy_both_probe_ids_button"),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.ContentCopy,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text("Copy Both IDs")
                                        }

                                        // Verification Result Banner
                                        crossClientProbeVerification?.let { verification ->
                                            Spacer(Modifier.height(8.dp))
                                            Card(
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (verification.isVerified) {
                                                        MaterialTheme.colorScheme.primaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.errorContainer
                                                    },
                                                ),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .testTag("probe_verification_result_card"),
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(10.dp),
                                                ) {
                                                    Icon(
                                                        imageVector = if (verification.isVerified) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                                                        contentDescription = null,
                                                        tint = if (verification.isVerified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(20.dp),
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        text = verification.message,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = if (verification.isVerified) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                                        modifier = Modifier.testTag("probe_verification_result_text"),
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(Modifier.height(8.dp))

                                        // Verify Probe Update Button
                                        Button(
                                            onClick = { viewModel.verifyCrossClientTestProbe() },
                                            enabled = !isVerifyingProbe && !isCleaningUpProbe,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("verify_probe_update_button"),
                                        ) {
                                            if (isVerifyingProbe) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text("Verifying Update...")
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Outlined.CheckCircle,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text("Verify Probe Update (Read Version 2)")
                                            }
                                        }

                                        Spacer(Modifier.height(6.dp))

                                        // Cleanup Probe Button
                                        OutlinedButton(
                                            onClick = viewModel::cleanupCrossClientTestProbe,
                                            enabled = !isVerifyingProbe && !isCleaningUpProbe,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("cleanup_probe_button"),
                                        ) {
                                            if (isCleaningUpProbe) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text("Cleaning Up...")
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Outlined.Delete,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text("Clean Up Test Probe")
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        Button(
                            onClick = {
                                viewModel.connectGoogleDrive { intentSenderRequest ->
                                    authorizationLauncher.launch(intentSenderRequest)
                                }
                            },
                            enabled = !isConnectingDrive,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("connect_google_drive_button"),
                        ) {
                            if (isConnectingDrive) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Connecting...")
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.Link,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (settings.hasCloudDriveToken) "Reconnect Google Drive"
                                    else "Connect Google Drive"
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(10.dp))

                        OutlinedTextField(
                            value = cloudFolderId,
                            onValueChange = { cloudFolderId = it },
                            label = { Text("Google Drive Folder ID") },
                            placeholder = { Text("e.g. 1a2B3c4D5e...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("cloud_folder_id_input"),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = cloudToken,
                            onValueChange = { cloudToken = it },
                            label = {
                                Text(
                                    if (settings.hasCloudDriveToken) "Replace Access Token (Stored in Keystore)"
                                    else "Google Drive Access Token (Stored in Keystore)"
                                )
                            },
                            placeholder = { Text("Bearer access token") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("cloud_token_input"),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = cloudRefreshToken,
                            onValueChange = { cloudRefreshToken = it },
                            label = {
                                Text(
                                    if (settings.hasCloudDriveRefreshToken) "Replace Refresh Token (Stored in Keystore)"
                                    else "OAuth Refresh Token (Recommended for auto-refresh)"
                                )
                            },
                            placeholder = { Text("1//04... OAuth refresh token") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("cloud_refresh_token_input"),
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = {
                                    viewModel.saveCloudConfig(cloudEnabled, cloudFolderId, cloudToken, cloudRefreshToken)
                                    cloudToken = ""
                                    cloudRefreshToken = ""
                                },
                                modifier = Modifier.testTag("save_cloud_config_button"),
                            ) {
                                Text("Save cloud config")
                            }
                            OutlinedButton(
                                onClick = viewModel::syncCloudNow,
                                enabled = !isSyncingCloud,
                                modifier = Modifier.testTag("sync_cloud_now_button"),
                            ) {
                                if (isSyncingCloud) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Syncing...")
                                } else {
                                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Sync results now")
                                }
                            }
                        }
                    }
                }

                // 3. Offline Speech Recognition (Whisper)
                item(key = "offline_transcription") {
                    SettingsCard(title = "Offline speech recognition") {
                        Text(
                            text = "Reelix transcribes audio entirely offline on your device using a Whisper GGML model. No third-party APIs, network requests, or subscription keys are required.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))

                        when (val status = modelStatus) {
                            is ModelStatus.Ready -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Model ready",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                KeyValueLine("File", status.fileName)
                                KeyValueLine("Format", status.modelFormat)
                                KeyValueLine("Size", "${status.sizeBytes / (1024 * 1024)} MB")
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { modelPickerLauncher.launch(arrayOf("*/*")) }) {
                                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Replace model")
                                    }
                                    OutlinedButton(
                                        onClick = viewModel::deleteTranscriptionModel,
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error,
                                        ),
                                    ) {
                                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Delete")
                                    }
                                }
                            }
                            is ModelStatus.NotInstalled -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "No transcription model installed",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Supported format: GGML/GGUF Whisper model (e.g. ggml-tiny.bin ~75MB or ggml-base.bin ~142MB). Tap below to import a downloaded model file from your device storage.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = { modelPickerLauncher.launch(arrayOf("*/*")) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Import Whisper model (.bin)")
                                }
                            }
                            is ModelStatus.Error -> {
                                Text(
                                    text = "Error: ${status.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = { modelPickerLauncher.launch(arrayOf("*/*")) }) {
                                    Text("Try importing another model")
                                }
                            }
                        }
                    }
                }

                // 3. Media Ingestion Limits
                item(key = "media_limits") {
                    SettingsCard(title = "Media ingestion limits") {
                        Text(
                            text = "Reelix copies shared video and audio files into app-private storage. You can set a maximum import file size to prevent large files from exhausting device storage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Max media import size: ${settings.maxMediaImportSizeMb} MB",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Slider(
                            value = settings.maxMediaImportSizeMb.toFloat(),
                            onValueChange = { viewModel.setMaxMediaImportSizeMb(it.roundToInt()) },
                            valueRange = 25f..500f,
                            steps = 18,
                        )
                    }
                }

                // 4. Processing & Network Preferences
                item(key = "processing_prefs") {
                    SettingsCard(title = "Processing & network restrictions") {
                        ToggleRow(
                            title = "Automatic background processing",
                            subtitle = "Process saved links and media via WorkManager without keeping the app open.",
                            checked = settings.autoProcess,
                            onCheckedChange = viewModel::setAutoProcess,
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        ToggleRow(
                            title = "Wi-Fi / unmetered network only",
                            subtitle = "Wait for unmetered connectivity when fetching link previews or calling AI.",
                            checked = settings.wifiOnly,
                            onCheckedChange = viewModel::setWifiOnly,
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        ToggleRow(
                            title = "Require device charging",
                            subtitle = "Defer scheduled background runs until the phone is plugged in.",
                            checked = settings.requiresCharging,
                            onCheckedChange = viewModel::setRequiresCharging,
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        ToggleRow(
                            title = "Fetch public OpenGraph link previews",
                            subtitle = "Best-effort public title/caption check without login or scraping.",
                            checked = settings.fetchLinkPreviews,
                            onCheckedChange = viewModel::setFetchLinkPreviews,
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        ToggleRow(
                            title = "Cache preview thumbnails locally",
                            subtitle = "Store legitimate OpenGraph preview images for offline viewing.",
                            checked = settings.downloadThumbnails,
                            onCheckedChange = viewModel::setDownloadThumbnails,
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = "Max items per background run: ${settings.jobsPerRun}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Slider(
                            value = settings.jobsPerRun.toFloat(),
                            onValueChange = { viewModel.setJobsPerRun(it.roundToInt()) },
                            valueRange = 1f..50f,
                        )
                    }
                }

                // 5. Optional AI Enrichment Configuration
                item(key = "ai_config") {
                    SettingsCard(title = "Optional AI integration") {
                        Text(
                            text = "Reelix works 100% without AI and includes no paid or hardcoded API. You may optionally connect a self-hosted or free OpenAI-compatible /chat/completions endpoint (such as local Ollama).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        ToggleRow(
                            title = "Enable AI summarization & tagging",
                            subtitle = "Summarise available captions, transcripts and notes.",
                            checked = aiEnabled,
                            onCheckedChange = { aiEnabled = it },
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = aiBaseUrl,
                            onValueChange = { aiBaseUrl = it },
                            label = { Text("Base URL") },
                            placeholder = { Text("http://127.0.0.1:11434/v1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = aiModel,
                            onValueChange = { aiModel = it },
                            label = { Text("Model name") },
                            placeholder = { Text("llama3.2") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newApiKey,
                            onValueChange = { newApiKey = it },
                            label = {
                                Text(
                                    if (settings.analysis.hasApiKey) {
                                        "Replace API key (key stored in Keystore)"
                                    } else {
                                        "Optional API key (stored in Android Keystore)"
                                    },
                                )
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = {
                                    viewModel.saveAnalysisConfig(aiEnabled, aiBaseUrl, aiModel, newApiKey)
                                    newApiKey = ""
                                },
                            ) {
                                Text("Save AI config")
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.testAiConnection(
                                        baseUrl = aiBaseUrl,
                                        model = aiModel,
                                        apiKey = newApiKey.ifBlank { null },
                                    )
                                },
                                enabled = !isTestingConnection && aiBaseUrl.isNotBlank(),
                            ) {
                                if (isTestingConnection) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Testing...")
                                } else {
                                    Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Test connection")
                                }
                            }
                            if (settings.analysis.hasApiKey) {
                                OutlinedButton(onClick = viewModel::removeStoredApiKey) {
                                    Text("Remove key")
                                }
                            }
                        }

                        // Connection test result display
                        connectionTest?.let { testResult ->
                            Spacer(Modifier.height(10.dp))
                            when (testResult) {
                                is ConnectionTestResult.Success -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Outlined.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = testResult.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    if (testResult.modelsAvailable.isNotEmpty()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "Available models: ${testResult.modelsAvailable.take(5).joinToString(", ")}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                is ConnectionTestResult.Failure -> {
                                    Row(verticalAlignment = Alignment.Top) {
                                        Icon(
                                            imageVector = Icons.Outlined.ErrorOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = testResult.error,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }

                        if (settings.analysis.isComplete) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = viewModel::queueItemsAwaitingAi) {
                                Text("Queue existing items awaiting AI analysis")
                            }
                        }
                    }
                }

                // 6. Appearance
                item(key = "appearance") {
                    SettingsCard(title = "Appearance") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.themeMode == mode,
                                    onClick = { viewModel.setThemeMode(mode) },
                                    label = { Text(mode.label) },
                                )
                            }
                        }
                    }
                }

                // 7. Storage Breakdown & Data Controls
                item(key = "storage_info") {
                    SettingsCard(title = "Local storage") {
                        KeyValueLine("Saved items", counts.total.toString())
                        KeyValueLine("Room SQLite + FTS database", Formatters.formatBytes(storage.databaseBytes))
                        KeyValueLine("Cached preview thumbnails", Formatters.formatBytes(storage.thumbnailBytes))
                        KeyValueLine("Imported media files", Formatters.formatBytes(storage.mediaBytes))
                        KeyValueLine("Offline transcription model", Formatters.formatBytes(storage.modelBytes))
                        KeyValueLine("Total local footprint", Formatters.formatBytes(storage.totalBytes))
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = viewModel::refreshStorage) {
                                Text("Refresh size")
                            }
                            Button(
                                onClick = { showClearConfirm = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                                modifier = Modifier.testTag("clear_data_button"),
                            ) {
                                Text("Clear local data")
                            }
                        }
                    }
                }

                // 8. Privacy & Application Version
                item(key = "privacy_about") {
                    SettingsCard(title = "Privacy & about") {
                        Text(
                            text = "• Single-user, local-first vault: your links, notes, tags, audio and FTS index stay on this device.\n" +
                                "• No Instagram login, scraping or private API calls are ever performed.\n" +
                                "• Offline speech recognition runs locally with no internet connection.\n" +
                                "• Optional API keys are encrypted with AES-256-GCM in the Android Keystore, excluded from backups and never written to logs or UI.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        KeyValueLine("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    }
                }
            }
        }

        if (showClearConfirm) {
            ConfirmDeleteDialog(
                title = "Clear all local vault data?",
                body = "This permanently deletes all ${counts.total} saved items, imported media, notes, tags, search index entries and cached thumbnails from this device.",
                confirmLabel = "Clear everything",
                onConfirm = {
                    showClearConfirm = false
                    viewModel.clearAllLocalData()
                },
                onDismiss = { showClearConfirm = false },
            )
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    OutlinedCard(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun KeyValueLine(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.labelLarge)
    }
}
