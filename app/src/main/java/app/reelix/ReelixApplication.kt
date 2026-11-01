package app.reelix

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.work.Configuration
import app.reelix.core.ReelixLog
import app.reelix.di.AppContainer
import app.reelix.di.DefaultAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReelixApplication : Application(), Configuration.Provider {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var container: AppContainer

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (!::container.isInitialized) {
            container = DefaultAppContainer(this)
        }
        val initialContainer = container
        // Defer background queue recovery until after the initial main-thread startup frame.
        Handler(Looper.getMainLooper()).post {
            if (container !== initialContainer) return@post
            applicationScope.launch {
                if (container !== initialContainer) return@launch
                runCatching {
                    initialContainer.processingRepository.resetInterrupted()
                    initialContainer.processingScheduler.syncPeriodicSchedule()
                    if (initialContainer.savedItemRepository.countDue() > 0) {
                        initialContainer.processingScheduler.scheduleImmediate()
                    }
                }.onFailure { ReelixLog.w("Startup queue recovery skipped", it) }
            }
        }
    }
}
