package app.reelix.processing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import app.reelix.ReelixApplication
import app.reelix.cloud.CloudSyncRepository
import app.reelix.core.Clock
import app.reelix.core.ReelixLog
import app.reelix.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException

/**
 * Persistent background worker that drains up to `settings.jobsPerRun` queued items per run.
 *
 * Uses both a DI constructor (for deterministic testing via [ReelixWorkerFactory]) and the standard
 * two-argument WorkManager constructor (resolving [ReelixApplication.container] in production).
 *
 * When `settings.cloudProcessingEnabled` is true, offloads audio extraction, Whisper transcription,
 * and Gemini enrichment to GitHub Actions via persistent Google Drive storage.
 */
class ProcessingWorker(
    appContext: Context,
    params: WorkerParameters,
    private val pipeline: ProcessingPipeline,
    private val settings: SettingsRepository,
    private val scheduler: ProcessingScheduler,
    private val clock: Clock,
    private val cloudSyncRepository: CloudSyncRepository? = null,
    private val processingRepository: ProcessingRepository? = null,
) : CoroutineWorker(appContext, params) {

    @Suppress("unused")
    constructor(appContext: Context, params: WorkerParameters) : this(
        appContext = appContext,
        params = params,
        pipeline = (appContext.applicationContext as ReelixApplication).container.pipeline,
        settings = (appContext.applicationContext as ReelixApplication).container.settingsRepository,
        scheduler = (appContext.applicationContext as ReelixApplication).container.processingScheduler,
        clock = (appContext.applicationContext as ReelixApplication).container.clock,
        cloudSyncRepository = (appContext.applicationContext as ReelixApplication).container.cloudSyncRepository,
        processingRepository = (appContext.applicationContext as ReelixApplication).container.processingRepository,
    )

    override suspend fun doWork(): Result {
        return try {
            val prefs = settings.current()
            if (prefs.cloudProcessingEnabled && cloudSyncRepository != null) {
                // 1. Synchronize completed jobs from Google Drive to local Room database and search index
                val synced = cloudSyncRepository.syncCompletedJobs()
                val now = clock.now()
                if (synced > 0) {
                    settings.recordRun(now, "Synced $synced completed cloud job(s)")
                }

                // 2. Offload pending due items to Google Drive
                val dueIds = processingRepository?.dueItemIds(prefs.jobsPerRun) ?: emptyList()
                var uploaded = 0
                for (id in dueIds) {
                    if (cloudSyncRepository.uploadPendingItem(id)) {
                        uploaded++
                    }
                }
                if (uploaded > 0) {
                    settings.recordRun(clock.now(), "Offloaded $uploaded item(s) to cloud queue")
                }

                if (dueIds.isNotEmpty()) {
                    scheduler.scheduleDelayed(60_000L)
                }
                Result.success()
            } else {
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
            }
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
    private val cloudSyncRepository: CloudSyncRepository? = null,
    private val processingRepository: ProcessingRepository? = null,
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
            cloudSyncRepository = cloudSyncRepository,
            processingRepository = processingRepository,
        )
        else -> null
    }
}
