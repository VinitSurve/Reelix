package app.reelix

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import app.reelix.core.ReelixLog
import app.reelix.data.settings.AppSettings
import app.reelix.ui.ReelixApp
import app.reelix.ui.theme.ReelixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val openItemId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            extractDeepLinkItem(intent)
            handleTestActions(intent)
        }

        val container = (application as ReelixApplication).container
        setContent {
            val settings by container.settingsRepository.settings.collectAsState(initial = AppSettings())
            val targetItem by openItemId.collectAsState()

            ReelixTheme(themeMode = settings.themeMode) {
                ReelixApp(
                    container = container,
                    openItemId = targetItem,
                    onOpenItemConsumed = { openItemId.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractDeepLinkItem(intent)
        handleTestActions(intent)
    }

    private fun handleTestActions(incoming: Intent?) {
        val action = incoming?.getStringExtra("REELIX_ACTION") ?: return
        val targetId = incoming.getStringExtra("TARGET_ITEM_ID") ?: "fef6fb3c-b63a-4c59-ba6c-f8038400d7e3"
        val container = (application as ReelixApplication).container

        when (action) {
            "RECOVER_TEST_ITEM" -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    ReelixLog.d("=== START CONTROLLED RECOVERY FOR $targetId ===")
                    try {
                        // 1. Fetch current manifest from Drive (Pre-recovery check)
                        val manifest = container.cloudSyncClient.fetchJobManifest(targetId)
                        if (manifest == null) {
                            ReelixLog.w("RECOVERY_FAIL: Manifest $targetId not found on Drive")
                            return@launch
                        }
                        ReelixLog.d("PRE_RECOVERY_MANIFEST_RAW: " + manifest.toJson().replace("\n", " "))
                        ReelixLog.d("PRE_RECOVERY_STATUS: " + manifest.status)
                        ReelixLog.d("PRE_RECOVERY_URL: " + manifest.originalUrl)
                        ReelixLog.d("PRE_RECOVERY_CAPTION: " + manifest.caption)
                        ReelixLog.d("PRE_RECOVERY_USER_NOTES: " + manifest.userNotes)
                        ReelixLog.d("PRE_RECOVERY_CATEGORY: " + manifest.category)
                        ReelixLog.d("PRE_RECOVERY_TAGS: " + manifest.userTags.joinToString(","))
                        ReelixLog.d("PRE_RECOVERY_STAGES: acq=" + manifest.stages.acquisition +
                            " audio=" + manifest.stages.audioExtraction +
                            " trans=" + manifest.stages.transcription +
                            " gemini=" + manifest.stages.geminiAnalysis)
                        ReelixLog.d("PRE_RECOVERY_RETRY_COUNT: " + manifest.retryCount)
                        ReelixLog.d("PRE_RECOVERY_ERROR: " + manifest.errorMessage)

                        // 2. Prepare recovered manifest
                        val recovered = manifest.copy(
                            status = "WAITING_RETRY",
                            retryCount = 0,
                            nextRetryAt = 0L,
                            errorMessage = null,
                            updatedAt = container.clock.now(),
                        )

                        // 3. Persist update to Drive using safe update
                        val submitted = container.cloudSyncClient.submitJobManifest(recovered)
                        ReelixLog.d("RECOVERY_SUBMIT_RESULT: $submitted")

                        // 4. Verify persisted manifest on Drive
                        val verified = container.cloudSyncClient.fetchJobManifest(targetId)
                        if (verified != null) {
                            ReelixLog.d("VERIFIED_MANIFEST_STATUS: " + verified.status)
                            ReelixLog.d("VERIFIED_MANIFEST_RETRY_COUNT: " + verified.retryCount)
                            ReelixLog.d("VERIFIED_MANIFEST_NEXT_RETRY_AT: " + verified.nextRetryAt)
                            ReelixLog.d("VERIFIED_MANIFEST_ERROR: " + verified.errorMessage)
                            ReelixLog.d("VERIFIED_MANIFEST_RAW: " + verified.toJson().replace("\n", " "))
                        }

                        // 5. Synchronize Android from authoritative Drive state
                        val syncedCount = container.cloudSyncRepository.syncCompletedJobs()
                        ReelixLog.d("SYNC_COMPLETED_COUNT: $syncedCount")

                        ReelixLog.d("=== COMPLETED CONTROLLED RECOVERY FOR $targetId ===")
                    } catch (e: Exception) {
                        ReelixLog.w("RECOVERY_EXCEPTION", e)
                    }
                }
            }
            "SYNC_ONLY" -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    ReelixLog.d("=== START SYNC_ONLY ===")
                    try {
                        val syncedCount = container.cloudSyncRepository.syncCompletedJobs()
                        ReelixLog.d("SYNC_ONLY_COUNT: $syncedCount")
                        val manifest = container.cloudSyncClient.fetchJobManifest(targetId)
                        if (manifest != null) {
                            ReelixLog.d("SYNC_DRIVE_MANIFEST_STATUS: " + manifest.status)
                            ReelixLog.d("SYNC_DRIVE_MANIFEST_RAW: " + manifest.toJson().replace("\n", " "))
                        }
                    } catch (e: Exception) {
                        ReelixLog.w("SYNC_ONLY_EXCEPTION", e)
                    }
                    ReelixLog.d("=== END SYNC_ONLY ===")
                }
            }
        }
    }

    private fun extractDeepLinkItem(incoming: Intent?) {
        val id = incoming?.getStringExtra(EXTRA_OPEN_ITEM_ID)?.takeIf { it.isNotBlank() }
        if (id != null) {
            openItemId.value = id
        }
    }

    companion object {
        const val EXTRA_OPEN_ITEM_ID = "app.reelix.extra.OPEN_ITEM_ID"
    }
}

