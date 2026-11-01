package app.reelix.ui.screens.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import app.reelix.data.repository.FtsQueryBuilder
import app.reelix.data.repository.SavedItemRepository
import app.reelix.domain.model.ContentType
import app.reelix.domain.model.ItemListEntry
import app.reelix.domain.model.ItemQuery
import app.reelix.domain.model.SortOrder
import app.reelix.domain.model.StatusFilter
import app.reelix.ui.components.SavedItemCard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val repository: SavedItemRepository,
) : ViewModel() {

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    private val _query = MutableStateFlow(ItemQuery())
    val query: StateFlow<ItemQuery> = _query.asStateFlow()

    val topTags: StateFlow<List<String>> = repository.observeTopTags(limit = 16)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val results: StateFlow<List<ItemListEntry>> = combine(_searchText, _query) { text, q -> text to q }
        .flatMapLatest { (text, q) ->
            if (FtsQueryBuilder.build(text) == null) {
                flowOf(emptyList())
            } else {
                repository.search(text, q)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSearchText(text: String) {
        _searchText.value = text
    }

    fun setStatusFilter(filter: StatusFilter) {
        _query.update { it.copy(statusFilter = filter) }
    }

    fun toggleContentType(type: ContentType) {
        _query.update { current ->
            val all = ContentType.entries.toSet()
            val updated = if (current.contentTypes == all) {
                setOf(type)
            } else if (type in current.contentTypes) {
                (current.contentTypes - type).ifEmpty { all }
            } else {
                current.contentTypes + type
            }
            current.copy(contentTypes = updated)
        }
    }

    fun toggleSortOrder() {
        _query.update {
            val next = if (it.sortOrder == SortOrder.NEWEST) SortOrder.OLDEST else SortOrder.NEWEST
            it.copy(sortOrder = next)
        }
    }

    companion object {
        fun factory(repository: SavedItemRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SearchViewModel(repository) as T
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    initialTag: String?,
    onInitialTagConsumed: () -> Unit,
    onOpenDetails: (String) -> Unit,
) {
    val searchText by viewModel.searchText.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val topTags by viewModel.topTags.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()

    val context = LocalContext.current

    LaunchedEffect(initialTag) {
        if (!initialTag.isNullOrBlank()) {
            viewModel.setSearchText(initialTag)
            onInitialTagConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search vault") },
                actions = {
                    TextButton(onClick = viewModel::toggleSortOrder) {
                        Icon(Icons.Outlined.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(query.sortOrder.label)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("search_screen"),
        ) {
            OutlinedTextField(
                value = searchText,
                onValueChange = viewModel::setSearchText,
                placeholder = { Text("Search titles, captions, transcripts, tags, notes…") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchText.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchText("") }) {
                            Icon(Icons.Outlined.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("search_input"),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                StatusFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = query.statusFilter == filter,
                        onClick = { viewModel.setStatusFilter(filter) },
                        label = { Text(filter.label) },
                    )
                }
                val allTypesSelected = query.contentTypes.size == ContentType.entries.size
                listOf(ContentType.REEL, ContentType.POST, ContentType.AUDIO, ContentType.IGTV).forEach { type ->
                    FilterChip(
                        selected = !allTypesSelected && type in query.contentTypes,
                        onClick = { viewModel.toggleContentType(type) },
                        label = { Text(type.label) },
                    )
                }
            }

            val hasActiveQuery = FtsQueryBuilder.build(searchText) != null

            if (!hasActiveQuery) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                ) {
                    Text("Offline full-text search", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Matches titles, captions, transcripts, AI summaries, tags, categories and personal notes directly from your local SQLite FTS index.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (topTags.isNotEmpty()) {
                        Spacer(Modifier.height(18.dp))
                        Text("Popular tags in your vault", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            topTags.forEach { tag ->
                                SuggestionChip(
                                    onClick = { viewModel.setSearchText(tag) },
                                    label = { Text("#$tag") },
                                )
                            }
                        }
                    }
                }
            } else if (results.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No matches found", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Nothing in your local vault matched \"$searchText\" with the selected filters.",
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
                    items(items = results, key = { it.id }) { entry ->
                        SavedItemCard(
                            entry = entry,
                            onOpenDetails = { onOpenDetails(entry.id) },
                            onOpenInstagram = { SafeUrlOpener.openInstagramUrl(context, entry.originalUrl) },
                            onTagClick = { tag -> viewModel.setSearchText(tag) },
                        )
                    }
                }
            }
        }
    }
}

