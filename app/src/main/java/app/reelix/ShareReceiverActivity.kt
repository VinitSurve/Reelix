package app.reelix

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.reelix.domain.model.InstagramLink
import app.reelix.domain.model.LinkSaveOutcome
import app.reelix.domain.model.SaveSource
import app.reelix.domain.model.ShareSaveResult
import app.reelix.ui.theme.ReelixTheme
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated Android Sharesheet entry point.
 *
 * Designed for speed and reliability:
 * - Persists incoming URLs inside [NonCancellable] before scheduling WorkManager.
 * - Renders a lightweight confirmation card over Instagram so the user can keep browsing.
 * - Offers explicit "Save another copy" only when a duplicate URL is shared.
 */
class ShareReceiverActivity : ComponentActivity() {

    private sealed interface ShareUiState {
        data object Saving : ShareUiState
        data class Result(val result: ShareSaveResult) : ShareUiState
        data class CopySaved(val itemId: String, val link: InstagramLink) : ShareUiState
    }

    private val uiState = MutableStateFlow<ShareUiState>(ShareUiState.Saving)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            processIncomingIntent(intent)
        }

        val container = (application as ReelixApplication).container
        setContent {
            val settings by container.settingsRepository.settings.collectAsState(
                initial = app.reelix.data.settings.AppSettings(),
            )
            val currentState by uiState.collectAsState()

            ReelixTheme(themeMode = settings.themeMode) {
                ShareOverlayContent(
                    state = currentState,
                    onOpenInReelix = { itemId ->
                        openMainApp(itemId)
                        finish()
                    },
                    onSaveAnotherCopy = { link ->
                        saveExplicitDuplicate(link)
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIncomingIntent(intent)
    }

    private fun processIncomingIntent(incoming: Intent?) {
        uiState.value = ShareUiState.Saving
        val intake = (application as ReelixApplication).container.shareIntakeUseCase
        lifecycleScope.launch {
            val result = withContext(NonCancellable) {
                intake.handleIntent(incoming)
            }
            uiState.value = ShareUiState.Result(result)
        }
    }

    private fun saveExplicitDuplicate(link: InstagramLink) {
        val intake = (application as ReelixApplication).container.shareIntakeUseCase
        lifecycleScope.launch {
            val outcome = withContext(NonCancellable) {
                intake.saveAnotherCopy(link, source = SaveSource.SHARE)
            }
            if (outcome is LinkSaveOutcome.Saved) {
                uiState.value = ShareUiState.CopySaved(outcome.itemId, outcome.link)
            }
        }
    }

    private fun openMainApp(itemId: String?) {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (itemId != null) {
                putExtra(MainActivity.EXTRA_OPEN_ITEM_ID, itemId)
            }
        }
        startActivity(mainIntent)
    }

    @Composable
    private fun ShareOverlayContent(
        state: ShareUiState,
        onOpenInReelix: (String?) -> Unit,
        onSaveAnotherCopy: (InstagramLink) -> Unit,
        onDismiss: () -> Unit,
    ) {
        // Auto-dismiss clean single/multi saves after a short confirmation so browsing isn't interrupted.
        LaunchedEffect(state) {
            val shouldAutoClose = when (state) {
                is ShareUiState.Result -> {
                    val r = state.result
                    (r is ShareSaveResult.Handled && r.saved.isNotEmpty() && r.duplicates.isEmpty()) ||
                        (r is ShareSaveResult.MediaHandled && r.saved.isNotEmpty() && r.failed.isEmpty())
                }
                is ShareUiState.CopySaved -> true
                else -> false
            }
            if (shouldAutoClose) {
                delay(AUTO_DISMISS_MS)
                onDismiss()
            }
        }

        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (state !is ShareUiState.Saving) onDismiss() },
                )
                .padding(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .testTag("share_confirmation_card"),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    when (state) {
                        ShareUiState.Saving -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("Saving to Reelix…", style = MaterialTheme.typography.titleMedium)
                            }
                        }

                        is ShareUiState.CopySaved -> {
                            HeaderRow(
                                icon = Icons.Outlined.CheckCircle,
                                tint = MaterialTheme.colorScheme.primary,
                                title = "Saved another copy to Reelix",
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Queued for background processing.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(14.dp))
                            Row(
                                horizontalArrangement = Arrangement.End,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                TextButton(onClick = onDismiss) { Text("Done") }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { onOpenInReelix(state.itemId) }) { Text("View in Reelix") }
                            }
                        }

                        is ShareUiState.Result -> ResultCardBody(
                            result = state.result,
                            onOpenInReelix = onOpenInReelix,
                            onSaveAnotherCopy = onSaveAnotherCopy,
                            onDismiss = onDismiss,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ResultCardBody(
        result: ShareSaveResult,
        onOpenInReelix: (String?) -> Unit,
        onSaveAnotherCopy: (InstagramLink) -> Unit,
        onDismiss: () -> Unit,
    ) {
        when (result) {
            ShareSaveResult.NothingShared -> {
                HeaderRow(
                    icon = Icons.Outlined.ErrorOutline,
                    tint = MaterialTheme.colorScheme.error,
                    title = "Nothing was shared",
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Android did not pass any text or link in this share request.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onDismiss) { Text("Close") }
                }
            }

            ShareSaveResult.NoInstagramLink -> {
                HeaderRow(
                    icon = Icons.Outlined.ErrorOutline,
                    tint = MaterialTheme.colorScheme.error,
                    title = "No Instagram link found",
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Reelix saves Instagram Reels, posts and IGTV links. Share a link from Instagram to add it to your vault.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onDismiss) { Text("Close") }
                }
            }

            is ShareSaveResult.Error -> {
                HeaderRow(
                    icon = Icons.Outlined.ErrorOutline,
                    tint = MaterialTheme.colorScheme.error,
                    title = "Could not save link",
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onDismiss) { Text("Close") }
                }
            }

            is ShareSaveResult.MediaHandled -> {
                val saved = result.saved
                val failed = result.failed

                if (saved.isNotEmpty()) {
                    val headline = if (saved.size == 1) {
                        "Media saved to Reelix"
                    } else {
                        "${saved.size} media files saved to Reelix"
                    }
                    HeaderRow(
                        icon = Icons.Outlined.CheckCircle,
                        tint = MaterialTheme.colorScheme.primary,
                        title = headline,
                    )
                    Spacer(Modifier.height(6.dp))
                    val subtitle = buildString {
                        append("Copied to private storage & queued for local audio extraction.")
                        if (failed.isNotEmpty()) {
                            append(" (${failed.size} failed)")
                        }
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onDismiss) { Text("Continue browsing") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onOpenInReelix(saved.first().itemId) }) {
                            Text("Open")
                        }
                    }
                } else if (failed.isNotEmpty()) {
                    HeaderRow(
                        icon = Icons.Outlined.ErrorOutline,
                        tint = MaterialTheme.colorScheme.error,
                        title = "Could not import media",
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = failed.first().reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = onDismiss) { Text("Close") }
                    }
                }
            }

            is ShareSaveResult.Handled -> {
                val saved = result.saved
                val duplicates = result.duplicates

                if (saved.isNotEmpty()) {
                    val headline = if (saved.size == 1) {
                        "${saved.first().link.contentType.label} saved to Reelix"
                    } else {
                        "${saved.size} Instagram links saved to Reelix"
                    }
                    HeaderRow(
                        icon = Icons.Outlined.CheckCircle,
                        tint = MaterialTheme.colorScheme.primary,
                        title = headline,
                    )
                    Spacer(Modifier.height(6.dp))
                    val subtitle = buildString {
                        append("Queued locally for background enrichment.")
                        if (duplicates.isNotEmpty()) {
                            append(" (${duplicates.size} already saved skipped.)")
                        }
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onDismiss) { Text("Continue browsing") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onOpenInReelix(saved.first().itemId) }) {
                            Text("Open")
                        }
                    }
                } else if (duplicates.isNotEmpty()) {
                    val firstDup = duplicates.first()
                    HeaderRow(
                        icon = Icons.Outlined.BookmarkAdded,
                        tint = MaterialTheme.colorScheme.primary,
                        title = "Already in your vault",
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "This ${firstDup.link.contentType.label.lowercase()} is already saved in Reelix.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = onDismiss) { Text("Close") }
                        if (duplicates.size == 1) {
                            OutlinedButton(onClick = { onSaveAnotherCopy(firstDup.link) }) {
                                Text("Save copy")
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Button(onClick = { onOpenInReelix(firstDup.itemId) }) {
                            Text("View item")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun HeaderRow(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        tint: Color,
        title: String,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        }
    }

    companion object {
        private const val AUTO_DISMISS_MS = 2_200L
    }
}

