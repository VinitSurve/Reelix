package app.reelix.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.reelix.core.url.SafeUrlOpener
import app.reelix.data.repository.SavedItemRepository
import app.reelix.domain.model.ItemListEntry
import app.reelix.domain.model.ItemQuery
import app.reelix.domain.model.LibraryCounts
import app.reelix.domain.model.ShareSaveResult
import app.reelix.domain.model.StatusFilter
import app.reelix.domain.usecase.ShareIntakeUseCase
import app.reelix.processing.ProcessingScheduler
import app.reelix.processing.WorkerQueueState
import app.reelix.ui.components.SaveLinkDialog
import app.reelix.ui.components.SavedItemCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    repository: SavedItemRepository,
    private val shareIntake: ShareIntakeUseCase,
    private val scheduler: ProcessingScheduler,
) : ViewModel() {

    val counts: StateFlow<LibraryCounts> = repository.observeCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryCounts())

    val recentItems: StateFlow<List<ItemListEntry>> = repository.observeItems(ItemQuery(), limit = 8)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val workerState: StateFlow<WorkerQueueState> = scheduler.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkerQueueState())

    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    fun saveManualText(rawText: String, notes: String? = null, category: String? = null) {
        viewModelScope.launch {
            val msg = when (val result = shareIntake.handleManualText(rawText, notes = notes, category = category)) {
                ShareSaveResult.NothingShared -> "Paste an Instagram URL to save."
                ShareSaveResult.NoInstagramLink -> "No valid Instagram Reel or post URL found."
                is ShareSaveResult.Error -> result.message
                is ShareSaveResult.MediaHandled -> "Media file imported."
                is ShareSaveResult.Handled -> when {
                    result.saved.size == 1 && result.duplicates.isEmpty() ->
                        "${result.saved.first().link.contentType.label} saved to your queue."
                    result.saved.isNotEmpty() ->
                        "Saved ${result.saved.size} Instagram links."
                    else -> "Already saved in your vault."
                }
            }
            _feedback.value = msg
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            scheduler.scheduleImmediate(forceRunNow = true)
            _feedback.value = "Syncing vault with Google Drive..."
        }
    }

    fun consumeFeedback() {
        _feedback.value = null
    }

    companion object {
        fun factory(
            repository: SavedItemRepository,
            shareIntake: ShareIntakeUseCase,
            scheduler: ProcessingScheduler,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(repository, shareIntake, scheduler) as T
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToLibrary: (StatusFilter) -> Unit,
    onNavigateToSearch: (String?) -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenItemDetails: (String) -> Unit,
) {
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val recent by viewModel.recentItems.collectAsStateWithLifecycle()
    val workerState by viewModel.workerState.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(feedback) {
        val msg = feedback ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeFeedback()
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Reelix", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (workerState.isRunning) "Background enrichment active" else "Personal Instagram knowledge vault",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::syncNow,
                        modifier = Modifier.testTag("home_sync_button"),
                    ) {
                        Icon(Icons.Outlined.Sync, contentDescription = "Sync vault")
                    }
                    IconButton(onClick = { onNavigateToSearch(null) }) {
                        Icon(Icons.Outlined.Search, contentDescription = "Search vault")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .testTag("home_screen"),
        ) {
            item(key = "counts_grid") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        VaultMetricTile(
                            label = "Total saved",
                            value = counts.total,
                            onClick = { onNavigateToLibrary(StatusFilter.ALL) },
                            modifier = Modifier.weight(1f).testTag("metric_total"),
                        )
                        VaultMetricTile(
                            label = "Awaiting",
                            value = counts.awaiting,
                            onClick = { onNavigateToLibrary(StatusFilter.AWAITING) },
                            modifier = Modifier.weight(1f).testTag("metric_awaiting"),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        VaultMetricTile(
                            label = "Transcribed",
                            value = counts.transcribed,
                            onClick = { onNavigateToLibrary(StatusFilter.TRANSCRIBED) },
                            modifier = Modifier.weight(1f).testTag("metric_transcribed"),
                        )
                        VaultMetricTile(
                            label = "Needs attention",
                            value = counts.needsAttention,
                            highlightAlert = counts.needsAttention > 0,
                            onClick = { onNavigateToLibrary(StatusFilter.ATTENTION) },
                            modifier = Modifier.weight(1f).testTag("metric_attention"),
                        )
                    }
                }
            }

            item(key = "quick_actions") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = { showSaveDialog = true },
                        modifier = Modifier.weight(1f).testTag("save_link_button"),
                    ) {
                        Icon(Icons.Outlined.AddLink, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Save link")
                    }
                    OutlinedButton(
                        onClick = { onNavigateToSearch(null) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Search vault")
                    }
                }
            }

            if (recent.isEmpty()) {
                item(key = "empty_vault") {
                    EmptyVaultCard(onPasteLink = { showSaveDialog = true })
                }
            } else {
                item(key = "recent_header") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    ) {
                        Text("Recently saved", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onNavigateToLibrary(StatusFilter.ALL) }) {
                            Text("View all")
                        }
                    }
                }

                items(items = recent, key = { it.id }) { entry ->
                    SavedItemCard(
                        entry = entry,
                        onOpenDetails = { onOpenItemDetails(entry.id) },
                        onOpenInstagram = {
                            SafeUrlOpener.openInstagramUrl(context, entry.originalUrl)
                        },
                        onTagClick = { tag -> onNavigateToSearch(tag) },
                    )
                }
            }
        }
    }

    if (showSaveDialog) {
        SaveLinkDialog(
            onSave = { text, notes, category ->
                showSaveDialog = false
                viewModel.saveManualText(text, notes, category)
            },
            onDismiss = { showSaveDialog = false },
        )
    }
    }
}

@Composable
private fun VaultMetricTile(
    label: String,
    value: Int,
    highlightAlert: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val border = if (highlightAlert) scheme.error else scheme.outlineVariant
    val valueColor = if (highlightAlert) scheme.error else scheme.onSurface

    OutlinedCard(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, border),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = valueColor,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyVaultCard(onPasteLink: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Icon(
                imageVector = Icons.Outlined.Bookmarks,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text("Your vault is ready", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "1. In Instagram, tap Share on any informative Reel or post.\n" +
                    "2. Choose \"Save to Reelix\" in Android's share sheet.\n" +
                    "3. Keep scrolling — Reelix saves the link immediately and enriches it in the background.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onPasteLink) {
                Text("Or paste an Instagram link")
            }
        }
    }
}

