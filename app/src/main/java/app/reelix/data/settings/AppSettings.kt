package app.reelix.data.settings

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** Configuration for an optional, user-supplied OpenAI-compatible chat-completions endpoint (e.g. Ollama). */
data class AnalysisProviderSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val model: String = "",
    val hasApiKey: Boolean = false,
) {
    val isComplete: Boolean get() = enabled && baseUrl.isNotBlank() && model.isNotBlank()
}

data class AppSettings(
    val autoProcess: Boolean = true,
    val wifiOnly: Boolean = false,
    val requiresCharging: Boolean = false,
    val jobsPerRun: Int = DEFAULT_JOBS_PER_RUN,
    val maxMediaImportSizeMb: Int = DEFAULT_MAX_MEDIA_SIZE_MB,
    val fetchLinkPreviews: Boolean = true,
    val downloadThumbnails: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val analysis: AnalysisProviderSettings = AnalysisProviderSettings(),
    val cloudProcessingEnabled: Boolean = false,
    val cloudDriveFolderId: String = "",
    val cloudDriveToken: String = "",
    val lastRunAt: Long? = null,
    val lastRunSummary: String? = null,
) {
    val processingNeedsNetwork: Boolean get() = fetchLinkPreviews || analysis.isComplete || cloudProcessingEnabled
    val maxMediaImportSizeBytes: Long get() = maxMediaImportSizeMb.toLong() * 1024 * 1024

    companion object {
        const val DEFAULT_JOBS_PER_RUN = 10
        val JOBS_PER_RUN_RANGE = 1..50
        const val DEFAULT_MAX_MEDIA_SIZE_MB = 200
        val MEDIA_SIZE_OPTIONS_MB = listOf(50, 100, 200, 500)
        val MEDIA_IMPORT_SIZE_RANGE_MB = 25..1000
    }
}
