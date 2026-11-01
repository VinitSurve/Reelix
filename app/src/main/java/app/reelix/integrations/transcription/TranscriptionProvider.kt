package app.reelix.integrations.transcription

import java.io.File

/** Converts audio (16kHz mono WAV) into text. */
interface TranscriptionProvider {
    val id: String
    val displayName: String
    val isAvailable: Boolean
    suspend fun transcribe(audioFile: File, onProgress: ((Float) -> Unit)? = null): TranscriptionOutcome
}

sealed interface TranscriptionOutcome {
    data class Success(val transcript: String, val modelName: String?) : TranscriptionOutcome
    data class ModelNotConfigured(val message: String) : TranscriptionOutcome
    data class TransientFailure(val error: String) : TranscriptionOutcome
    data class PermanentFailure(val error: String) : TranscriptionOutcome
}

/** Default fallback when no transcription engine or model is available. */
class UnavailableTranscriptionProvider : TranscriptionProvider {
    override val id: String = "none"
    override val displayName: String = "No transcription engine"
    override val isAvailable: Boolean = false
    override suspend fun transcribe(audioFile: File, onProgress: ((Float) -> Unit)?): TranscriptionOutcome =
        TranscriptionOutcome.ModelNotConfigured("No transcription engine or model is configured")
}
