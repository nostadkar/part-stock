package com.partstock.scanner

import android.widget.Toast
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

/** Every bin with how many parts and pieces it holds. Tap a bin to see its parts; the pencil renames it everywhere. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationsScreen(vm: InventoryViewModel, prefs: Prefs, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Loaded on IO by the ViewModel and refreshed after every change.
    val data by vm.data.collectAsStateWithLifecycle()
    val bins = data.locations
    var renaming by remember { mutableStateOf<LocationSummary?>(null) }
    var labelMenu by remember { mutableStateOf<String?>(null) }
    var labels by remember { mutableStateOf<List<LabelItem>?>(null) }

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
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text(
                "${bins.size} bin${if (bins.size == 1) "" else "s"} · tap one to see what's in it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (data.loaded && bins.isEmpty()) Text("No parts yet.", modifier = Modifier.padding(top = 24.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(bins, key = { it.name }) { b ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(b.name) }) {
                        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(b.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${b.parts} part${if (b.parts == 1) "" else "s"} · ${b.pieces} pieces",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
                    OutlinedTextField(name, { name = it }, label = { Text("New name") }, singleLine = true)
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
                            Toast.makeText(context, "Renamed on $n part${if (n == 1) "" else "s"}", Toast.LENGTH_LONG).show()
                        }
                    },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}
