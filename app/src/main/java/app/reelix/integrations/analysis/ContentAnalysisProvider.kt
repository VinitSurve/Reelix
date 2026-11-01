package app.reelix.integrations.analysis

import app.reelix.domain.model.ContentType

/** Structured AI analysis output. */
data class AnalysisResult(
    val summary: String,
    val detailedSummary: String?,
    val mainTopic: String?,
    val keywords: List<String>,
    val tags: List<String>,
    val category: String?,
    val keyTakeaways: List<String>,
    val importantConcepts: List<String>,
    val actionableSteps: List<String>,
    val modelName: String?,
)

sealed interface AnalysisOutcome {
    data class Success(val result: AnalysisResult) : AnalysisOutcome
    /** The provider worked but the text was insufficient to produce a result. Nothing is fabricated. */
    data class NoResult(val reason: String) : AnalysisOutcome
    /** Retry later (network, rate limit, server error). */
    data class TransientFailure(val error: String) : AnalysisOutcome
    /** Configuration or response problem that will not fix itself. */
    data class PermanentFailure(val error: String) : AnalysisOutcome
}

sealed interface ConnectionTestResult {
    data class Success(val message: String, val modelsAvailable: List<String>) : ConnectionTestResult
    data class Failure(val error: String) : ConnectionTestResult
}

interface ContentAnalysisProvider {
    val id: String
    val displayName: String
    suspend fun analyze(input: AnalysisInput): AnalysisOutcome
    suspend fun testConnection(): ConnectionTestResult
}

data class AnalysisInput(
    val contentType: ContentType,
    val title: String?,
    val caption: String?,
    val transcript: String?,
    val notes: String?,
) {
    /** The text the model is allowed to use. Nothing else is sent. */
    fun sourceText(): String = buildString {
        title?.takeIf { it.isNotBlank() }?.let { append("Title: ").append(it).append("\n") }
        caption?.takeIf { it.isNotBlank() }?.let { append("Caption: ").append(it).append("\n") }
        transcript?.takeIf { it.isNotBlank() }?.let { append("Transcript: ").append(it).append("\n") }
        notes?.takeIf { it.isNotBlank() }?.let { append("User notes: ").append(it).append("\n") }
    }.trim()

    val hasEnoughText: Boolean get() = sourceText().length >= MIN_SOURCE_CHARS

    companion object {
        const val MIN_SOURCE_CHARS = 30
    }
}
