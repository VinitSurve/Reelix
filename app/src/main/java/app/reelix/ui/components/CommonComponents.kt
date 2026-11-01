package app.reelix.ui.components

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.reelix.domain.model.ContentType
import app.reelix.domain.model.ItemListEntry
import app.reelix.domain.model.ProcessingStatus
import app.reelix.domain.model.StageStatus
import app.reelix.domain.model.TagSource
import app.reelix.domain.model.UserMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class ProvenanceKind(val label: String) {
    USER("Yours"),
    EXTRACTED("Extracted"),
    MEDIA("Media"),
    TRANSCRIPTION("Transcribed"),
    AI("AI-generated"),
}

@Composable
fun ProvenanceBadge(kind: ProvenanceKind, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (kind) {
        ProvenanceKind.USER -> scheme.primaryContainer to scheme.onPrimaryContainer
        ProvenanceKind.EXTRACTED -> scheme.secondaryContainer to scheme.onSecondaryContainer
        ProvenanceKind.MEDIA -> scheme.secondaryContainer to scheme.onSecondaryContainer
        ProvenanceKind.TRANSCRIPTION -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        ProvenanceKind.AI -> scheme.tertiaryContainer to scheme.onTertiaryContainer
    }
    Pill(text = kind.label, containerColor = bg, contentColor = fg, modifier = modifier)
}

@Composable
fun ItemHonestStatusBadge(label: String, isAttention: Boolean, isAwaiting: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when {
        isAttention -> scheme.errorContainer to scheme.onErrorContainer
        isAwaiting -> scheme.surfaceVariant to scheme.onSurfaceVariant
        label == "AI enriched" -> scheme.primaryContainer to scheme.onPrimaryContainer
        label == "Transcribed" -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        label == "Media available" || label == "Audio extracted" -> scheme.secondaryContainer to scheme.onSecondaryContainer
        else -> scheme.secondaryContainer.copy(alpha = 0.7f) to scheme.onSecondaryContainer
    }
    Pill(text = label, containerColor = bg, contentColor = fg, modifier = modifier)
}

@Composable
fun ProcessingStatusBadge(status: ProcessingStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (status) {
        ProcessingStatus.COMPLETED -> scheme.primaryContainer to scheme.onPrimaryContainer
        ProcessingStatus.NEEDS_ATTENTION -> scheme.errorContainer to scheme.onErrorContainer
        ProcessingStatus.QUEUED,
        ProcessingStatus.PROCESSING,
        ProcessingStatus.WAITING_RETRY,
        -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Pill(text = status.label, containerColor = bg, contentColor = fg, modifier = modifier)
}

@Composable
fun StageStatusPill(status: StageStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (status) {
        StageStatus.SUCCEEDED -> scheme.primaryContainer to scheme.onPrimaryContainer
        StageStatus.FAILED -> scheme.errorContainer to scheme.onErrorContainer
        StageStatus.RETRY_SCHEDULED -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Pill(text = status.label, containerColor = bg, contentColor = fg, modifier = modifier)
}

@Composable
fun Pill(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        contentColor = contentColor,
        modifier = if (onClick != null) {
            modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
        } else {
            modifier
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * Renders a locally downloaded preview image ONLY if one was legitimately obtained and saved on disk.
 * Never displays stock or fake thumbnails.
 */
@Composable
fun LegitimateThumbnail(
    path: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    if (path.isNullOrBlank()) return
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path)
                if (!file.exists()) return@runCatching null
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val img = bitmap ?: return
    Image(
        bitmap = img,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(10.dp)),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavedItemCard(
    entry: ItemListEntry,
    onOpenDetails: () -> Unit,
    onOpenInstagram: () -> Unit,
    onQuickEdit: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onDeleteRequest: (() -> Unit)? = null,
    onTagClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    OutlinedCard(
        onClick = onOpenDetails,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .testTag("saved_item_card_${entry.id}"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Pill(text = entry.contentType.label)
                entry.category?.takeIf { it.isNotBlank() }?.let { category ->
                    Spacer(Modifier.width(6.dp))
                    Pill(
                        text = category,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.weight(1f))
                ItemHonestStatusBadge(
                    label = entry.primaryStatusLabel,
                    isAttention = entry.isNeedsAttention,
                    isAwaiting = entry.processingStatus.isAwaiting,
                )
                if (onQuickEdit != null || onRetry != null || onDeleteRequest != null) {
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "Item actions",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Open in Instagram") },
                                onClick = {
                                    menuExpanded = false
                                    onOpenInstagram()
                                },
                            )
                            if (onQuickEdit != null) {
                                DropdownMenuItem(
                                    text = { Text("Edit notes & metadata") },
                                    onClick = {
                                        menuExpanded = false
                                        onQuickEdit()
                                    },
                                )
                            }
                            if (onRetry != null) {
                                DropdownMenuItem(
                                    text = { Text("Retry processing") },
                                    onClick = {
                                        menuExpanded = false
                                        onRetry()
                                    },
                                )
                            }
                            if (onDeleteRequest != null) {
                                DropdownMenuItem(
                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menuExpanded = false
                                        onDeleteRequest()
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    val primaryHeadline = entry.displayTitle
                        ?: entry.captionPreview?.lineSequence()?.firstOrNull()?.take(100)
                        ?: Formatters.cleanUrlDisplay(entry.originalUrl)
                    val isUrlFallback = entry.displayTitle == null && entry.captionPreview == null

                    Text(
                        text = primaryHeadline,
                        style = if (isUrlFallback) {
                            MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace)
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    val bodyPreview = entry.summary ?: entry.captionPreview?.takeIf { entry.displayTitle != null }
                    if (!bodyPreview.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        if (entry.summary != null) {
                            ProvenanceBadge(ProvenanceKind.AI)
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            text = bodyPreview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (!entry.thumbnailPath.isNullOrBlank()) {
                    Spacer(Modifier.width(12.dp))
                    LegitimateThumbnail(
                        path = entry.thumbnailPath,
                        contentDescription = "Preview image",
                        modifier = Modifier.size(72.dp),
                    )
                }
            }

            if (entry.tags.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    entry.tags.take(6).forEach { tag ->
                        Pill(
                            text = "#$tag",
                            onClick = onTagClick?.let { cb -> { cb(tag) } },
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Saved ${Formatters.formatDate(entry.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (entry.processingStatus == ProcessingStatus.NEEDS_ATTENTION && onRetry != null) {
                    TextButton(onClick = onRetry) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Retry")
                    }
                }
                TextButton(onClick = onOpenInstagram) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Instagram")
                }
            }
        }
    }
}

@Composable
fun ReelixModalDialog(
    title: String,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onDismissRequest)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.48f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismissRequest,
            )
            .imePadding()
            .padding(20.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            modifier = Modifier
                .widthIn(max = 500.dp)
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(14.dp))
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    content()
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    dismissButton()
                    Spacer(Modifier.width(8.dp))
                    confirmButton()
                }
            }
        }
    }
}

@Composable
fun ConfirmDeleteDialog(
    title: String = "Delete saved item?",
    body: String = "This removes the saved Instagram link, notes, tags and processing history from your local vault.",
    confirmLabel: String = "Delete",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ReelixModalDialog(
        title = title,
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        content = {
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        },
    )
}

@Composable
fun EditUserMetadataDialog(
    initial: UserMetadata,
    onSave: (UserMetadata) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var title by rememberSaveable { mutableStateOf(initial.title.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(initial.category.orEmpty()) }
    var tagsText by rememberSaveable { mutableStateOf(initial.tags.joinToString(", ")) }
    var notes by rememberSaveable { mutableStateOf(initial.notes.orEmpty()) }
    var caption by rememberSaveable { mutableStateOf(initial.caption.orEmpty()) }
    var transcript by rememberSaveable { mutableStateOf(initial.transcript.orEmpty()) }

    ReelixModalDialog(
        title = "Edit your metadata",
        onDismissRequest = {
            focusManager.clearFocus(force = true)
            onDismiss()
        },
        confirmButton = {
            TextButton(
                onClick = {
                    focusManager.clearFocus(force = true)
                    val parsedTags = tagsText.split(',', '\n')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    onSave(
                        UserMetadata(
                            title = title,
                            caption = caption,
                            transcript = transcript,
                            notes = notes,
                            category = category,
                            tags = parsedTags,
                        ),
                    )
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    focusManager.clearFocus(force = true)
                    onDismiss()
                },
            ) { Text("Cancel") }
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Only your user-owned fields are modified. Extracted and AI-generated records remain untouched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    placeholder = { Text("e.g. Cooking, Android, Fitness") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text("Your tags (comma-separated)") },
                    placeholder = { Text("kotlin, architecture, compose") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Personal notes") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    label = { Text("Manual caption (optional)") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = transcript,
                    onValueChange = { transcript = it },
                    label = { Text("Manual transcript (optional)") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

@Composable
fun SaveLinkDialog(
    onSaveText: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var input by rememberSaveable { mutableStateOf("") }
    ReelixModalDialog(
        title = "Save Instagram link",
        onDismissRequest = {
            focusManager.clearFocus(force = true)
            onDismiss()
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank(),
                onClick = {
                    focusManager.clearFocus(force = true)
                    onSaveText(input)
                },
            ) {
                Text("Save to queue")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    focusManager.clearFocus(force = true)
                    onDismiss()
                },
            ) { Text("Cancel") }
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Tip: While browsing Instagram, tap Share → Save to Reelix for instant one-tap saving. You can also paste one or more Instagram links here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Instagram Reel or Post URL") },
                    placeholder = { Text("https://www.instagram.com/reel/...") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_url_input"),
                )
            }
        },
    )
}

fun TagSource.toProvenance(): ProvenanceKind = when (this) {
    TagSource.USER -> ProvenanceKind.USER
    TagSource.EXTRACTED -> ProvenanceKind.EXTRACTED
    TagSource.AI -> ProvenanceKind.AI
}
