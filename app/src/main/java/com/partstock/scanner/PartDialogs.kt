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


@Composable
internal fun EditDialog(
    part: PartRow,
    onDismiss: () -> Unit,
    /** Pieces per bin, main bin, description, cost, category, pieces to use, minimum. */
    onSave: (Map<String, Int>, String, String, Double?, String, Int, Int?) -> Unit,
    onDelete: () -> Unit,
    knownBins: List<String> = emptyList(),
    onMove: (from: String, to: String, qty: Int) -> Unit = { _, _, _ -> },
    /** Make a QR label for this part; null hides the button. */
    onLabel: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
    /** The part's photos strip; null hides it. */
    photos: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val startBins = remember { part.bins() }
    val multi = startBins.size > 1
    // One bin: the usual Quantity + Bin fields. Several bins: a count per bin, and the main bin stays as it is.
    var qty by remember { mutableStateOf(part.quantity.toString()) }
    val binQty = remember { mutableStateListOf(*startBins.values.map { it.toString() }.toTypedArray()) }
    var moving by remember { mutableStateOf(false) }
    // With one bin, edit that bin's name (it can differ from the main bin after the main bin was emptied).
    var location by remember { mutableStateOf(if (multi) part.location else Locations.plain(startBins.keys.first())) }
    var description by remember { mutableStateOf(part.description) }
    var cost by remember { mutableStateOf(part.unitCost?.let { InventoryDb.fmtCost(it) }.orEmpty()) }
    var category by remember { mutableStateOf(part.category) }
    var minimum by remember { mutableStateOf(part.minQty?.toString().orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var use by remember { mutableIntStateOf(0) }
    var confirmUse by remember { mutableStateOf(false) }
    val link = part.link()
    val stock = if (multi) startBins.values.withIndex().sumOf { (i, q) -> binQty[i].toIntOrNull() ?: q }
        else qty.toIntOrNull() ?: part.quantity
    fun stockMap(): Map<String, Int> =
        if (multi) startBins.keys.withIndex().associate { (i, b) -> b to (binQty[i].toIntOrNull() ?: startBins.getValue(b)) }
        else mapOf(Locations.normalize(location) to stock)
    // "Price from Mouser, Oct 18", shown while the cost is the one that lookup saved.
    val priceLabel = remember { PriceRefresh.label(part.priceSource, part.priceCheckedAt, LocalDate.now()) }
    val savedCost = remember { part.unitCost?.let { InventoryDb.fmtCost(it) }.orEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(part.mpn.ifBlank { part.customerPn.ifBlank { part.distributorPn } }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val ids = listOf(
                    if (part.distributorPn.isNotBlank()) "${part.distributor.ifBlank { "Dist" }}: ${part.distributorPn}" else "",
                    if (part.customerPn.isNotBlank()) "Cust: ${part.customerPn}" else "",
                ).filter { it.isNotBlank() }.joinToString(" · ")
                if (ids.isNotBlank()) Text(ids, style = MaterialTheme.typography.bodySmall)
                val chips = part.specs().chips()
                if (chips.isNotEmpty()) SpecChips(chips)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    if (link.isNotBlank()) {
                        TextButton(onClick = { openLink(context, link) }) { Text("Open on ${Links.siteName(link)}") }
                    }
                    val sheet = part.datasheet()
                    if (sheet.isNotBlank()) {
                        TextButton(onClick = { openLink(context, sheet) }) {
                            Text(if (Links.isDatasheetSearch(sheet)) "Find datasheet" else "Datasheet")
                        }
                    }
                    if (onLabel != null) TextButton(onClick = onLabel) { Text("Label") }
                    if (onHistory != null) TextButton(onClick = onHistory) { Text("History") }
                }
                photos?.invoke()
                OutlinedTextField(description, { description = it }, label = { Text("Description") })
                CategoryPicker(category) { category = it }
                if (multi) {
                    Text("Stock by bin · total $stock", style = MaterialTheme.typography.titleSmall)
                    startBins.keys.forEachIndexed { i, b ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                b + if (Locations.same(b, part.location)) " (main)" else "",
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                binQty[i], { v -> binQty[i] = v.filter { it.isDigit() }.take(7) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(110.dp),
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        qty, { v -> qty = v.filter { it.isDigit() } }, label = { Text("Quantity") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                TextButton(onClick = { moving = true }) {
                    Text(if (multi) "Move between bins…" else "Move some to another bin…")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use", modifier = Modifier.width(40.dp))
                    IconButton(onClick = {
                        use = maxOf(0, use - 1)
                        confirmUse = false
                    }) { Icon(Icons.Filled.Remove, contentDescription = "Use fewer") }
                    OutlinedTextField(
                        if (use == 0) "" else use.toString(),
                        { v ->
                            use = v.filter { it.isDigit() }.take(6).toIntOrNull() ?: 0
                            confirmUse = false
                        },
                        placeholder = { Text("0") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(88.dp),
                    )
                    IconButton(onClick = {
                        use += 1
                        confirmUse = false
                    }) { Icon(Icons.Filled.Add, contentDescription = "Use more") }
                }
                if (use > 0) {
                    Text(
                        if (use > stock) "Only $stock in stock; saving sets it to 0." else "After use: ${stock - use}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (use > stock) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    minimum, { v -> minimum = v.filter { it.isDigit() }.take(7) },
                    label = { Text("Minimum (optional)") }, singleLine = true,
                    supportingText = { Text("Low stock when the count is at or below this") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    cost, { v -> cost = v.filter { it.isDigit() || it == '.' } }, label = { Text("Unit cost (each)") },
                    singleLine = true, prefix = { Text("$") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = if (priceLabel.isNotBlank() && cost == savedCost) {
                        { Text(priceLabel) }
                    } else null,
                )
                if (!multi) {
                    OutlinedTextField(location, { location = it }, label = { Text("Bin / location") }, singleLine = true)
                }
                if (part.notes.isNotBlank()) Text("Notes: ${part.notes}", style = MaterialTheme.typography.bodySmall)
                Text("Scanned ${part.scans}× · updated ${part.updatedAt}", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (use > stock && !confirmUse) confirmUse = true
                else onSave(
                    stockMap(), location, description, cost.toDoubleOrNull(), category, use,
                    minimum.toIntOrNull()?.takeIf { it > 0 },
                )
            }) { Text(if (confirmUse) "Tap again to save" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                Text(if (confirmDelete) "Tap again to delete" else "Delete", color = MaterialTheme.colorScheme.error)
            }
        },
    )

    if (moving) {
        MoveDialog(
            bins = startBins,
            knownBins = knownBins,
            onDismiss = { moving = false },
            onMove = { from, to, n ->
                moving = false
                onMove(from, to, n)
            },
        )
    }
}

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
