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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    vm: InventoryViewModel,
    secrets: SecretStore,
    onBack: () -> Unit,
    onProjects: () -> Unit = {},
    onLocations: () -> Unit = {},
    /** Show only parts with stock in this bin (picked on the Locations screen); null = all. */
    bin: String? = null,
    onClearBin: () -> Unit = {},
    prefs: Prefs? = null,
    /** Open this part's dialog straight away (a part label was scanned). */
    openPart: Long? = null,
) {
    val context = LocalContext.current
    val db = vm.db
    // Parts, photos and filters come from the ViewModel, loaded and filtered off the main thread.
    remember(bin) { vm.bin.value = bin }
    DisposableEffect(Unit) { onDispose { vm.clearFilters() } }
    val view by vm.view.collectAsStateWithLifecycle()
    val parts = view.parts
    val photos = view.photos
    val bucket by vm.bucket.collectAsStateWithLifecycle()
    val lowOnly by vm.lowOnly.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(vm.query.value) }
    var menuOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf(openPart) }
    // Follows the latest copy of the part, and closes when it's gone (e.g. undone).
    val data by vm.data.collectAsStateWithLifecycle()
    val editing = editingId?.let { id -> data.parts.firstOrNull { it.id == id } }
    LaunchedEffect(data.loaded, editing == null) {
        if (data.loaded && editingId != null && editing == null) editingId = null
    }
    var labels by remember { mutableStateOf<List<LabelItem>?>(null) }
    var history by remember { mutableStateOf<PartRow?>(null) }
    var pendingCsv by remember { mutableStateOf("") }
    var buildingKit by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var importingOrder by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    /** Builds a CSV on IO, then hands it over on the main thread. */
    fun withCsv(build: () -> String, then: (String) -> Unit) {
        scope.launch {
            val csv = try {
                withContext(Dispatchers.IO) { build() }
            } catch (e: Exception) {
                toast("Export failed: ${e.message}")
                return@launch
            }
            then(csv)
        }
    }

    var zipping by remember { mutableStateOf(false) }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            zipping = true
            scope.launch {
                try {
                    val n = withContext(Dispatchers.IO) { Export.saveZip(context, uri, db) }
                    toast("Saved with $n photo${if (n == 1) "" else "s"}")
                } catch (e: Exception) {
                    toast("Save failed: ${e.message}")
                }
                zipping = false
            }
        }
    }

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

    val counts = view.counts
    val lowCount = view.lowCount
    val tracked = view.tracked
    val shown = view.shown
    val groups = view.groups
    val pieces = view.pieces
    val value = view.value
    val unpriced = view.unpriced

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inventory") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { refreshing = true }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh prices")
                    }
                    IconButton(onClick = onLocations) {
                        Icon(Icons.Filled.Inventory2, contentDescription = "Locations")
                    }
                    IconButton(onClick = onProjects) {
                        Icon(Icons.Filled.DeveloperBoard, contentDescription = "Projects / BOMs")
                    }
                    IconButton(onClick = { buildingKit = true }) { Icon(Icons.Filled.Build, contentDescription = "Build kit") }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.Share, contentDescription = "Import and export")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Import Mouser / DigiKey order CSV…") }, onClick = {
                            menuOpen = false
                            importingOrder = true
                        })
                        DropdownMenuItem(text = { Text("Share inventory CSV") }, onClick = {
                            menuOpen = false
                            withCsv(db::inventoryCsv) { Export.share(context, Export.fileName("inventory"), it) }
                        })
                        DropdownMenuItem(text = { Text("Save inventory CSV to file…") }, onClick = {
                            menuOpen = false
                            withCsv(db::inventoryCsv) {
                                pendingCsv = it
                                saveFile.launch(Export.fileName("inventory"))
                            }
                        })
                        DropdownMenuItem(text = { Text("Share inventory + photos (zip)") }, enabled = !zipping, onClick = {
                            menuOpen = false
                            zipping = true
                            scope.launch {
                                try {
                                    val chooser = withContext(Dispatchers.IO) { Export.shareZip(context, db) }
                                    context.startActivity(chooser)
                                } catch (e: Exception) {
                                    toast("Export failed: ${e.message}")
                                }
                                zipping = false
                            }
                        })
                        DropdownMenuItem(text = { Text("Save inventory + photos (zip) to file…") }, enabled = !zipping, onClick = {
                            menuOpen = false
                            saveZip.launch(Export.zipName())
                        })
                        if (prefs != null) {
                            DropdownMenuItem(text = { Text("Make QR labels for the ${shown.size} shown parts…") }, onClick = {
                                menuOpen = false
                                labels = shown.map(::partLabel)
                            })
                        }
                        DropdownMenuItem(text = { Text("Share reorder list CSV") }, onClick = {
                            menuOpen = false
                            withCsv({ if (db.reorderCount() == 0) "" else db.reorderCsv() }) { csv ->
                                if (csv.isEmpty()) toast("Nothing is below its minimum")
                                else Export.share(context, Export.fileName("reorder"), csv)
                            }
                        })
                        DropdownMenuItem(text = { Text("Save reorder list CSV to file…") }, onClick = {
                            menuOpen = false
                            withCsv({ if (db.reorderCount() == 0) "" else db.reorderCsv() }) { csv ->
                                if (csv.isEmpty()) toast("Nothing is below its minimum")
                                else {
                                    pendingCsv = csv
                                    saveFile.launch(Export.fileName("reorder"))
                                }
                            }
                        })
                        DropdownMenuItem(text = { Text("Share scan log CSV") }, onClick = {
                            menuOpen = false
                            withCsv(db::logCsv) { Export.share(context, Export.fileName("scan-log"), it) }
                        })
                        DropdownMenuItem(text = { Text("Save scan log CSV to file…") }, onClick = {
                            menuOpen = false
                            withCsv(db::logCsv) {
                                pendingCsv = it
                                saveFile.launch(Export.fileName("scan-log"))
                            }
                        })
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    vm.query.value = it // filtered (debounced) in the ViewModel
                },
                placeholder = { Text("Search part, value, package, bin") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (bin != null) {
                FilterChip(
                    selected = true,
                    onClick = onClearBin,
                    label = { Text("Bin ${bin} · ${shown.size} part${if (shown.size == 1) "" else "s"} ✕") },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (counts.size > 1 || tracked) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = bucket == null && !lowOnly,
                        onClick = {
                            vm.bucket.value = null
                            vm.lowOnly.value = false
                        },
                        label = { Text("All") },
                    )
                    if (tracked) {
                        FilterChip(
                            selected = lowOnly,
                            onClick = { vm.lowOnly.value = !lowOnly },
                            label = { Text("Low stock ($lowCount)") },
                        )
                    }
                    com.partstock.scanner.parse.Categories.ALL.filter { counts.containsKey(it) }.forEach { c ->
                        FilterChip(
                            selected = bucket == c,
                            onClick = { vm.bucket.value = if (bucket == c) null else c },
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
            if (!view.loaded) {
                Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (parts.isEmpty()) {
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
                    Card(Modifier.fillMaxWidth().clickable { editingId = p.id }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            photos[p.id]?.firstOrNull()?.let { f ->
                                PhotoThumb(f, 48.dp, Modifier.padding(end = 12.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    p.mpn.ifBlank { p.customerPn.ifBlank { p.distributorPn } },
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodySmall)
                                val chips = p.specs().chips()
                                if (chips.isNotEmpty()) SpecChips(chips, Modifier.padding(top = 4.dp))
                                val bins = p.bins()
                                val where = Locations.summary(bins).let { w ->
                                    if (w.isNotBlank() && bins.size == 1) "Bin $w" else w
                                }
                                val meta = listOf(p.manufacturer, where).filter { it.isNotBlank() }.joinToString(" · ")
                                if (meta.isNotBlank()) {
                                    Text(meta, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            val sheet = p.datasheet()
                            if (sheet.isNotBlank()) {
                                IconButton(onClick = { openLink(context, sheet) }) {
                                    Icon(
                                        Icons.Filled.Description,
                                        contentDescription = if (Links.isDatasheetSearch(sheet)) "Find datasheet" else "Datasheet",
                                        tint = if (Links.isDatasheetSearch(sheet)) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Column(Modifier.padding(start = 4.dp), horizontalAlignment = Alignment.End) {
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
            onDismiss = { editingId = null },
            onSave = { stock, loc, desc, cost, cat, use, min ->
                editingId = null
                scope.launch {
                    // The edit and the use are one change for Undo.
                    val left = withContext(Dispatchers.IO) {
                        db.grouped {
                            db.update(p.id, stock, loc, desc, cost, cat, min)
                            if (use > 0) db.use(p.id, use) else null
                        }
                    }
                    UndoOffer.offer(context, if (use > 0) "Used $use · $left left" else "Saved ${p.key()}", db.lastChange)
                }
            },
            onDelete = {
                editingId = null
                scope.launch {
                    withContext(Dispatchers.IO) { db.delete(p.id) }
                    UndoOffer.offer(context, "Deleted ${p.key()}", db.lastChange)
                }
            },
            onHistory = { history = p },
            photos = { PartPhotosRow(db, p.id) },
            knownBins = data.locations.map { it.name },
            onLabel = if (prefs != null) ({ labels = listOf(partLabel(p)) }) else null,
            onMove = { from, to, n ->
                editingId = null
                scope.launch {
                    val moved = withContext(Dispatchers.IO) { db.move(p.id, from, to, n) } ?: 0
                    if (moved > 0) UndoOffer.offer(context, "Moved $moved from $from to ${Locations.normalize(to)}", db.lastChange)
                    else toast("Nothing to move")
                }
            },
        )
    }

    history?.let { p -> HistoryDialog(db, p) { history = null } }

    labels?.let { items ->
        if (prefs != null) LabelDialog(items, prefs) { labels = null }
    }

    if (refreshing) {
        RefreshPricesDialog(
            db = db,
            secrets = secrets,
            parts = parts,
            onClose = { refreshing = false },
        )
    }

    if (importingOrder) {
        OrderImportFlow(
            db = db,
            onClose = { msg ->
                importingOrder = false
                if (msg != null) UndoOffer.offer(context, msg, db.lastChange)
            },
        )
    }

    if (buildingKit) {
        BuildKitDialog(
            db = db,
            onDismiss = { buildingKit = false },
            onBuilt = { msg ->
                buildingKit = false
                UndoOffer.offer(context, msg, db.lastChange)
            },
        )
    }
}
