package app.reelix.processing

import app.reelix.domain.model.ProcessingStage

/** Outcome of running one pipeline stage for one item. */
sealed interface StageOutcome {
    val message: String?

    data class Succeeded(override val message: String? = null) : StageOutcome
    data class Skipped(override val message: String) : StageOutcome
    /** The information this stage produces cannot be obtained (e.g. no media). Not an error. */
    data class Unavailable(override val message: String) : StageOutcome
    /** A provider for this stage has not been configured by the user. */
    data class NotConfigured(override val message: String) : StageOutcome
    /** Temporary problem; retried with exponential backoff. */
    data class TransientFailure(override val message: String) : StageOutcome
    /** Problem that will not resolve by itself; the item is flagged for review. */
    data class PermanentFailure(override val message: String) : StageOutcome
}

/** One independently retryable unit of work. Implementations must be idempotent. */
interface StageHandler {
    val stage: ProcessingStage
    suspend fun run(itemId: String): StageOutcome
}

/** Exponential backoff without jitter so behaviour is predictable and testable. */
class BackoffPolicy(
    val baseDelayMs: Long = 2 * 60_000L,
    val maxDelayMs: Long = 6 * 60 * 60_000L,
    val maxAttempts: Int = 6,
) {
    /** Delay before retry number [attempt] (1-based). */
    fun delayFor(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 30)
        val delay = baseDelayMs.toDouble() * (1L shl exponent)
        return minOf(delay, maxDelayMs.toDouble()).toLong()
    }
}

data class BatchResult(
    val processed: Int,
    val completed: Int,
    val retriesScheduled: Int,
    val needsAttention: Int,
    val remainingDue: Int,
    val nextRetryAt: Long?,
    val recoveredInterrupted: Int,
) {
    fun summary(): String = buildString {
        append("Processed $processed")
        if (completed > 0) append(", $completed done")
        if (retriesScheduled > 0) append(", $retriesScheduled retrying")
        if (needsAttention > 0) append(", $needsAttention need attention")
        if (recoveredInterrupted > 0) append(", recovered $recoveredInterrupted")
    }
}

