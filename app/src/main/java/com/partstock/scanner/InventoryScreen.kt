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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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

    val shown = remember(parts, query) {
        val q = InventoryDb.norm(query)
        if (q.isEmpty()) parts else parts.filter { p ->
            listOf(p.mpn, p.description, p.manufacturer, p.location, p.distributorPn, p.customerPn)
                .any { InventoryDb.norm(it).contains(q) }
        }
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
            val summary = buildString {
                append("${parts.size} parts · $pieces pieces")
                if (parts.size > unpriced) append(" · \$${InventoryDb.fmtMoney(value)} value")
                if (unpriced > 0 && parts.size > unpriced) append(" ($unpriced unpriced)")
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
                items(shown, key = { it.id }) { p ->
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
                                Text(p.quantity.toString(), style = MaterialTheme.typography.titleLarge)
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

    editing?.let { p ->
        EditDialog(
            part = p,
            onDismiss = { editing = null },
            onSave = { q, loc, desc, cost ->
                db.update(p.id, q, loc, desc, cost)
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
}

@Composable
private fun EditDialog(
    part: PartRow,
    onDismiss: () -> Unit,
    onSave: (Int, String, String, Double?) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var qty by remember { mutableStateOf(part.quantity.toString()) }
    var location by remember { mutableStateOf(part.location) }
    var description by remember { mutableStateOf(part.description) }
    var cost by remember { mutableStateOf(part.unitCost?.let { InventoryDb.fmtCost(it) }.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val link = part.link()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(part.mpn.ifBlank { part.customerPn.ifBlank { part.distributorPn } }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val ids = listOf(
                    if (part.distributorPn.isNotBlank()) "${part.distributor.ifBlank { "Dist" }}: ${part.distributorPn}" else "",
                    if (part.customerPn.isNotBlank()) "Cust: ${part.customerPn}" else "",
                ).filter { it.isNotBlank() }.joinToString(" · ")
                if (ids.isNotBlank()) Text(ids, style = MaterialTheme.typography.bodySmall)
                if (link.isNotBlank()) {
                    TextButton(onClick = { openLink(context, link) }) { Text("Open on ${Links.siteName(link)}") }
                }
                OutlinedTextField(description, { description = it }, label = { Text("Description") })
                OutlinedTextField(
                    qty, { v -> qty = v.filter { it.isDigit() } }, label = { Text("Quantity") }, singleLine = true,
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
                onSave(qty.toIntOrNull() ?: part.quantity, location, description, cost.toDoubleOrNull())
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                Text(if (confirmDelete) "Tap again to delete" else "Delete", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
