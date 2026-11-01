package app.reelix.domain.model

/** The kind of Instagram or imported content a saved record points to. */
enum class ContentType(val label: String) {
    REEL("Reel"),
    POST("Post"),
    IGTV("IGTV video"),
    AUDIO("Audio"),
    UNKNOWN("Link / media"),
}

/** Overall lifecycle of a saved item in the processing queue. */
enum class ProcessingStatus(val label: String) {
    QUEUED("Queued"),
    PROCESSING("Processing"),
    WAITING_RETRY("Retry scheduled"),
    COMPLETED("Processed"),
    NEEDS_ATTENTION("Needs attention"),
    ;

    val isAwaiting: Boolean get() = this == QUEUED || this == PROCESSING || this == WAITING_RETRY

    companion object {
        val AWAITING: Set<ProcessingStatus> = setOf(QUEUED, PROCESSING, WAITING_RETRY)
    }
}

/** Status of a single pipeline stage. */
enum class StageStatus(val label: String) {
    PENDING("Pending"),
    RUNNING("Running"),
    SUCCEEDED("Done"),
    SKIPPED("Skipped"),
    UNAVAILABLE("Unavailable"),
    NOT_CONFIGURED("Not configured"),
    RETRY_SCHEDULED("Retry scheduled"),
    FAILED("Failed"),
    ;

    /** A stage in one of these states never needs to run again unless the user explicitly asks. */
    val isSettled: Boolean
        get() = this == SUCCEEDED || this == SKIPPED || this == UNAVAILABLE || this == NOT_CONFIGURED
}

/**
 * Ordered, independently retryable processing stages.
 */
enum class ProcessingStage(val label: String) {
    VALIDATION("URL validation"),
    ACQUISITION("Content acquisition"),
    METADATA("Metadata extraction"),
    MEDIA_VERIFICATION("Media verification"),
    AUDIO_EXTRACTION("Audio extraction"),
    TRANSCRIPTION("Transcription"),
    VISUAL_ANALYSIS("Visual analysis"),
    ANALYSIS("AI summary & concepts"),
    TAGGING("Tag generation"),
    INDEXING("Search indexing"),
    COMPLETION("Completion"),
}

/** Where a tag came from, so the UI can distinguish user, extracted and AI information. */
enum class TagSource { USER, EXTRACTED, AI }

/** How an item entered the vault. */
enum class SaveSource { SHARE, MANUAL, MEDIA_SHARE }

/** Filter options for library and search. */
enum class StatusFilter(val label: String) {
    ALL("All"),
    MEDIA_AVAILABLE("Media available"),
    TRANSCRIBED("Transcribed"),
    AI_ENRICHED("AI enriched"),
    AWAITING("Awaiting"),
    ATTENTION("Needs attention"),
}
