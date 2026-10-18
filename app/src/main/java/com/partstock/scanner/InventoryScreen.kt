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
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.Links

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(db: InventoryDb, onBack: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) } // bump to reload after edits
    val parts = remember(version) { db.all() }
    var query by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PartRow?>(null) }
    var pendingCsv by remember { mutableStateOf("") }
    var bucket by remember { mutableStateOf<String?>(null) } // null = all categories
    var buildingKit by remember { mutableStateOf(false) }
    var lowOnly by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                Export.writeTo(context, uri, pendingCsv)
                toast("Saved")
            } catch (e: Exception) {
                toast("Save failed: ${e.message}")
            }
        }
    }

    val counts = remember(parts) { parts.groupingBy { it.category }.eachCount() }
    val lowCount = remember(parts) { parts.count { it.isLow() } }
    val tracked = remember(parts) { parts.any { it.minQty != null } }
    val shown = remember(parts, query, bucket, lowOnly) {
        val q = InventoryDb.norm(query)
        parts.filter { p ->
            (bucket == null || p.category == bucket) && (!lowOnly || p.isLow()) &&
                (q.isEmpty() || listOf(p.mpn, p.description, p.manufacturer, p.location, p.distributorPn, p.customerPn, p.category)
                    .any { InventoryDb.norm(it).contains(q) })
        }
    }
    val groups = remember(shown) {
        val order = com.partstock.scanner.parse.Categories.ALL
        shown.groupBy { it.category }.toList().sortedBy { (c, _) -> order.indexOf(c).let { if (it < 0) 999 else it } }
    }
    val pieces = parts.sumOf { it.quantity }
    val value = parts.sumOf { (it.unitCost ?: 0.0) * it.quantity }
    val unpriced = parts.count { it.unitCost == null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inventory") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { buildingKit = true }) { Icon(Icons.Filled.Build, contentDescription = "Build kit") }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.Share, contentDescription = "Export") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Share inventory CSV") }, onClick = {
                            menuOpen = false
                            Export.share(context, Export.fileName("inventory"), db.inventoryCsv())
                        })
                        DropdownMenuItem(text = { Text("Save inventory CSV to file…") }, onClick = {
                            menuOpen = false
                            pendingCsv = db.inventoryCsv()
                            saveFile.launch(Export.fileName("inventory"))
                        })
                        DropdownMenuItem(text = { Text("Share reorder list CSV") }, onClick = {
                            menuOpen = false
                            if (db.reorderCount() == 0) toast("Nothing is below its minimum")
                            else Export.share(context, Export.fileName("reorder"), db.reorderCsv())
                        })
                        DropdownMenuItem(text = { Text("Save reorder list CSV to file…") }, onClick = {
                            menuOpen = false
                            if (db.reorderCount() == 0) toast("Nothing is below its minimum")
                            else {
                                pendingCsv = db.reorderCsv()
                                saveFile.launch(Export.fileName("reorder"))
                            }
                        })
                        DropdownMenuItem(text = { Text("Share scan log CSV") }, onClick = {
                            menuOpen = false
                            Export.share(context, Export.fileName("scan-log"), db.logCsv())
                        })
                        DropdownMenuItem(text = { Text("Save scan log CSV to file…") }, onClick = {
                            menuOpen = false
                            pendingCsv = db.logCsv()
                            saveFile.launch(Export.fileName("scan-log"))
                        })
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search part, description, bin") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (counts.size > 1 || tracked) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = bucket == null && !lowOnly,
                        onClick = {
                            bucket = null
                            lowOnly = false
                        },
                        label = { Text("All") },
                    )
                    if (tracked) {
                        FilterChip(
                            selected = lowOnly,
                            onClick = { lowOnly = !lowOnly },
                            label = { Text("Low stock ($lowCount)") },
                        )
                    }
                    com.partstock.scanner.parse.Categories.ALL.filter { counts.containsKey(it) }.forEach { c ->
                        FilterChip(
                            selected = bucket == c,
                            onClick = { bucket = if (bucket == c) null else c },
                            label = { Text("$c (${counts[c]})") },
                        )
                    }
                }
            }
            val summary = buildString {
                append("${parts.size} parts · $pieces pieces")
                if (parts.size > unpriced) append(" · \$${InventoryDb.fmtMoney(value)} value")
                if (unpriced > 0 && parts.size > unpriced) append(" ($unpriced unpriced)")
                if (lowCount > 0) append(" · $lowCount low")
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (parts.isEmpty()) {
                Text("Nothing yet. Scan a label to add your first part.", modifier = Modifier.padding(top = 24.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { (cat, rows) ->
                item(key = "h-$cat") {
                    Text(
                        "$cat · ${rows.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(rows, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth().clickable { editing = p }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    p.mpn.ifBlank { p.customerPn.ifBlank { p.distributorPn } },
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodySmall)
                                val meta = listOf(p.manufacturer, if (p.location.isNotBlank()) "Bin ${p.location}" else "")
                                    .filter { it.isNotBlank() }.joinToString(" · ")
                                if (meta.isNotBlank()) {
                                    Text(meta, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Column(Modifier.padding(start = 12.dp), horizontalAlignment = Alignment.End) {
                                Text(
                                    p.quantity.toString(), style = MaterialTheme.typography.titleLarge,
                                    color = if (p.isLow()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                                p.minQty?.let { m ->
                                    Text(
                                        "min $m", style = MaterialTheme.typography.bodySmall,
                                        color = if (p.isLow()) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                p.unitCost?.let { c ->
                                    Text(
                                        "\$${InventoryDb.fmtCost(c)} ea",
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

    editing?.let { p ->
        EditDialog(
            part = p,
            onDismiss = { editing = null },
            onSave = { q, loc, desc, cost, cat, use, min ->
                db.update(p.id, q, loc, desc, cost, cat, min)
                if (use > 0) {
                    val left = db.use(p.id, use)
                    toast("Used $use · $left left")
                }
                editing = null
                version++
            },
            onDelete = {
                db.delete(p.id)
                editing = null
                version++
                toast("Deleted ${p.mpn.ifBlank { p.customerPn }}")
            },
        )
    }

    if (buildingKit) {
        BuildKitDialog(
            db = db,
            onDismiss = { buildingKit = false },
            onBuilt = { msg ->
                buildingKit = false
                version++
                toast(msg)
            },
        )
    }
}

/** Pick a kit, enter how many boards, check the list, then subtract everything at once. */
@Composable
private fun BuildKitDialog(db: InventoryDb, onDismiss: () -> Unit, onBuilt: (String) -> Unit) {
    val kits = remember { db.kits() }
    var kit by remember { mutableStateOf(kits.firstOrNull()?.kit) }
    var boardsText by remember { mutableStateOf("1") }
    var confirmShort by remember { mutableStateOf(false) }
    val boards = boardsText.toIntOrNull() ?: 0
    val plan = remember(kit, boards) { kit?.let { if (boards > 0) db.kitPlan(it, boards) else emptyList() }.orEmpty() }
    val short = plan.filter { it.after < 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Build kit") },
        text = {
            if (kits.isEmpty()) {
                Text(
                    "No kits yet. Save a bag with its Entrust kit label and the app remembers which kit it belongs to, " +
                        "how many go on each board and the ref des.",
                )
            } else Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                kits.forEach { k ->
                    FilterChip(
                        selected = kit == k.kit,
                        onClick = {
                            kit = k.kit
                            confirmShort = false
                        },
                        label = {
                            Text("${k.kit} · ${k.parts} part${if (k.parts == 1) "" else "s"}" +
                                (k.kitQuantity?.let { " · label qty $it" } ?: ""))
                        },
                    )
                }
                OutlinedTextField(
                    boardsText,
                    { v ->
                        boardsText = v.filter { it.isDigit() }.take(5)
                        confirmShort = false
                    },
                    label = { Text("Boards") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                plan.forEach { line ->
                    val p = line.part
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.mpn.ifBlank { p.customerPn.ifBlank { p.distributorPn } },
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${line.perBoard} × $boards = ${line.need}" +
                                    (if (line.refDes.isNotBlank()) " · ${line.refDes}" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        Text(
                            "${p.quantity} → ${line.after}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (line.after < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                if (short.isNotEmpty()) {
                    Text(
                        "${short.size} part${if (short.size == 1) "" else "s"} would go below 0; " +
                            "they'll be set to 0. Recount those bags.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (kits.isNotEmpty()) {
                TextButton(
                    enabled = kit != null && boards > 0 && plan.isNotEmpty(),
                    onClick = {
                        val k = kit
                        if (k != null) {
                            if (short.isNotEmpty() && !confirmShort) {
                                confirmShort = true
                            } else {
                                db.buildKit(k, boards)
                                onBuilt("Used parts for $boards board${if (boards == 1) "" else "s"} of $k")
                            }
                        }
                    },
                ) {
                    Text(if (confirmShort) "Tap again to use anyway" else "Use parts")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (kits.isEmpty()) "OK" else "Cancel") } },
    )
}

@Composable
private fun EditDialog(
    part: PartRow,
    onDismiss: () -> Unit,
    onSave: (Int, String, String, Double?, String, Int, Int?) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var qty by remember { mutableStateOf(part.quantity.toString()) }
    var location by remember { mutableStateOf(part.location) }
    var description by remember { mutableStateOf(part.description) }
    var cost by remember { mutableStateOf(part.unitCost?.let { InventoryDb.fmtCost(it) }.orEmpty()) }
    var category by remember { mutableStateOf(part.category) }
    var minimum by remember { mutableStateOf(part.minQty?.toString().orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var use by remember { mutableIntStateOf(0) }
    var confirmUse by remember { mutableStateOf(false) }
    val link = part.link()
    val stock = qty.toIntOrNull() ?: part.quantity

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
                if (link.isNotBlank()) {
                    TextButton(onClick = { openLink(context, link) }) { Text("Open on ${Links.siteName(link)}") }
                }
                OutlinedTextField(description, { description = it }, label = { Text("Description") })
                CategoryPicker(category) { category = it }
                OutlinedTextField(
                    qty, { v -> qty = v.filter { it.isDigit() } }, label = { Text("Quantity") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
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
                )
                OutlinedTextField(location, { location = it }, label = { Text("Bin / location") }, singleLine = true)
                if (part.notes.isNotBlank()) Text("Notes: ${part.notes}", style = MaterialTheme.typography.bodySmall)
                Text("Scanned ${part.scans}× · updated ${part.updatedAt}", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (use > stock && !confirmUse) confirmUse = true
                else onSave(
                    stock, location, description, cost.toDoubleOrNull(), category, use,
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
}
