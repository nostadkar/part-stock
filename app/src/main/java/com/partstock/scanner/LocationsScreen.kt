package com.partstock.scanner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.Locations
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.ui.text.style.TextOverflow
import com.partstock.scanner.parse.BinStats
import com.partstock.scanner.parse.ChartData
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.IconToggleButton

/**
 * Every bin with how many parts and pieces it holds, a fill bar (pieces vs. the fullest bin), its top categories and
 * a few thumbnails; search and sort at the top. Tap a bin to see its parts; the pencil renames it everywhere.
 * The Grid view (#59) draws bins named like drawers (A3) as a cabinet; see [LocationsGrid].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationsScreen(
    vm: InventoryViewModel,
    prefs: Prefs,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onScan: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Loaded on IO by the ViewModel and refreshed after every change.
    val data by vm.data.collectAsStateWithLifecycle()
    val bins = data.locations
    // Fill, top categories and thumbnails per bin, searched and sorted off the main thread.
    val view by vm.locations.collectAsStateWithLifecycle()
    val sort by vm.binSort.collectAsStateWithLifecycle()
    val query by vm.binQuery.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<LocationSummary?>(null) }
    var labelMenu by remember { mutableStateOf<String?>(null) }
    var labels by remember { mutableStateOf<List<LabelItem>?>(null) }
    var gridView by remember { mutableStateOf(prefs.locationsView == "GRID") }
    var shade by remember { mutableStateOf(BinStats.Shade.parse(prefs.locationsShade)) }
    val actions = remember(data) {
        BinActions(
            open = onOpen,
            rename = { renaming = it },
            binLabel = { labels = listOf(binLabel(it.name, it.parts)) },
            partLabels = { b -> labels = data.parts.filter { Locations.keyOf(it.bins(), b.name) != null }.map(::partLabel) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Locations") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (data.loaded && bins.isEmpty()) {
                EmptyState(
                    icon = { Icon(Icons.Filled.Inventory2, contentDescription = null, modifier = Modifier.size(72.dp)) },
                    title = "No bins yet",
                    text = "Scan a part and pick a bin when you save it. Bins show up here with what's in them.",
                    buttons = { if (onScan != null) Button(onClick = onScan) { Text("Scan a label") } },
                )
                return@Column
            }
            if (bins.size > 1) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.binQuery.value = it },
                    placeholder = { Text("Find a bin") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { vm.binQuery.value = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // The grid isn't sorted: there the chips pick what shades the cells.
                    if (gridView) {
                        items(BinStats.Shade.entries.toList(), key = { it.name }) { s ->
                            FilterChip(
                                selected = shade == s,
                                onClick = {
                                    shade = s
                                    prefs.locationsShade = s.name
                                },
                                label = { Text("Shade: ${s.title.lowercase()}") },
                            )
                        }
                    } else {
                        items(BinStats.Sort.entries.toList(), key = { it.name }) { s ->
                            FilterChip(selected = sort == s, onClick = { vm.binSort.value = s }, label = { Text(s.title) })
                        }
                    }
                }
            }
            Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (view.bins.size == view.total) "${view.total} bin${if (view.total == 1) "" else "s"} · " +
                        (if (gridView) "tap to open, hold for more" else "tap one to see what's in it")
                    else "${view.bins.size} of ${view.total} bins",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                listOf(false to "List view", true to "Grid view").forEach { (grid, label) ->
                    IconToggleButton(
                        checked = gridView == grid,
                        onCheckedChange = {
                            gridView = grid
                            prefs.locationsView = if (grid) "GRID" else "LIST"
                        },
                    ) {
                        Icon(
                            if (grid) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList,
                            contentDescription = label,
                            tint = if (gridView == grid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (view.loaded && view.bins.isEmpty() && bins.isNotEmpty()) {
                Text(
                    "No bin matches \"$query\".",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )
            }
            if (gridView) {
                LocationsGrid(view.bins, shade, actions, Modifier.weight(1f))
                return@Column
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                items(view.bins, key = { it.summary.name }) { bin ->
                    val b = bin.summary
                    Card(Modifier.fillMaxWidth().clickable { onOpen(b.name) }) {
                        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        b.name, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                                    )
                                    bin.topCategories.forEach { c ->
                                        Icon(
                                            categoryIcon(c), contentDescription = c,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(start = 6.dp).size(16.dp),
                                        )
                                    }
                                }
                                Text(
                                    "${b.parts} part${if (b.parts == 1) "" else "s"} · ${b.pieces} piece${if (b.pieces == 1) "" else "s"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                BarMeter(
                                    value = bin.fill.toDouble(),
                                    max = 1.0,
                                    contentDescription = "${b.pieces} pieces, ${ChartData.percent(bin.fill.toDouble())} of the fullest bin",
                                    height = 6.dp,
                                    animate = false,
                                    modifier = Modifier.padding(top = 6.dp, end = 4.dp),
                                )
                                if (bin.preview.isNotEmpty()) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 6.dp),
                                    ) {
                                        bin.preview.forEach { p -> PartImage(p, data.photos[p.id]?.firstOrNull(), 28.dp) }
                                        val more = BinStats.more(b.parts, bin.preview.size)
                                        if (more.isNotEmpty()) {
                                            Text(
                                                more, style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(start = 4.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            Box {
                                IconButton(onClick = { labelMenu = b.name }) {
                                    Icon(Icons.Filled.QrCode2, contentDescription = "Labels for ${b.name}")
                                }
                                DropdownMenu(expanded = labelMenu == b.name, onDismissRequest = { labelMenu = null }) {
                                    DropdownMenuItem(text = { Text("Bin label") }, onClick = {
                                        labelMenu = null
                                        labels = listOf(binLabel(b.name, b.parts))
                                    })
                                    DropdownMenuItem(text = { Text("Labels for its ${b.parts} part${if (b.parts == 1) "" else "s"}") }, onClick = {
                                        labelMenu = null
                                        labels = data.parts.filter { Locations.keyOf(it.bins(), b.name) != null }.map(::partLabel)
                                    })
                                }
                            }
                            IconButton(onClick = { renaming = b }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Rename ${b.name}")
                            }
                        }
                    }
                }
            }
        }
    }

    labels?.let { items -> LabelDialog(items, prefs) { labels = null } }

    renaming?.let { b ->
        var name by remember(b.name) { mutableStateOf(if (b.name == Locations.UNASSIGNED) "" else b.name) }
        val existing = bins.any { it.name != b.name && Locations.same(it.name, name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename ${b.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Picking an existing bin here is a merge (said below), so the picker offers them too.
                    BinPicker(
                        name, { name = it }, label = "New name",
                        bins = bins,
                        exclude = b.name, showRecent = false, last = true,
                    )
                    Text(
                        if (existing) "That bin exists already: the parts are merged into it and counts added."
                        else "Renames the bin on all ${b.parts} part${if (b.parts == 1) "" else "s"}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && Locations.normalize(name) != b.name,
                    onClick = {
                        val to = name.trim()
                        renaming = null
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { vm.db.renameLocation(b.name, to) }
                            if (Locations.same(prefs.lastLocation, b.name)) prefs.lastLocation = Locations.plain(to)
                            Feedback.success(context, "Renamed on $n part${if (n == 1) "" else "s"}")
                        }
                    },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}
