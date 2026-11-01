package app.reelix.processing

import app.reelix.core.ReelixLog
import app.reelix.core.safeDescription
import app.reelix.domain.model.ProcessingStage
import app.reelix.domain.model.ProcessingStatus
import app.reelix.domain.model.StageStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs the ordered stages for queued items.
 *
 * - Stages already settled are skipped, so re-running an item is idempotent and never repeats
 *   expensive work that has already succeeded.
 * - A transient failure stops the item at that stage and schedules a retry with exponential
 *   backoff; earlier results are kept.
 * - After [BackoffPolicy.maxAttempts] or on a permanent failure the item is flagged NEEDS_ATTENTION.
 * - Items left in PROCESSING by a killed process are re-queued at the start of every batch.
 * - A process-wide mutex guarantees only one batch runs at a time, whichever worker triggered it.
 */
class ProcessingPipeline(
    private val repository: ProcessingRepository,
    handlers: List<StageHandler>,
    private val backoff: BackoffPolicy = BackoffPolicy(),
) {
    private val handlers: Map<ProcessingStage, StageHandler> = handlers.associateBy { it.stage }
    private val mutex = Mutex()

    suspend fun runBatch(limit: Int): BatchResult = mutex.withLock {
        val recovered = repository.resetInterrupted()
        val ids = repository.dueItemIds(limit.coerceAtLeast(1))
        var completed = 0
        var retrying = 0
        var attention = 0
        for (id in ids) {
            currentCoroutineContext().ensureActive()
            when (processItemLocked(id)) {
                ProcessingStatus.COMPLETED -> completed++
                ProcessingStatus.WAITING_RETRY -> retrying++
                ProcessingStatus.NEEDS_ATTENTION -> attention++
                else -> Unit
            }
        }
        BatchResult(
            processed = ids.size,
            completed = completed,
            retriesScheduled = retrying,
            needsAttention = attention,
            remainingDue = repository.countDue(),
            nextRetryAt = repository.earliestFutureRetry(),
            recoveredInterrupted = recovered,
        )
    }

    /** Processes a single item if it is due. Returns its resulting status, or null if not processed. */
    suspend fun processItem(id: String): ProcessingStatus? = mutex.withLock { processItemLocked(id) }

    private suspend fun processItemLocked(id: String): ProcessingStatus? {
        if (!repository.claim(id)) return null
        val jobs = repository.ensureJobs(id)

        for (stage in ProcessingStage.entries) {
            val job = jobs[stage] ?: continue
            if (job.status.isSettled) continue
            currentCoroutineContext().ensureActive()
            if (!repository.exists(id)) return null // Deleted while processing.

            repository.markStageRunning(id, job)
            val handler = handlers[stage]
            val outcome = if (handler == null) {
                StageOutcome.Skipped("No handler registered for this stage")
            } else {
                try {
                    handler.run(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ReelixLog.w("Stage ${stage.name} crashed", e)
                    StageOutcome.TransientFailure(e.safeDescription())
                }
            }
            if (!repository.exists(id)) return null

            when (outcome) {
                is StageOutcome.Succeeded -> repository.settleStage(id, job, StageStatus.SUCCEEDED, outcome.message)
                is StageOutcome.Skipped -> repository.settleStage(id, job, StageStatus.SKIPPED, outcome.message)
                is StageOutcome.Unavailable -> repository.settleStage(id, job, StageStatus.UNAVAILABLE, outcome.message)
                is StageOutcome.NotConfigured -> repository.settleStage(id, job, StageStatus.NOT_CONFIGURED, outcome.message)
                is StageOutcome.TransientFailure -> {
                    val attempts = job.attempts + 1
                    return if (attempts >= backoff.maxAttempts) {
                        repository.failStage(id, job, attempts, "${outcome.message} (gave up after $attempts attempts)")
                        ProcessingStatus.NEEDS_ATTENTION
                    } else {
                        val next = repository.now() + backoff.delayFor(attempts)
                        repository.scheduleRetry(id, job, attempts, next, outcome.message)
                        ProcessingStatus.WAITING_RETRY
                    }
                }
                is StageOutcome.PermanentFailure -> {
                    repository.failStage(id, job, job.attempts + 1, outcome.message)
                    return ProcessingStatus.NEEDS_ATTENTION
                }
            }
        }
        repository.markCompleted(id)
        return ProcessingStatus.COMPLETED
    }
}

