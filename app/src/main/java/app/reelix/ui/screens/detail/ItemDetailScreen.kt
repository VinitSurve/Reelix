package app.reelix.ui.screens.detail

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.reelix.core.url.SafeUrlOpener
import app.reelix.data.repository.SavedItemRepository
import app.reelix.domain.model.ItemDetails
import app.reelix.domain.model.StageInfo
import app.reelix.domain.model.TagSource
import app.reelix.domain.model.UserMetadata
import app.reelix.processing.ProcessingScheduler
import app.reelix.ui.components.ConfirmDeleteDialog
import app.reelix.ui.components.EditUserMetadataDialog
import app.reelix.ui.components.Formatters
import app.reelix.ui.components.LegitimateThumbnail
import app.reelix.ui.components.Pill
import app.reelix.ui.components.ProcessingStatusBadge
import app.reelix.ui.components.ProvenanceBadge
import app.reelix.ui.components.ProvenanceKind
import app.reelix.ui.components.StageStatusPill
import app.reelix.ui.components.toProvenance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data object NotFound : DetailUiState
    data class Loaded(val details: ItemDetails) : DetailUiState
}

class ItemDetailViewModel(
    private val itemId: String,
    private val repository: SavedItemRepository,
    private val scheduler: ProcessingScheduler,
) : ViewModel() {

    val state: StateFlow<DetailUiState> = repository.observeDetails(itemId)
        .map { details -> if (details == null) DetailUiState.NotFound else DetailUiState.Loaded(details) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState.Loading)

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    fun saveUserMetadata(metadata: UserMetadata) {
        viewModelScope.launch {
            if (repository.updateUserMetadata(itemId, metadata)) {
                _feedback.value = "Saved your notes & metadata."
            }
        }
    }

    fun retryFailed() {
        viewModelScope.launch {
            if (repository.retry(itemId)) {
                scheduler.scheduleImmediate(forceRunNow = true)
                _feedback.value = "Queued failed stages for retry."
            }
        }
    }

    fun reprocessEnrichment() {
        viewModelScope.launch {
            if (repository.reprocess(itemId)) {
                scheduler.scheduleImmediate(forceRunNow = true)
                _feedback.value = "Queued enrichment stages."
            }
        }
    }

    fun deleteItem() {
        viewModelScope.launch {
            if (repository.delete(itemId)) {
                _deleted.value = true
            }
        }
    }

    fun consumeFeedback() {
        _feedback.value = null
    }

    companion object {
        fun factory(
            itemId: String,
            repository: SavedItemRepository,
            scheduler: ProcessingScheduler,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ItemDetailViewModel(itemId, repository, scheduler) as T
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ItemDetailScreen(
    viewModel: ItemDetailViewModel,
    onNavigateBack: () -> Unit,
    onSearchTag: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showEditDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showAllStages by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(deleted) {
        if (deleted) onNavigateBack()
    }

    LaunchedEffect(feedback) {
        val msg = feedback ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeFeedback()
    }

    val loadedDetails = (state as? DetailUiState.Loaded)?.details

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Item details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (loadedDetails != null) {
                        IconButton(
                            onClick = { showEditDialog = true },
                            modifier = Modifier.testTag("detail_edit_button"),
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit metadata")
                        }
                        IconButton(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.testTag("detail_delete_button"),
                        ) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = "Delete item",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (val current = state) {
            DetailUiState.Loading -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    CircularProgressIndicator()
                }
            }

            DetailUiState.NotFound -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Item not found", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onNavigateBack) { Text("Back to vault") }
                    }
                }
            }

            is DetailUiState.Loaded -> {
                val item = current.details
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = innerPadding.calculateTopPadding() + 8.dp,
                        bottom = innerPadding.calculateBottomPadding() + 28.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("item_detail_screen"),
                ) {
                    // 1. Overview & Original URL
                    item(key = "overview") {
                        SectionCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Pill(text = item.contentType.label)
                                if (item.copyIndex > 0) {
                                    Spacer(Modifier.width(6.dp))
                                    Pill(text = "Copy #${item.copyIndex + 1}")
                                }
                                Spacer(Modifier.weight(1f))
                                ProcessingStatusBadge(
                                    status = item.processingStatus,
                                    errorMessage = item.lastError,
                                    isMediaAvailable = item.isMediaAvailable,
                                )
                            }
                            Spacer(Modifier.height(10.dp))

                            Text(
                                text = item.displayTitle ?: Formatters.cleanUrlDisplay(item.originalUrl),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Spacer(Modifier.height(8.dp))

                            SelectionContainer {
                                Text(
                                    text = item.originalUrl,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = buildString {
                                    append("Saved ${Formatters.formatDateTime(item.createdAt)}")
                                    item.lastProcessedAt?.let { append(" • Processed ${Formatters.formatDateTime(it)}") }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Spacer(Modifier.height(12.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Pill(text = "Extraction: ${item.extractionStatus.label}")
                                Pill(text = "Transcription: ${item.transcriptionStatus.label}")
                                Pill(text = "AI analysis: ${item.analysisStatus.label}")
                            }

                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { SafeUrlOpener.openInstagramUrl(context, item.originalUrl) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Open Instagram")
                                }
                                OutlinedButton(
                                    onClick = { clipboard.setText(AnnotatedString(item.originalUrl)) },
                                ) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Copy URL")
                                }
                            }
                        }
                    }

                    // 2. Error / Attention Banner (when present)
                    if (!item.lastError.isNullOrBlank()) {
                        item(key = "error_banner") {
                            val isManualInputRequired = item.lastError.contains("URL-only", ignoreCase = true) ||
                                item.lastError.contains("without media", ignoreCase = true) ||
                                item.lastError.contains("manual", ignoreCase = true) ||
                                (!item.isMediaAvailable && item.isNeedsAttention)

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isManualInputRequired) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
                                contentColor = if (isManualInputRequired) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = if (isManualInputRequired) "Manual input required" else "Processing issue",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(item.lastError, style = MaterialTheme.typography.bodyMedium)
                                    if (isManualInputRequired) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "Instagram URLs do not include public media. Share the reel video directly to Reelix or add personal notes below to enable knowledge extraction.",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    } else {
                                        Spacer(Modifier.height(10.dp))
                                        OutlinedButton(onClick = viewModel::retryFailed) {
                                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Retry failed stage")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. User-provided Information
                    item(key = "user_section") {
                        SectionCard {
                            SectionHeader(
                                title = "Your notes & metadata",
                                provenance = ProvenanceKind.USER,
                                actionLabel = "Edit",
                                onAction = { showEditDialog = true },
                            )

                            val userTags = item.tags.filter { it.source == TagSource.USER }
                            val hasAnyUserContent = !item.userTitle.isNullOrBlank() ||
                                !item.userCategory.isNullOrBlank() ||
                                !item.userNotes.isNullOrBlank() ||
                                !item.userCaption.isNullOrBlank() ||
                                !item.userTranscript.isNullOrBlank() ||
                                userTags.isNotEmpty()

                            if (!hasAnyUserContent) {
                                Text(
                                    text = "Add your own title, category, tags, notes, caption or transcript. Everything you enter is indexed for offline search.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    LabeledBlock("Your title", item.userTitle)
                                    LabeledBlock("Category", item.userCategory)
                                    LabeledBlock("Notes", item.userNotes)
                                    LabeledBlock("Manual caption", item.userCaption)
                                    LabeledBlock("Manual transcript", item.userTranscript)
                                    if (userTags.isNotEmpty()) {
                                        Text(
                                            text = "Your tags",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            userTags.forEach { tag ->
                                                Pill(text = "#${tag.name}", onClick = { onSearchTag(tag.name) })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. Extracted Information
                    item(key = "extracted_section") {
                        SectionCard {
                            SectionHeader(
                                title = "Extracted from public preview",
                                provenance = ProvenanceKind.EXTRACTED,
                            )

                            val extractedTags = item.tags.filter { it.source == TagSource.EXTRACTED }
                            val hasExtracted = !item.extractedTitle.isNullOrBlank() ||
                                !item.authorName.isNullOrBlank() ||
                                !item.extractedCaption.isNullOrBlank() ||
                                !item.thumbnailPath.isNullOrBlank() ||
                                extractedTags.isNotEmpty()

                            if (!hasExtracted) {
                                Text(
                                    text = "No public link-preview metadata was returned for this URL (${item.extractionStatus.label.lowercase()}). Your saved link remains intact and you can add notes or caption text above.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (!item.thumbnailPath.isNullOrBlank()) {
                                        LegitimateThumbnail(
                                            path = item.thumbnailPath,
                                            contentDescription = "Extracted preview thumbnail",
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(180.dp),
                                        )
                                    }
                                    LabeledBlock("Author", item.authorName)
                                    LabeledBlock("Preview title", item.extractedTitle)
                                    LabeledBlock("Caption", item.extractedCaption)
                                    if (extractedTags.isNotEmpty()) {
                                        Text(
                                            text = "Caption hashtags",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            extractedTags.forEach { tag ->
                                                Pill(text = "#${tag.name}", onClick = { onSearchTag(tag.name) })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 5. Imported Media (if available)
                    if (item.mediaInfo != null) {
                        item(key = "media_info_section") {
                            val media = item.mediaInfo
                            SectionCard {
                                SectionHeader(
                                    title = "Imported media file",
                                    provenance = ProvenanceKind.MEDIA,
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LabeledBlock("Original file", media.originalFileName)
                                    LabeledBlock("MIME type", media.mimeType)
                                    val sizeStr = "${(media.sizeBytes ?: 0L) / (1024 * 1024)} MB (${(media.sizeBytes ?: 0L) / 1024} KB)"
                                    LabeledBlock("File size", sizeStr)
                                    if (media.durationMs != null && media.durationMs > 0) {
                                        val secs = media.durationMs / 1000
                                        val mins = secs / 60
                                        val remSecs = secs % 60
                                        LabeledBlock("Duration", "%d:%02d".format(mins, remSecs))
                                    }
                                    val audioStatus = if (media.audioFileExists) {
                                        "Extracted (16kHz mono WAV, ${(media.audioSizeBytes ?: 0L) / 1024} KB)"
                                    } else {
                                        item.audioExtractionStatus.label
                                    }
                                    LabeledBlock("Audio track", audioStatus)
                                }
                            }
                        }
                    }

                    // 6. Transcript (Offline Whisper or User-provided)
                    item(key = "transcript_section") {
                        SectionCard {
                            val hasGenerated = !item.generatedTranscript.isNullOrBlank()
                            val hasUser = !item.userTranscript.isNullOrBlank()
                            val transcriptKind = if (hasUser) ProvenanceKind.USER else if (hasGenerated) ProvenanceKind.TRANSCRIPTION else ProvenanceKind.EXTRACTED
                            SectionHeader(
                                title = "Transcript",
                                provenance = transcriptKind,
                            )
                            val text = item.effectiveTranscript
                            if (text.isNullOrBlank()) {
                                Text(
                                    text = "No transcript available (${item.transcriptionStatus.label.lowercase()}). To transcribe locally, share a video/audio file and import a Whisper model in Settings.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = text,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }

                    // 6b. On-screen Visual Text (OCR)
                    if (!item.ocrText.isNullOrBlank()) {
                        item(key = "ocr_section") {
                            SectionCard {
                                SectionHeader(
                                    title = "On-screen visual text (OCR)",
                                    provenance = ProvenanceKind.EXTRACTED,
                                )
                                Text(
                                    text = item.ocrText,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }

                    // 7. AI-generated Enrichment
                    item(key = "ai_section") {
                        SectionCard {
                            SectionHeader(
                                title = "AI enrichment",
                                provenance = ProvenanceKind.AI,
                                actionLabel = "Re-run",
                                onAction = viewModel::reprocessEnrichment,
                            )

                            val ai = item.aiResult
                            val aiTags = item.tags.filter { it.source == TagSource.AI }
                            if (ai == null) {
                                Text(
                                    text = "No AI output generated (${item.analysisStatus.label.lowercase()}). Reelix never fabricates summaries. Configure an optional OpenAI-compatible model (such as local Ollama) in Settings to summarise available captions, transcripts and notes.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    LabeledBlock("Main topic", ai.mainTopic)
                                    LabeledBlock("Summary", ai.summary)
                                    LabeledBlock("Detailed summary", ai.detailedSummary)
                                    if (ai.importantConcepts.isNotEmpty()) {
                                        Text(
                                            text = "Important concepts",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        ai.importantConcepts.forEach { concept ->
                                            Text("• $concept", style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                    if (ai.keyTakeaways.isNotEmpty()) {
                                        Text(
                                            text = "Key takeaways",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        ai.keyTakeaways.forEach { point ->
                                            Text("• $point", style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                    if (ai.actionableSteps.isNotEmpty()) {
                                        Text(
                                            text = "Actionable steps",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        ai.actionableSteps.forEachIndexed { index, step ->
                                            Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                    LabeledBlock("Suggested category", ai.category)
                                    if (ai.keywords.isNotEmpty()) {
                                        LabeledBlock("Keywords", ai.keywords.joinToString(", "))
                                    }
                                    if (aiTags.isNotEmpty()) {
                                        Text(
                                            text = "AI tags",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            aiTags.forEach { tag ->
                                                Pill(text = "#${tag.name}", onClick = { onSearchTag(tag.name) })
                                            }
                                        }
                                    }
                                    Text(
                                        text = "Model: ${ai.modelName ?: "configured endpoint"} • ${Formatters.formatDateTime(ai.generatedAt)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    // 6. All Tags Summary (if any)
                    if (item.tags.isNotEmpty()) {
                        item(key = "all_tags") {
                            SectionCard {
                                Text("All tags", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    item.tags.forEach { tag ->
                                        Pill(
                                            text = "#${tag.name} (${tag.source.toProvenance().label})",
                                            onClick = { onSearchTag(tag.name) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 7. Pipeline Stages & Processing History
                    item(key = "pipeline_history") {
                        SectionCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Processing pipeline",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = viewModel::retryFailed) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Retry")
                                }
                            }

                            val displayedStages = if (showAllStages) item.stages else item.stages.take(5)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                displayedStages.forEach { stageInfo ->
                                    StageRow(stageInfo)
                                }
                            }
                            if (item.stages.size > 5) {
                                TextButton(onClick = { showAllStages = !showAllStages }) {
                                    Text(if (showAllStages) "Show fewer stages" else "Show all ${item.stages.size} stages")
                                }
                            }

                            if (item.events.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(10.dp))
                                Text("Recent history", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(6.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    item.events.take(10).forEach { ev ->
                                        val prefix = ev.stage?.label?.let { "$it — " }.orEmpty()
                                        val detail = ev.message?.let { ": $it" }.orEmpty()
                                        Text(
                                            text = "${Formatters.formatDateTime(ev.createdAt)} • $prefix${ev.status}$detail",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog && loadedDetails != null) {
        EditUserMetadataDialog(
            initial = UserMetadata(
                title = loadedDetails.userTitle,
                caption = loadedDetails.userCaption,
                transcript = loadedDetails.userTranscript,
                notes = loadedDetails.userNotes,
                category = loadedDetails.userCategory,
                tags = loadedDetails.tags.filter { it.source == TagSource.USER }.map { it.name },
            ),
            onSave = { updated ->
                showEditDialog = false
                viewModel.saveUserMetadata(updated)
            },
            onDismiss = { showEditDialog = false },
        )
    }

    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            onConfirm = {
                showDeleteConfirm = false
                viewModel.deleteItem()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    OutlinedCard(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    provenance: ProvenanceKind,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ProvenanceBadge(provenance)
            Spacer(Modifier.height(4.dp))
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun LabeledBlock(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        SelectionContainer {
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StageRow(info: StageInfo) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = info.stage.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (info.attempts > 0) {
                Text(
                    text = "${info.attempts}x",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            StageStatusPill(info.status)
        }
        if (!info.lastError.isNullOrBlank()) {
            Text(
                text = info.lastError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
