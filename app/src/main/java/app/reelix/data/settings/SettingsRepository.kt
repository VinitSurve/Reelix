package app.reelix.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.reelix.core.ReelixLog
import app.reelix.integrations.analysis.ConnectionTestResult
import app.reelix.integrations.analysis.OpenAiCompatibleAnalysisProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings = settings.first()
    suspend fun update(transform: (AppSettings) -> AppSettings)
    suspend fun setApiKey(apiKey: String?)
    fun apiKey(): String?
    suspend fun recordRun(at: Long, summary: String)
    suspend fun testAiConnection(baseUrl: String, model: String, apiKey: String?): ConnectionTestResult
    fun cloudDriveAccessToken(): String?
    fun cloudDriveRefreshToken(): String?
    suspend fun setCloudDriveCredentials(
        accessToken: String?,
        refreshToken: String? = null,
        clientId: String? = null,
        clientSecret: String? = null,
    )
    suspend fun saveCloudDriveAccessToken(accessToken: String) {
        setCloudDriveCredentials(accessToken = accessToken)
    }
    suspend fun clearCloudDriveCredentials() {
        setCloudDriveCredentials(accessToken = null, refreshToken = null, clientId = null, clientSecret = null)
    }
    suspend fun refreshCloudDriveAccessToken(): String?
    suspend fun getValidDriveAccessToken(): String?
    suspend fun saveTestProbe(folderId: String, fileId: String, folderName: String) {
        update { it.copy(
            lastTestProbeFolderId = folderId,
            lastTestProbeFileId = fileId,
            lastTestProbeFolderName = folderName,
        ) }
    }
    suspend fun clearTestProbe() {
        update { it.copy(
            lastTestProbeFolderId = "",
            lastTestProbeFileId = "",
            lastTestProbeFolderName = "",
        ) }
    }
    suspend fun restoreKnownProbe() {
        saveTestProbe(
            folderId = AppSettings.KNOWN_TEST_PROBE_FOLDER_ID,
            fileId = AppSettings.KNOWN_TEST_PROBE_FILE_ID,
            folderName = AppSettings.KNOWN_TEST_PROBE_FOLDER_NAME,
        )
    }
    suspend fun clear()
}

private val Context.reelixDataStore: DataStore<Preferences> by preferencesDataStore(name = "reelix_settings")

fun Context.settingsDataStore(): DataStore<Preferences> = applicationContext.reelixDataStore

/** DataStore-backed settings. Sensitive keys are encrypted with AES-256-GCM in [SecretStore], never in plain preferences. */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val secretStore: SecretStore,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val context: Context? = null,
    private val googleDriveAuthManager: app.reelix.cloud.GoogleDriveAuthManager? = null,
) : SettingsRepository {

    private object Keys {
        val autoProcess = booleanPreferencesKey("auto_process")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val requiresCharging = booleanPreferencesKey("requires_charging")
        val jobsPerRun = intPreferencesKey("jobs_per_run")
        val maxMediaImportSizeMb = intPreferencesKey("max_media_import_size_mb")
        val fetchLinkPreviews = booleanPreferencesKey("fetch_link_previews")
        val downloadThumbnails = booleanPreferencesKey("download_thumbnails")
        val themeMode = stringPreferencesKey("theme_mode")
        val analysisEnabled = booleanPreferencesKey("analysis_enabled")
        val analysisBaseUrl = stringPreferencesKey("analysis_base_url")
        val analysisModel = stringPreferencesKey("analysis_model")
        val cloudProcessingEnabled = booleanPreferencesKey("cloud_processing_enabled")
        val cloudDriveFolderId = stringPreferencesKey("cloud_drive_folder_id")
        val lastRunAt = longPreferencesKey("last_run_at")
        val lastRunSummary = stringPreferencesKey("last_run_summary")
        val lastTestProbeFolderId = stringPreferencesKey("last_test_probe_folder_id")
        val lastTestProbeFileId = stringPreferencesKey("last_test_probe_file_id")
        val lastTestProbeFolderName = stringPreferencesKey("last_test_probe_folder_name")
    }

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    private fun Preferences.toSettings(): AppSettings {
        val p = this
        val defaults = AppSettings()
        val savedFileId = p[Keys.lastTestProbeFileId]
        val (probeFolderId, probeFileId, probeFolderName) = when {
            savedFileId == null -> Triple(
                AppSettings.KNOWN_TEST_PROBE_FOLDER_ID,
                AppSettings.KNOWN_TEST_PROBE_FILE_ID,
                AppSettings.KNOWN_TEST_PROBE_FOLDER_NAME,
            )
            savedFileId.isNotBlank() -> Triple(
                p[Keys.lastTestProbeFolderId].orEmpty(),
                savedFileId,
                p[Keys.lastTestProbeFolderName].orEmpty(),
            )
            else -> Triple("", "", "")
        }
        return AppSettings(
            autoProcess = p[Keys.autoProcess] ?: defaults.autoProcess,
            wifiOnly = p[Keys.wifiOnly] ?: defaults.wifiOnly,
            requiresCharging = p[Keys.requiresCharging] ?: defaults.requiresCharging,
            jobsPerRun = (p[Keys.jobsPerRun] ?: defaults.jobsPerRun).coerceIn(AppSettings.JOBS_PER_RUN_RANGE),
            maxMediaImportSizeMb = p[Keys.maxMediaImportSizeMb] ?: defaults.maxMediaImportSizeMb,
            fetchLinkPreviews = p[Keys.fetchLinkPreviews] ?: defaults.fetchLinkPreviews,
            downloadThumbnails = p[Keys.downloadThumbnails] ?: defaults.downloadThumbnails,
            themeMode = p[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: defaults.themeMode,
            analysis = AnalysisProviderSettings(
                enabled = p[Keys.analysisEnabled] ?: false,
                baseUrl = p[Keys.analysisBaseUrl].orEmpty(),
                model = p[Keys.analysisModel].orEmpty(),
                hasApiKey = secretStore.get(API_KEY_ALIAS) != null,
            ),
            cloudProcessingEnabled = p[Keys.cloudProcessingEnabled] ?: defaults.cloudProcessingEnabled,
            cloudDriveFolderId = p[Keys.cloudDriveFolderId] ?: defaults.cloudDriveFolderId,
            hasCloudDriveToken = secretStore.get(CLOUD_DRIVE_TOKEN_ALIAS) != null,
            hasCloudDriveRefreshToken = secretStore.get(CLOUD_DRIVE_REFRESH_TOKEN_ALIAS) != null,
            lastRunAt = p[Keys.lastRunAt],
            lastRunSummary = p[Keys.lastRunSummary],
            lastTestProbeFolderId = probeFolderId,
            lastTestProbeFileId = probeFileId,
            lastTestProbeFolderName = probeFolderName,
        )
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { p ->
            val current = p.toSettings()
            val next = transform(current)
            p[Keys.autoProcess] = next.autoProcess
            p[Keys.wifiOnly] = next.wifiOnly
            p[Keys.requiresCharging] = next.requiresCharging
            p[Keys.jobsPerRun] = next.jobsPerRun.coerceIn(AppSettings.JOBS_PER_RUN_RANGE)
            p[Keys.maxMediaImportSizeMb] = next.maxMediaImportSizeMb
            p[Keys.fetchLinkPreviews] = next.fetchLinkPreviews
            p[Keys.downloadThumbnails] = next.downloadThumbnails
            p[Keys.themeMode] = next.themeMode.name
            p[Keys.analysisEnabled] = next.analysis.enabled
            p[Keys.analysisBaseUrl] = next.analysis.baseUrl.trim()
            p[Keys.analysisModel] = next.analysis.model.trim()
            p[Keys.cloudProcessingEnabled] = next.cloudProcessingEnabled
            p[Keys.cloudDriveFolderId] = next.cloudDriveFolderId.trim()
            p[Keys.lastTestProbeFolderId] = next.lastTestProbeFolderId.trim()
            p[Keys.lastTestProbeFileId] = next.lastTestProbeFileId.trim()
            p[Keys.lastTestProbeFolderName] = next.lastTestProbeFolderName.trim()
        }
    }

    override suspend fun saveTestProbe(folderId: String, fileId: String, folderName: String) {
        dataStore.edit { p ->
            p[Keys.lastTestProbeFolderId] = folderId.trim()
            p[Keys.lastTestProbeFileId] = fileId.trim()
            p[Keys.lastTestProbeFolderName] = folderName.trim()
        }
    }

    override suspend fun clearTestProbe() {
        dataStore.edit { p ->
            p[Keys.lastTestProbeFolderId] = ""
            p[Keys.lastTestProbeFileId] = ""
            p[Keys.lastTestProbeFolderName] = ""
        }
    }

    override suspend fun setApiKey(apiKey: String?) {
        if (apiKey.isNullOrBlank()) secretStore.remove(API_KEY_ALIAS) else secretStore.put(API_KEY_ALIAS, apiKey.trim())
        dataStore.edit { p -> p[Keys.analysisEnabled] = p[Keys.analysisEnabled] ?: false }
    }

    override fun apiKey(): String? = secretStore.get(API_KEY_ALIAS)

    override fun cloudDriveAccessToken(): String? = secretStore.get(CLOUD_DRIVE_TOKEN_ALIAS)

    override fun cloudDriveRefreshToken(): String? = secretStore.get(CLOUD_DRIVE_REFRESH_TOKEN_ALIAS)

    override suspend fun setCloudDriveCredentials(
        accessToken: String?,
        refreshToken: String?,
        clientId: String?,
        clientSecret: String?,
    ) {
        if (accessToken.isNullOrBlank()) {
            secretStore.remove(CLOUD_DRIVE_TOKEN_ALIAS)
        } else {
            secretStore.put(CLOUD_DRIVE_TOKEN_ALIAS, accessToken.trim())
        }
        if (refreshToken.isNullOrBlank()) {
            secretStore.remove(CLOUD_DRIVE_REFRESH_TOKEN_ALIAS)
        } else {
            secretStore.put(CLOUD_DRIVE_REFRESH_TOKEN_ALIAS, refreshToken.trim())
        }
        if (clientId.isNullOrBlank()) {
            secretStore.remove(CLOUD_DRIVE_CLIENT_ID_ALIAS)
        } else {
            secretStore.put(CLOUD_DRIVE_CLIENT_ID_ALIAS, clientId.trim())
        }
        if (clientSecret.isNullOrBlank()) {
            secretStore.remove(CLOUD_DRIVE_CLIENT_SECRET_ALIAS)
        } else {
            secretStore.put(CLOUD_DRIVE_CLIENT_SECRET_ALIAS, clientSecret.trim())
        }
        // Touch DataStore to notify observers
        dataStore.edit { p ->
            p[Keys.cloudProcessingEnabled] = p[Keys.cloudProcessingEnabled] ?: false
        }
    }

    override suspend fun saveCloudDriveAccessToken(accessToken: String) {
        if (accessToken.isNotBlank()) {
            secretStore.put(CLOUD_DRIVE_TOKEN_ALIAS, accessToken.trim())
            dataStore.edit { p ->
                p[Keys.cloudProcessingEnabled] = p[Keys.cloudProcessingEnabled] ?: false
            }
        }
    }

    override suspend fun clearCloudDriveCredentials() {
        secretStore.remove(CLOUD_DRIVE_TOKEN_ALIAS)
        secretStore.remove(CLOUD_DRIVE_REFRESH_TOKEN_ALIAS)
        secretStore.remove(CLOUD_DRIVE_CLIENT_ID_ALIAS)
        secretStore.remove(CLOUD_DRIVE_CLIENT_SECRET_ALIAS)
        dataStore.edit { p ->
            p[Keys.cloudProcessingEnabled] = false
        }
    }

    override suspend fun refreshCloudDriveAccessToken(): String? = withContext(Dispatchers.IO) {
        // 1. Attempt silent refresh via native Google Identity AuthorizationClient if available
        if (context != null && googleDriveAuthManager != null) {
            val nativeToken = googleDriveAuthManager.silentRefresh(context)
            if (!nativeToken.isNullOrBlank()) {
                secretStore.put(CLOUD_DRIVE_TOKEN_ALIAS, nativeToken)
                ReelixLog.d("Successfully refreshed Google Drive token via native AuthorizationClient")
                return@withContext nativeToken
            }
        }

        // 2. Fall back to OAuth refresh token flow if configured
        val refreshToken = secretStore.get(CLOUD_DRIVE_REFRESH_TOKEN_ALIAS) ?: return@withContext null
        val clientId = secretStore.get(CLOUD_DRIVE_CLIENT_ID_ALIAS)
        val clientSecret = secretStore.get(CLOUD_DRIVE_CLIENT_SECRET_ALIAS)

        val formBuilder = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
        if (!clientId.isNullOrBlank()) formBuilder.add("client_id", clientId)
        if (!clientSecret.isNullOrBlank()) formBuilder.add("client_secret", clientSecret)

        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(formBuilder.build())
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    ReelixLog.w("Failed to refresh Google Drive OAuth token (${response.code})")
                    return@withContext null
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val newAccessToken = json.optString("access_token")
                if (newAccessToken.isNotBlank()) {
                    secretStore.put(CLOUD_DRIVE_TOKEN_ALIAS, newAccessToken)
                    ReelixLog.d("Successfully refreshed Google Drive OAuth access token")
                    return@withContext newAccessToken
                }
                null
            }
        } catch (e: Exception) {
            ReelixLog.w("Exception during Google Drive OAuth token refresh", e)
            null
        }
    }

    override suspend fun getValidDriveAccessToken(): String? {
        val current = cloudDriveAccessToken()
        if (!current.isNullOrBlank()) return current
        return refreshCloudDriveAccessToken()
    }

    override suspend fun recordRun(at: Long, summary: String) {
        dataStore.edit { p ->
            p[Keys.lastRunAt] = at
            p[Keys.lastRunSummary] = summary
        }
    }

    override suspend fun testAiConnection(baseUrl: String, model: String, apiKey: String?): ConnectionTestResult {
        val parsedUrl = OpenAiCompatibleAnalysisProvider.parseBaseUrl(baseUrl)
            ?: return ConnectionTestResult.Failure("Invalid base URL. Expected http://127.0.0.1:11434/v1 or https://...")
        val provider = OpenAiCompatibleAnalysisProvider(
            client = httpClient,
            baseUrl = parsedUrl,
            model = model.ifBlank { "default" },
            apiKey = apiKey ?: secretStore.get(API_KEY_ALIAS),
        )
        return provider.testConnection()
    }

    override suspend fun clear() {
        secretStore.remove(API_KEY_ALIAS)
        secretStore.remove(CLOUD_DRIVE_TOKEN_ALIAS)
        secretStore.remove(CLOUD_DRIVE_REFRESH_TOKEN_ALIAS)
        secretStore.remove(CLOUD_DRIVE_CLIENT_ID_ALIAS)
        secretStore.remove(CLOUD_DRIVE_CLIENT_SECRET_ALIAS)
        dataStore.edit { it.clear() }
    }

    companion object {
        private const val API_KEY_ALIAS = "analysis_api_key"
        private const val CLOUD_DRIVE_TOKEN_ALIAS = "cloud_drive_token"
        private const val CLOUD_DRIVE_REFRESH_TOKEN_ALIAS = "cloud_drive_refresh_token"
        private const val CLOUD_DRIVE_CLIENT_ID_ALIAS = "cloud_drive_client_id"
        private const val CLOUD_DRIVE_CLIENT_SECRET_ALIAS = "cloud_drive_client_secret"
    }
}
