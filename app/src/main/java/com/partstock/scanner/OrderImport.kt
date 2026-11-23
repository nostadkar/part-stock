package com.partstock.scanner

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.partstock.scanner.parse.Categories
import com.partstock.scanner.parse.Links
import com.partstock.scanner.parse.Locations
import com.partstock.scanner.parse.OrderCsv
import com.partstock.scanner.parse.OrderLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Inventory → Import order: pick a Mouser or DigiKey order CSV, tick the lines, add them to stock. Lines are matched
 * to existing parts like a scan. Each order line is remembered (order number + line) in the scan log, so importing
 * the same order again shows "Already imported" and leaves those lines unticked.
 *
 * The lines are shown on a full-screen page: a summary (new parts, restocks, already imported, total), filter chips,
 * one card per line with its own bin, and a bottom bar with the running total. New parts go to [defaultBin] (the
 * last bin used) unless changed; restocks go to the part's own bin. [knownBins] are offered in the bin picker.
 *
 * [onClose] gets a message to show after an import, or null when nothing changed.
 */
@Composable
fun OrderImportFlow(
    db: InventoryDb,
    onClose: (String?) -> Unit,
    defaultBin: String = "",
    knownBins: List<String> = emptyList(),
) {
    val context = LocalContext.current
    var order by remember { mutableStateOf<OrderCsv.Order?>(null) }
    var readError by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            onClose(null)
            return@rememberLauncherForActivityResult
        }
        try {
            val o = OrderCsv.parse(readText(context, uri), displayName(context, uri))
            if (o.error != null) readError = o.error else order = o
        } catch (e: Exception) {
            readError = "Couldn't read the file: ${e.message}"
        }
    }
    LaunchedEffect(Unit) {
        pick.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
    }

    readError?.let { msg ->
        AlertDialog(
            onDismissRequest = { onClose(null) },
            title = { Text("Import order") },
            text = {
                Text(
                    msg + "\n\nExport the order as CSV (Mouser: Order History → export; DigiKey: order or cart → " +
                        "Download CSV). An Excel file can be saved as CSV from Excel or Google Sheets first.",
                )
            },
            confirmButton = { TextButton(onClick = { onClose(null) }) { Text("OK") } },
        )
    }

    order?.let { OrderLinesPage(db, it, defaultBin, knownBins, onClose) }
}

@Composable
private fun OrderLinesPage(
    db: InventoryDb, order: OrderCsv.Order, defaultBin: String, knownBins: List<String>, onClose: (String?) -> Unit,
) {
    // Which lines are in stock already and which bags were imported before, read on IO before showing the list.
    val first by produceState<Pair<List<Match?>, List<BagHit?>>?>(null) {
        value = withContext(Dispatchers.IO) {
            order.lines.map { db.find(it.mpn, it.customerPn, it.distributorPn) } to
                order.lines.map { db.findBag(it.fingerprint(order.distributor, order.orderNumber)) }
        }
    }
    Dialog(
        onDismissRequest = { onClose(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val loaded = first
            if (loaded == null) {
                Spacer(Modifier.fillMaxSize())
            } else {
                OrderLinesContent(db, order, loaded.first, loaded.second, defaultBin, knownBins, onClose)
            }
        }
    }
}

/** Which lines the page shows. */
private enum class LineFilter(val label: String) { ALL("All"), NEW("New"), RESTOCK("Restock"), IMPORTED("Already imported") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrderLinesContent(
    db: InventoryDb,
    order: OrderCsv.Order,
    matches: List<Match?>,
    firstSeen: List<BagHit?>,
    defaultBin: String,
    knownBins: List<String>,
    onClose: (String?) -> Unit,
) {
    var orderNo by remember { mutableStateOf(order.orderNumber) }
    val seen by produceState(firstSeen, orderNo) {
        value = if (orderNo == order.orderNumber) firstSeen
        else withContext(Dispatchers.IO) { order.lines.map { db.findBag(it.fingerprint(order.distributor, orderNo)) } }
    }
    // Lines already imported start unticked; ticking one adds it again on purpose.
    val checked = remember { mutableStateListOf<Boolean>().apply { addAll(firstSeen.map { it == null }) } }
    // One bin per line: new parts default to the last bin used, restocks to the part's own bin.
    val bins = remember {
        mutableStateListOf<String>().apply {
            addAll(order.lines.indices.map { i -> matches[i]?.row?.location?.takeIf { it.isNotBlank() } ?: defaultBin })
        }
    }
    var filter by remember { mutableStateOf(LineFilter.ALL) }
    var binFor by remember { mutableStateOf<Int?>(null) } // line index, or -1 for "all shown lines"
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val statuses = order.lines.indices.map { i -> OrderLines.status(matches[i] != null, seen[i] != null) }
    val shown = order.lines.indices.filter { i ->
        when (filter) {
            LineFilter.ALL -> true
            LineFilter.NEW -> statuses[i] == OrderLines.Status.NEW
            LineFilter.RESTOCK -> statuses[i] == OrderLines.Status.RESTOCK
            LineFilter.IMPORTED -> statuses[i] == OrderLines.Status.IMPORTED
        }
    }
    val count = checked.count { it }
    val total = OrderLines.total(order.lines, checked.toBooleanArray())
    val hasPrices = order.lines.any { it.unitPrice != null }

    fun doImport() {
        val no = orderNo.trim()
        val label = "${order.distributor} order" + if (no.isNotEmpty()) " $no" else ""
        val entries = order.lines.withIndex().filter { (i, _) -> checked[i] }.map { (i, l) ->
            ScanEntry(
                mpn = l.mpn,
                description = l.description,
                manufacturer = l.manufacturer,
                quantity = l.quantity,
                addMode = true,
                location = bins[i].trim(),
                distributor = order.distributor,
                distributorPn = l.distributorPn,
                customerPn = l.customerPn,
                source = label,
                notes = if (no.isNotEmpty()) "Order $no" else "",
                dateCode = "",
                refDes = "",
                raw = "$label, line ${l.line}",
                unitCost = l.unitPrice,
                productUrl = Links.productUrl(order.distributor, l.distributorPn, l.mpn).orEmpty(),
                category = Categories.classify(l.description),
                bagFingerprint = l.fingerprint(order.distributor, no),
            )
        }
        try {
            val newParts = order.lines.indices.count { checked[it] && matches[it] == null }
            db.saveAll(entries)
            onClose("Added ${entries.size} line${if (entries.size == 1) "" else "s"} from $label ($newParts new part${if (newParts == 1) "" else "s"})")
        } catch (e: Exception) {
            error = "Import failed, nothing was changed: ${e.message}"
            saving = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${order.distributor} order") },
                navigationIcon = {
                    IconButton(onClick = { onClose(null) }) { Icon(Icons.Filled.Close, contentDescription = "Cancel import") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (hasPrices) Text(OrderLines.money(total), style = MaterialTheme.typography.titleMedium)
                            Text(
                                "$count of ${OrderLines.lines(order.lines.size)} ticked",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = {
                                saving = true
                                doImport()
                            },
                            enabled = count > 0 && !saving,
                        ) { Text("Add ${OrderLines.lines(count)}") }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "order-no") {
                OutlinedTextField(
                    orderNo,
                    { orderNo = it },
                    label = { Text("Order number") },
                    supportingText = { Text("Used to recognise this order if it's imported again") },
                    singleLine = true,
                    keyboardOptions = codeKeys(last = true),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "summary") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatChip(OrderLines.lines(order.lines.size), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                    StatChip(
                        "${OrderLines.count(statuses, OrderLines.Status.NEW)} new",
                        MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    StatChip(
                        "${OrderLines.count(statuses, OrderLines.Status.RESTOCK)} restock" +
                            if (OrderLines.count(statuses, OrderLines.Status.RESTOCK) == 1) "" else "s",
                        MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    val again = OrderLines.count(statuses, OrderLines.Status.IMPORTED)
                    if (again > 0) {
                        StatChip("$again already imported", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    }
                    if (hasPrices) {
                        StatChip(
                            "Total ${OrderLines.money(OrderLines.total(order.lines, null))}",
                            MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            item(key = "filters") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LineFilter.entries.forEach { f ->
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { shown.forEach { checked[it] = true } }) { Text("Select all") }
                        TextButton(onClick = { shown.forEach { checked[it] = false } }) { Text("None") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { binFor = -1 }) { Text("Bin for all…") }
                    }
                }
            }
            if (shown.isEmpty()) {
                item(key = "none") {
                    Text(
                        "No lines match this filter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            items(shown, key = { it }) { i ->
                OrderLineCard(
                    line = order.lines[i],
                    status = statuses[i],
                    match = matches[i],
                    seen = seen[i],
                    checked = checked[i],
                    bin = bins[i],
                    onChecked = { checked[i] = it },
                    onBin = { binFor = i },
                )
            }
        }
    }

    binFor?.let { target ->
        val start = if (target >= 0) bins[target] else shown.firstOrNull()?.let { bins[it] } ?: defaultBin
        OrderBinDialog(
            title = if (target >= 0) "Bin for ${order.lines[target].mpn.ifBlank { order.lines[target].distributorPn }}"
            else "Bin for the ${OrderLines.lines(shown.size)} shown",
            initial = start,
            knownBins = knownBins,
            onDismiss = { binFor = null },
            onPick = { b ->
                if (target >= 0) bins[target] = b else shown.forEach { bins[it] = b }
                binFor = null
            },
        )
    }
}

/** A small colored summary chip ("12 new"). */
@Composable
private fun StatChip(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

/** One order line: tick, part numbers, quantity × price, status chip and its bin. */
@Composable
private fun OrderLineCard(
    line: OrderCsv.Line,
    status: OrderLines.Status,
    match: Match?,
    seen: BagHit?,
    checked: Boolean,
    bin: String,
    onChecked: (Boolean) -> Unit,
    onBin: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChecked)
                .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(top = 4.dp, start = 8.dp, end = 8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    line.mpn.ifBlank { line.distributorPn },
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (line.mpn.isNotBlank() && line.distributorPn.isNotBlank()) {
                    Text(line.distributorPn, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                if (line.description.isNotBlank()) {
                    Text(
                        line.description, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Qty ${line.quantity}" + (line.unitPrice?.let { " × \$${InventoryDb.fmtCost(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val (text, bg, fg) = when (status) {
                        OrderLines.Status.NEW -> Triple("New", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                        OrderLines.Status.RESTOCK -> Triple(
                            "In stock ${match?.row?.quantity ?: 0} → ${(match?.row?.quantity ?: 0) + line.quantity}",
                            MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        OrderLines.Status.IMPORTED -> Triple(
                            "Imported ${seen?.let { shortDay(it.timestamp) }.orEmpty()}".trim() +
                                if (checked) " · adds again" else "",
                            MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    StatChip(text, bg, fg)
                }
            }
            Spacer(Modifier.width(8.dp))
            AssistChip(
                onClick = onBin,
                label = { Text(if (bin.isBlank()) "Bin" else Locations.normalize(bin), maxLines = 1) },
                leadingIcon = { Icon(Icons.Filled.Inventory2, contentDescription = null) },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Type a bin or pick one you already use. */
@Composable
private fun OrderBinDialog(
    title: String, initial: String, knownBins: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BinPicker(
                    text, { text = it }, label = "Bin", placeholder = "e.g. A3",
                    bins = binsOrNames(knownBins, LocalBins.current), last = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.noteBin(text)
                onPick(text.trim())
            }) { Text("Use") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "2026-10-17 14:03:11" → "Oct 17", like the duplicate-bag warning; anything else is shown as it is. */
private fun shortDay(ts: String): String = try {
    java.time.LocalDateTime.parse(ts.replace(' ', 'T'))
        .format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US))
} catch (_: Exception) {
    ts
}

internal fun readText(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("empty file")
    // Excel's "Unicode text" saves UTF-16 with a byte-order mark; everything else is read as UTF-8.
    return if (bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
            (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
    ) String(bytes, Charsets.UTF_16) else String(bytes, Charsets.UTF_8)
}

internal fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (e: Exception) {
    null
}
