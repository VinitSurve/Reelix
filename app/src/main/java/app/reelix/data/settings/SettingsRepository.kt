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
import app.reelix.integrations.analysis.ConnectionTestResult
import app.reelix.integrations.analysis.OpenAiCompatibleAnalysisProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import java.io.IOException

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings = settings.first()
    suspend fun update(transform: (AppSettings) -> AppSettings)
    suspend fun setApiKey(apiKey: String?)
    fun apiKey(): String?
    suspend fun recordRun(at: Long, summary: String)
    suspend fun testAiConnection(baseUrl: String, model: String, apiKey: String?): ConnectionTestResult
    suspend fun clear()
}

private val Context.reelixDataStore: DataStore<Preferences> by preferencesDataStore(name = "reelix_settings")

fun Context.settingsDataStore(): DataStore<Preferences> = applicationContext.reelixDataStore

/** DataStore-backed settings. The API key is held by [SecretStore], never in plain preferences. */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val secretStore: SecretStore,
    private val httpClient: OkHttpClient = OkHttpClient(),
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
        val lastRunAt = longPreferencesKey("last_run_at")
        val lastRunSummary = stringPreferencesKey("last_run_summary")
    }

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    private fun Preferences.toSettings(): AppSettings {
        val p = this
        val defaults = AppSettings()
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
            lastRunAt = p[Keys.lastRunAt],
            lastRunSummary = p[Keys.lastRunSummary],
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
        }
    }

    override suspend fun setApiKey(apiKey: String?) {
        if (apiKey.isNullOrBlank()) secretStore.remove(API_KEY_ALIAS) else secretStore.put(API_KEY_ALIAS, apiKey.trim())
        dataStore.edit { p -> p[Keys.analysisEnabled] = p[Keys.analysisEnabled] ?: false }
    }

    override fun apiKey(): String? = secretStore.get(API_KEY_ALIAS)

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
        dataStore.edit { it.clear() }
    }

    companion object {
        const val API_KEY_ALIAS = "analysis_api_key"
    }
}
