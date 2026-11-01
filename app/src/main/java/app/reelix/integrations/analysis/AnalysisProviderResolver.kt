package app.reelix.integrations.analysis

import app.reelix.data.settings.SettingsRepository
import okhttp3.OkHttpClient

/** Resolves the currently configured analysis provider, or null if AI enrichment is not set up. */
fun interface AnalysisProviderResolver {
    suspend fun current(): ContentAnalysisProvider?
}

class SettingsAnalysisProviderResolver(
    private val settings: SettingsRepository,
    private val client: OkHttpClient,
) : AnalysisProviderResolver {
    override suspend fun current(): ContentAnalysisProvider? {
        val config = settings.current().analysis
        if (!config.isComplete) return null
        val url = OpenAiCompatibleAnalysisProvider.parseBaseUrl(config.baseUrl) ?: return null
        return OpenAiCompatibleAnalysisProvider(client, url, config.model, settings.apiKey())
    }
}

