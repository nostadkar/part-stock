package com.partstock.scanner

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.Categories
import com.partstock.scanner.parse.Links
import com.partstock.scanner.parse.Locations
import com.partstock.scanner.parse.PriceRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate


/** Move N pieces of one part from one of its bins to another bin (new or existing). Saves straight away. */
@Composable
internal fun MoveDialog(
    bins: Map<String, Int>,
    knownBins: List<String>,
    onDismiss: () -> Unit,
    onMove: (String, String, Int) -> Unit,
) {
    val sources = bins.filterValues { it > 0 }.keys.toList()
    var from by remember { mutableStateOf(sources.firstOrNull()) }
    var to by remember { mutableStateOf("") }
    var qtyText by remember { mutableStateOf(sources.firstOrNull()?.let { bins[it]?.toString() }.orEmpty()) }
    val have = from?.let { bins[it] } ?: 0
    val n = qtyText.toIntOrNull() ?: 0
    val ok = from != null && to.isNotBlank() && n in 1..have && !Locations.same(from, to)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move stock") },
        text = {
            if (sources.isEmpty()) {
                Text("Nothing in stock to move.")
            } else Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("From", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sources.forEach { b ->
                        FilterChip(
                            selected = from == b,
                            onClick = {
                                from = b
                                qtyText = bins[b]?.toString().orEmpty()
                            },
                            label = { Text("$b · ${bins[b]}") },
                        )
                    }
                }
                OutlinedTextField(
                    qtyText, { v -> qtyText = v.filter { it.isDigit() }.take(7) },
                    label = { Text("How many") }, singleLine = true,
                    supportingText = { Text(if (n > have) "Only $have in $from" else "$have in $from") },
                    isError = n > have,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(to, { to = it }, label = { Text("To bin") }, singleLine = true)
                val others = knownBins.filter { k -> from == null || !Locations.same(k, from) }
                if (others.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        others.forEach { b ->
                            FilterChip(selected = Locations.same(b, to) && to.isNotBlank(), onClick = { to = b }, label = { Text(b) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = { from?.let { onMove(it, to.trim(), n) } }) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun PartRow.key() = mpn.ifBlank { customerPn.ifBlank { distributorPn } }

/** Every change to the part, newest first: date, what happened, old → new quantity and what else changed. */
@Composable
internal fun HistoryDialog(db: InventoryDb, part: PartRow, onDismiss: () -> Unit) {
    // null while loading (read on IO, not the main thread)
    val loaded by produceState<List<HistoryEntry>?>(null, part.id) { value = withContext(Dispatchers.IO) { db.history(part) } }
    val entries = loaded.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("History · ${part.key()}") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (loaded == null) CircularProgressIndicator()
                else if (entries.isEmpty()) Text("No changes recorded yet.")
                entries.forEach { h ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                historyAction(h.action) + if (h.undone) " (undone)" else "",
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            val qty = when {
                                h.qtyBefore != null && h.qtyAfter != null && h.qtyBefore != h.qtyAfter -> "${h.qtyBefore} → ${h.qtyAfter}"
                                h.qtyAfter != null -> "${h.qtyAfter}"
                                h.qtyBefore != null -> "${h.qtyBefore} → –"
                                else -> ""
                            }
                            Text(qty, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            h.ts + if (h.legacy) " · from scan log" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (h.summary.isNotBlank()) Text(h.summary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

internal fun historyAction(a: String): String = when {
    a == "add" || a == "scan add" -> "Scan · add"
    a == "set" || a == "scan set" -> "Scan · set count"
    a == "use" -> "Used"
    a.startsWith("use · ") -> "Used · " + a.removePrefix("use · ")
    a == "edit" -> "Edited"
    a == "adjust" -> "Added by hand"
    a == "move" -> "Moved"
    a == "rename bin" -> "Bin renamed"
    a == "delete" -> "Deleted"
    a == "undo" -> "Undo"
    a.startsWith("lookup") -> "Lookup" + a.removePrefix("lookup").let { if (it.isBlank()) "" else " ·$it" }
    a == "import" -> "CSV import"
    a == "restore" -> "CSV restore"
    a == "undone" -> "Scan (undone)"
    else -> a.replaceFirstChar { it.uppercase() }
}
