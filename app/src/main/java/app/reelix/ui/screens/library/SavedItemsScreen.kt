package app.reelix.ui.screens.library

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.reelix.domain.model.SortOrder
import app.reelix.domain.model.StatusFilter
import app.reelix.domain.model.TagSource
import app.reelix.domain.model.UserMetadata
import app.reelix.processing.ProcessingScheduler
import app.reelix.ui.components.ConfirmDeleteDialog
import app.reelix.ui.components.EditUserMetadataDialog
import app.reelix.ui.components.SavedItemCard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuickEditTarget(
    val itemId: String,
    val initial: UserMetadata,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SavedItemsViewModel(
    private val repository: SavedItemRepository,
    private val scheduler: ProcessingScheduler,
) : ViewModel() {

    private val _query = MutableStateFlow(ItemQuery())
    val query: StateFlow<ItemQuery> = _query.asStateFlow()

    val items: StateFlow<List<ItemListEntry>> = _query
        .flatMapLatest { q -> repository.observeItems(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _editTarget = MutableStateFlow<QuickEditTarget?>(null)
    val editTarget: StateFlow<QuickEditTarget?> = _editTarget.asStateFlow()

    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    fun setStatusFilter(filter: StatusFilter) {
        _query.update { it.copy(statusFilter = filter) }
    }

    fun toggleSortOrder() {
        _query.update {
            val next = if (it.sortOrder == SortOrder.NEWEST) SortOrder.OLDEST else SortOrder.NEWEST
            it.copy(sortOrder = next)
        }
    }

    fun openQuickEdit(itemId: String) {
        viewModelScope.launch {
            val details = repository.observeDetails(itemId).first() ?: return@launch
            _editTarget.value = QuickEditTarget(
                itemId = itemId,
                initial = UserMetadata(
                    title = details.userTitle,
                    caption = details.userCaption,
                    transcript = details.userTranscript,
                    notes = details.userNotes,
                    category = details.userCategory,
                    tags = details.tags.filter { it.source == TagSource.USER }.map { it.name },
                ),
            )
        }
    }

    fun dismissQuickEdit() {
        _editTarget.value = null
    }

    fun saveQuickEdit(itemId: String, metadata: UserMetadata) {
        viewModelScope.launch {
            _editTarget.value = null
            if (repository.updateUserMetadata(itemId, metadata)) {
                _feedback.value = "Metadata updated."
            }
        }
    }

    fun retryItem(itemId: String) {
        viewModelScope.launch {
            if (repository.retry(itemId)) {
                scheduler.scheduleImmediate(forceRunNow = true)
                _feedback.value = "Queued for retry."
            }
        }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch {
            if (repository.delete(itemId)) {
                _feedback.value = "Item deleted."
            }
        }
    }

    fun consumeFeedback() {
        _feedback.value = null
    }

    companion object {
        fun factory(
            repository: SavedItemRepository,
            scheduler: ProcessingScheduler,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SavedItemsViewModel(repository, scheduler) as T
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedItemsScreen(
    viewModel: SavedItemsViewModel,
    initialFilter: StatusFilter?,
    onInitialFilterConsumed: () -> Unit,
    onOpenDetails: (String) -> Unit,
    onSearchTag: (String) -> Unit,
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val editTarget by viewModel.editTarget.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var deleteCandidateId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(initialFilter) {
        if (initialFilter != null) {
            viewModel.setStatusFilter(initialFilter)
            onInitialFilterConsumed()
        }
    }

    LaunchedEffect(feedback) {
        val msg = feedback ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeFeedback()
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved items") },
                actions = {
                    TextButton(
                        onClick = viewModel::toggleSortOrder,
                        modifier = Modifier.testTag("sort_toggle_button"),
                    ) {
                        Icon(Icons.Outlined.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(query.sortOrder.label)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("saved_items_screen"),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                StatusFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = query.statusFilter == filter,
                        onClick = { viewModel.setStatusFilter(filter) },
                        label = { Text(filter.label) },
                        modifier = Modifier.testTag("filter_${filter.name}"),
                    )
                }
            }

            if (items.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (query.statusFilter == StatusFilter.ALL) {
                                "No saved items yet"
                            } else {
                                "No items in \"${query.statusFilter.label}\""
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (query.statusFilter == StatusFilter.ALL) {
                                "Share any Reel or post from Instagram to Reelix and it will appear here."
                            } else {
                                "Switch filters above to view the rest of your vault."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items = items, key = { it.id }) { entry ->
                        SavedItemCard(
                            entry = entry,
                            onOpenDetails = { onOpenDetails(entry.id) },
                            onOpenInstagram = { SafeUrlOpener.openInstagramUrl(context, entry.originalUrl) },
                            onQuickEdit = { viewModel.openQuickEdit(entry.id) },
                            onRetry = { viewModel.retryItem(entry.id) },
                            onDeleteRequest = { deleteCandidateId = entry.id },
                            onTagClick = onSearchTag,
                        )
                    }
                }
            }
        }
    }

    editTarget?.let { target ->
        EditUserMetadataDialog(
            initial = target.initial,
            onSave = { updated -> viewModel.saveQuickEdit(target.itemId, updated) },
            onDismiss = viewModel::dismissQuickEdit,
        )
    }

    deleteCandidateId?.let { targetId ->
        ConfirmDeleteDialog(
            onConfirm = {
                deleteCandidateId = null
                viewModel.deleteItem(targetId)
            },
            onDismiss = { deleteCandidateId = null },
        )
    }
    }
}

