package app.reelix.processing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import app.reelix.ReelixApplication
import app.reelix.core.Clock
import app.reelix.core.ReelixLog
import app.reelix.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException

/**
 * Persistent background worker that drains up to `settings.jobsPerRun` queued items per run.
 *
 * Uses both a DI constructor (for deterministic testing via [ReelixWorkerFactory]) and the standard
 * two-argument WorkManager constructor (resolving [ReelixApplication.container] in production).
 */
class ProcessingWorker(
    appContext: Context,
    params: WorkerParameters,
    private val pipeline: ProcessingPipeline,
    private val settings: SettingsRepository,
    private val scheduler: ProcessingScheduler,
    private val clock: Clock,
) : CoroutineWorker(appContext, params) {

    @Suppress("unused")
    constructor(appContext: Context, params: WorkerParameters) : this(
        appContext = appContext,
        params = params,
        pipeline = (appContext.applicationContext as ReelixApplication).container.pipeline,
        settings = (appContext.applicationContext as ReelixApplication).container.settingsRepository,
        scheduler = (appContext.applicationContext as ReelixApplication).container.processingScheduler,
        clock = (appContext.applicationContext as ReelixApplication).container.clock,
    )

    override suspend fun doWork(): Result {
        return try {
            val prefs = settings.current()
            val batch = pipeline.runBatch(prefs.jobsPerRun)
            val now = clock.now()
            if (batch.processed > 0 || batch.recoveredInterrupted > 0) {
                settings.recordRun(now, batch.summary())
            }
            if (batch.remainingDue > 0) {
                // Continue draining remaining items in a subsequent pass rather than monopolising one run.
                scheduler.scheduleDelayed(60_000L)
            } else if (batch.nextRetryAt != null) {
                scheduler.scheduleDelayed((batch.nextRetryAt - now).coerceAtLeast(30_000L))
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReelixLog.w("Background batch failed", e)
            Result.retry()
        }
    }
}

class ReelixWorkerFactory(
    private val pipeline: ProcessingPipeline,
    private val settings: SettingsRepository,
    private val scheduler: ProcessingScheduler,
    private val clock: Clock = Clock.SYSTEM,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        ProcessingWorker::class.java.name -> ProcessingWorker(
            appContext = appContext,
            params = workerParameters,
            pipeline = pipeline,
            settings = settings,
            scheduler = scheduler,
            clock = clock,
        )
        else -> null
    }
}

