package com.partstock.scanner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.Locations

/** Bin chip shown at the top of the scan screen in batch mode; tap to change the batch's bin. */
@Composable
fun BatchBinChip(bin: String, onBin: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { editing = true },
        label = { Text(if (bin.isBlank()) "Bin: none" else "Bin: ${Locations.normalize(bin)}") },
        leadingIcon = { Icon(Icons.Filled.Inventory2, contentDescription = null, modifier = Modifier.size(18.dp)) },
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
    )
    if (editing) {
        var text by remember { mutableStateOf(bin) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Bin for this batch") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Every bag saved from the batch goes into this bin.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Bin / location") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onBin(text.trim())
                    editing = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The batch queue at the bottom of the scan screen: one small card per bag with its status, "Save confident" for the
 * bags that pass every rule, and "Review" to go through the rest one by one.
 */
@Composable
fun BatchTray(
    cards: List<BatchCard>,
    reading: Boolean,
    saving: Boolean,
    onSaveConfident: () -> Unit,
    onReviewNext: () -> Unit,
    onOpen: (BatchCard) -> Unit,
    onRemove: (BatchCard) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = cards.count { it.status == BatchStatus.READY }
    val toReview = cards.count { it.status == BatchStatus.CHECK || it.status == BatchStatus.DUPLICATE }
    val looking = cards.count { it.status == BatchStatus.LOOKING_UP }
    var removing by remember { mutableStateOf<BatchCard?>(null) }
    var clearing by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val summary = buildString {
                    append(if (cards.size == 1) "1 bag" else "${cards.size} bags")
                    if (ready > 0) append(" · $ready ready")
                    if (looking > 0) append(" · $looking looking up")
                }
                Text(summary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (cards.isNotEmpty()) {
                    TextButton(onClick = { clearing = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Clear") }
                }
            }
            if (cards.isEmpty() && !reading) {
                Text(
                    "Scan bags one after another. Each one is read and looked up here while you scan the next.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Newest first, so the bag just scanned is always in view.
                    if (reading) item(key = "reading") { ReadingCard() }
                    items(cards.asReversed(), key = { it.item.id }) { c ->
                        BatchItemCard(c, onClick = { onOpen(c) }, onRemove = { removing = c })
                    }
                }
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSaveConfident,
                    enabled = ready > 0 && !saving,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Save confident ($ready)", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onReviewNext,
                    enabled = toReview > 0 && !saving,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text("Review ($toReview)", maxLines = 1) }
            }
        }
    }

    removing?.let { c ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove this bag?") },
            text = { Text("${partName(c)} is taken out of the batch and not saved.") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(c)
                    removing = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } },
        )
    }
    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text("Clear the batch?") },
            text = { Text("All ${cards.size} bags are taken out of the batch. Nothing is saved.") },
            confirmButton = {
                TextButton(onClick = {
                    onClear()
                    clearing = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text("Keep") } },
        )
    }
}

private fun partName(c: BatchCard): String =
    c.item.found?.mpn?.takeIf { it.isNotBlank() && c.part.mpn.isNullOrBlank() }
        ?: c.part.inventoryKey() ?: "Unreadable bag"

@Composable
private fun ReadingCard() {
    Card(Modifier.width(CARD_WIDTH).height(CARD_HEIGHT)) {
        Column(
            Modifier.padding(10.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Reading…", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun BatchItemCard(c: BatchCard, onClick: () -> Unit, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (c.status) {
        BatchStatus.READY -> scheme.primaryContainer to scheme.onPrimaryContainer
        BatchStatus.CHECK -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        BatchStatus.DUPLICATE -> scheme.errorContainer to scheme.onErrorContainer
        BatchStatus.LOOKING_UP -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.width(CARD_WIDTH).height(CARD_HEIGHT).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Box {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 20.dp)) {
                    when (c.status) {
                        BatchStatus.LOOKING_UP -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = content)
                        BatchStatus.READY -> Icon(Icons.Filled.TaskAlt, null, Modifier.size(16.dp))
                        BatchStatus.CHECK -> Icon(Icons.Filled.Error, null, Modifier.size(16.dp))
                        BatchStatus.DUPLICATE -> Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                    }
                    Text(
                        when (c.status) {
                            BatchStatus.LOOKING_UP -> "Looking up"
                            BatchStatus.READY -> "Looked up"
                            BatchStatus.CHECK -> "Needs check"
                            BatchStatus.DUPLICATE -> "Duplicate bag"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 4.dp),
                        maxLines = 1,
                    )
                }
                Text(
                    partName(c),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    c.part.quantity?.let { "Qty $it" } ?: "Qty ?",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
                val detail = when (c.status) {
                    BatchStatus.READY -> c.item.lookupNote
                    BatchStatus.LOOKING_UP -> c.item.lookupNote
                    else -> c.reasons.firstOrNull().orEmpty()
                }
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove from batch",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .clickable(onClick = onRemove)
                    .padding(4.dp),
                tint = content.copy(alpha = 0.7f),
            )
        }
    }
}

private val CARD_WIDTH = 150.dp
private val CARD_HEIGHT = 104.dp
