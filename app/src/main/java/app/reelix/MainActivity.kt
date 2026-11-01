package app.reelix

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.reelix.data.settings.AppSettings
import app.reelix.ui.ReelixApp
import app.reelix.ui.theme.ReelixTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val openItemId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            extractDeepLinkItem(intent)
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

