package app.reelix.processing

import android.content.Context
import androidx.work.BackoffPolicy as WorkBackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.reelix.data.settings.AppSettings
import app.reelix.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

data class WorkerQueueState(
    val isRunning: Boolean = false,
    val isEnqueued: Boolean = false,
)

/** Schedules persistent background queue execution without blocking UI or share intents. */
interface ProcessingScheduler {
    val state: Flow<WorkerQueueState>
    suspend fun scheduleImmediate(forceRunNow: Boolean = false)
    suspend fun scheduleDelayed(delayMs: Long)
    suspend fun syncPeriodicSchedule()
    suspend fun cancelAll()
}

class WorkManagerProcessingScheduler(
    context: Context,
    private val settings: SettingsRepository,
) : ProcessingScheduler {

    private val appContext = context.applicationContext
    private val workManager: WorkManager by lazy { WorkManager.getInstance(appContext) }

    override val state: Flow<WorkerQueueState> = flow {
        emitAll(
            workManager.getWorkInfosByTagFlow(WORK_TAG).map { infos ->
                WorkerQueueState(
                    isRunning = infos.any { it.state == WorkInfo.State.RUNNING },
                    isEnqueued = infos.any { it.state == WorkInfo.State.ENQUEUED },
                )
            },
        )
    }

    override suspend fun scheduleImmediate(forceRunNow: Boolean) {
        val prefs = settings.current()
        if (!prefs.autoProcess && !forceRunNow) return

        val constraints = buildConstraints(prefs, manualTrigger = forceRunNow)
        val request = OneTimeWorkRequestBuilder<ProcessingWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(WorkBackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
            .addTag(WORK_TAG)
            .build()

        val policy = if (forceRunNow) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workManager.enqueueUniqueWork(UNIQUE_IMMEDIATE_WORK, policy, request)
    }

    override suspend fun scheduleDelayed(delayMs: Long) {
        val prefs = settings.current()
        if (!prefs.autoProcess) return

        val safeDelay = delayMs.coerceAtLeast(MIN_DELAY_MS)
        val request = OneTimeWorkRequestBuilder<ProcessingWorker>()
            .setInitialDelay(safeDelay, TimeUnit.MILLISECONDS)
            .setConstraints(buildConstraints(prefs, manualTrigger = false))
            .setBackoffCriteria(WorkBackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
            .addTag(WORK_TAG)
            .build()

        workManager.enqueueUniqueWork(UNIQUE_RETRY_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    override suspend fun syncPeriodicSchedule() {
        val prefs = settings.current()
        if (!prefs.autoProcess) {
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
            return
        }
        val periodic = PeriodicWorkRequestBuilder<ProcessingWorker>(6, TimeUnit.HOURS)
            .setConstraints(buildConstraints(prefs, manualTrigger = false))
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
    }

    override suspend fun cancelAll() {
        workManager.cancelAllWorkByTag(WORK_TAG)
    }

    private fun buildConstraints(prefs: AppSettings, manualTrigger: Boolean): Constraints {
        val networkType = when {
            !prefs.processingNeedsNetwork -> NetworkType.NOT_REQUIRED
            prefs.wifiOnly -> NetworkType.UNMETERED
            else -> NetworkType.CONNECTED
        }
        return Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(!manualTrigger && prefs.requiresCharging)
            .setRequiresBatteryNotLow(!manualTrigger)
            .build()
    }

    companion object {
        const val WORK_TAG = "reelix_processing"
        const val UNIQUE_IMMEDIATE_WORK = "reelix_process_immediate"
        const val UNIQUE_RETRY_WORK = "reelix_process_retry"
        const val UNIQUE_PERIODIC_WORK = "reelix_process_periodic"
        private const val MIN_DELAY_MS = 30_000L
    }
}

/** Deterministic scheduler used in unit tests. */
class RecordingProcessingScheduler : ProcessingScheduler {
    var immediateCalls: Int = 0
        private set
    var lastForceRunNow: Boolean = false
        private set
    var lastDelayedMs: Long? = null
        private set
    var periodicSyncCalls: Int = 0
        private set
    var cancelCalls: Int = 0
        private set

    override val state: Flow<WorkerQueueState> = flowOf(WorkerQueueState())

    override suspend fun scheduleImmediate(forceRunNow: Boolean) {
        immediateCalls++
        lastForceRunNow = forceRunNow
    }

    override suspend fun scheduleDelayed(delayMs: Long) {
        lastDelayedMs = delayMs
    }

    override suspend fun syncPeriodicSchedule() {
        periodicSyncCalls++
    }

    override suspend fun cancelAll() {
        cancelCalls++
    }
}

