package app.reelix.cloud

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.reelix.ReelixApplication
import app.reelix.core.ReelixLog

class CloudSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? ReelixApplication ?: return Result.failure()
        val container = app.container

        val settings = container.settingsRepository.current()
        if (!settings.cloudProcessingEnabled) {
            ReelixLog.d("Cloud processing is disabled in Settings; CloudSyncWorker skipping")
            return Result.success()
        }

        return try {
            val cloudSyncRepo = container.cloudSyncRepository

            // 1. Check for and integrate any completed jobs from the cloud
            val synced = cloudSyncRepo.syncCompletedJobs()
            if (synced > 0) {
                ReelixLog.d("CloudSyncWorker: Integrated $synced completed cloud job(s)")
            }

            // 2. Upload any queued items waiting for cloud offload
            val dueIds = container.processingRepository.dueItemIds(limit = 5)
            for (id in dueIds) {
                cloudSyncRepo.uploadPendingItem(id)
            }

            Result.success()
        } catch (e: Exception) {
            ReelixLog.w("CloudSyncWorker encountered an error", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_TAG = "reelix_cloud_sync"
        const val UNIQUE_SYNC_WORK = "reelix_cloud_sync_periodic"
    }
}
