package com.partstock.scanner

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.BomCsv
import com.partstock.scanner.parse.BuildPlan

/**
 * Projects: a board with a BOM (imported from a KiCad / generic BOM CSV, or from a scanned Entrust kit). Shows how
 * many boards the stock supports, the limiting parts and what's short for N boards, and *Build N* subtracts the parts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(db: InventoryDb, onBack: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var openId by remember { mutableStateOf<Long?>(null) }
    var pendingBom by remember { mutableStateOf<Pair<String, BomCsv.Bom>?>(null) }
    var bomError by remember { mutableStateOf<String?>(null) }
    var pickingKit by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    val pickBom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val bom = BomCsv.parse(readText(context, uri))
            if (bom.error != null) bomError = bom.error
            else pendingBom = (displayName(context, uri)?.substringBeforeLast('.') ?: "Project") to bom
        } catch (e: Exception) {
            bomError = "Couldn't read the file: ${e.message}"
        }
    }

    openId?.let { id ->
        BackHandler {
            openId = null
            version++
        }
        ProjectDetail(db, id, onBack = {
            openId = null
            version++
        })
        return
    }

    // Each project with how many boards the stock builds; read on IO (null while loading).
    val listed by produceState<List<Pair<ProjectSummary, BuildPlan.Result>>?>(null, version) {
        value = withContext(Dispatchers.IO) {
            val stock = db.planStock()
            db.projects().map { p -> p to BuildPlan.plan(db.projectLines(p.id).map { it.toReq() }, stock, 1) }
        }
    }
    val projects = listed.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        pickBom.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
                    }) { Icon(Icons.Filled.UploadFile, contentDescription = "Import BOM CSV") }
                    IconButton(onClick = { pickingKit = true }) {
                        Icon(Icons.Filled.Inventory2, contentDescription = "Project from a scanned kit")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (listed != null && projects.isEmpty()) {
                item {
                    Text(
                        "No projects yet. Import a BOM CSV (upload icon: KiCad's BOM export, or any CSV with MPN or " +
                            "value, quantity per board and reference columns), or make one from a scanned Entrust kit " +
                            "(box icon). Then see how many boards you can build and what to order.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(projects, key = { it.first.id }) { (p, plan) ->
                Card(Modifier.fillMaxWidth().clickable { openId = p.id }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${p.lines} line${if (p.lines == 1) "" else "s"} · " + buildableText(plan) +
                                (if (p.source.isNotBlank()) " · ${p.source}" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    bomError?.let { msg ->
        AlertDialog(
            onDismissRequest = { bomError = null },
            title = { Text("Import BOM") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { bomError = null }) { Text("OK") } },
        )
    }

    pendingBom?.let { (fileName, bom) ->
        var name by remember(bom) { mutableStateOf(fileName) }
        AlertDialog(
            onDismissRequest = { pendingBom = null },
            title = { Text("New project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${bom.lines.size} BOM line${if (bom.lines.size == 1) "" else "s"}, " +
                            "${bom.lines.sumOf { it.perBoard }} parts per board" +
                            (if (bom.dnp > 0) " (${bom.dnp} DNP line${if (bom.dnp == 1) "" else "s"} left out)" else ""),
                    )
                    OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = db.createProject(name, "BOM CSV", bom.lines)
                    pendingBom = null
                    openId = id
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { pendingBom = null }) { Text("Cancel") } },
        )
    }

    if (pickingKit) {
        val kitsOrNull by produceState<List<KitSummary>?>(null) { value = withContext(Dispatchers.IO) { db.kits() } }
        val kits = kitsOrNull.orEmpty()
        if (kitsOrNull != null) AlertDialog(
            onDismissRequest = { pickingKit = false },
            title = { Text("Project from a kit") },
            text = {
                if (kits.isEmpty()) {
                    Text("No kits yet. Save a bag with its Entrust kit label and the kit is remembered.")
                } else Column(Modifier.verticalScroll(rememberScrollState())) {
                    kits.forEach { k ->
                        TextButton(onClick = {
                            pickingKit = false
                            scope.launch {
                                val made = withContext(Dispatchers.IO) {
                                    val (lines, ids) = db.kitAsBom(k.kit)
                                    if (lines.isEmpty()) null else db.createProject(k.kit, "Kit", lines, ids)
                                }
                                if (made == null) toast("That kit has no parts in stock") else openId = made
                            }
                        }) { Text("${k.kit} · ${k.parts} part${if (k.parts == 1) "" else "s"}") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingKit = false }) { Text("Close") } },
        )
    }
}

private fun buildableText(plan: BuildPlan.Result): String = when {
    plan.unmatched > 0 -> "${plan.unmatched} unmatched"
    else -> "can build ${plan.buildable}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectDetail(db: InventoryDb, id: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    // Project, its lines and the stock, read on IO; null only before the first read.
    val detail by produceState<Triple<ProjectSummary?, List<ProjectLine>, List<BuildPlan.Stock>>?>(null, version) {
        value = withContext(Dispatchers.IO) { Triple(db.project(id), db.projectLines(id), db.planStock()) }
    }
    val loaded = detail ?: return
    val project = loaded.first
    if (project == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val lines = loaded.second
    val stock = loaded.third
    var boardsText by remember { mutableStateOf("1") }
    val boards = boardsText.toIntOrNull() ?: 0
    val reqs = remember(lines) { lines.map { it.toReq() } }
    val plan = remember(reqs, stock, boards) { BuildPlan.plan(reqs, stock, boards) }
    var picking by remember { mutableStateOf<ProjectLine?>(null) }
    var confirmBuild by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
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
    val csvName = Export.fileName("order-" + project.name.replace(Regex("[^A-Za-z0-9._-]+"), "-").take(40))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete project")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                val atOne = remember(reqs, stock) { BuildPlan.plan(reqs, stock, 1) }
                Text(
                    if (atOne.unmatched > 0) "Can't build yet: ${atOne.unmatched} line${if (atOne.unmatched == 1) " has" else "s have"} no match"
                    else "You can build ${atOne.buildable} board${if (atOne.buildable == 1) "" else "s"}",
                    style = MaterialTheme.typography.titleLarge,
                )
                if (atOne.limiting.isNotEmpty()) {
                    Text(
                        "Limited by: " + atOne.limiting.take(5).joinToString(", ") +
                            if (atOne.limiting.size > 5) " +${atOne.limiting.size - 5} more" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (atOne.unmatched > 0) {
                    Text(
                        "Tap a line marked \"No match\" to pick the part from stock.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        boardsText,
                        { v -> boardsText = v.filter { it.isDigit() }.take(5) },
                        label = { Text("Boards") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(120.dp),
                    )
                    Button(onClick = { confirmBuild = true }, enabled = boards > 0 && plan.use.isNotEmpty()) {
                        Text("Build $boards")
                    }
                }
            }
            item {
                if (boards > 0 && plan.shortages.isEmpty()) {
                    Text("Nothing short for $boards board${if (boards == 1) "" else "s"}.", style = MaterialTheme.typography.bodyMedium)
                } else if (boards > 0) {
                    Text("Short for $boards board${if (boards == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium)
                }
            }
            items(plan.shortages) { s ->
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(s.label, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "need ${s.need}, have ${s.onHand}, short ${s.missing}" + if (s.part == null) " · no match" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("order ${s.orderQty}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            if (plan.shortages.isNotEmpty()) {
                item {
                    Row {
                        TextButton(onClick = { Export.share(context, csvName, plan.reorderCsv()) }) {
                            Icon(Icons.Filled.Share, contentDescription = null)
                            Text(" Share order CSV")
                        }
                        TextButton(onClick = {
                            pendingCsv = plan.reorderCsv()
                            saveFile.launch(csvName)
                        }) { Text("Save…") }
                    }
                }
            }
            item {
                HorizontalDivider()
                Text("BOM · ${lines.size} line${if (lines.size == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp))
            }
            items(lines.indices.toList()) { i ->
                val line = lines[i]
                val r = plan.lines.getOrNull(i)
                Column(Modifier.fillMaxWidth().clickable { picking = line }.padding(vertical = 4.dp)) {
                    Text(r?.req?.label ?: line.mpn, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium)
                    if (line.refDes.isNotBlank()) {
                        Text(line.refDes, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    val part = r?.part
                    val on = r?.matchedOn
                    val match = when {
                        part == null -> "No match — tap to pick"
                        on == "picked" -> "stock ${part.onHand} (picked: ${partLabel(part)})"
                        else -> "stock ${part.onHand} ($on)"
                    }
                    Text(
                        "${line.perBoard} per board" + (if (boards > 0) " · need ${r?.need ?: 0}" else "") + " · " + match,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (part == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    picking?.let { line ->
        PickPartDialog(
            db = db,
            line = line,
            onDismiss = { picking = null },
            onPick = { partId ->
                db.setLinePart(line.id, partId)
                picking = null
                version++
            },
        )
    }

    if (confirmBuild) {
        val zeroed = plan.shortages.count { it.part != null }
        AlertDialog(
            onDismissRequest = { confirmBuild = false },
            title = { Text("Build $boards board${if (boards == 1) "" else "s"}?") },
            text = {
                Text(
                    "Subtracts the parts for $boards board${if (boards == 1) "" else "s"} from ${plan.use.size} " +
                        "part${if (plan.use.size == 1) "" else "s"} in stock." +
                        (if (zeroed > 0) " $zeroed part${if (zeroed == 1) " is" else "s are"} short and will go to 0." else "") +
                        (if (plan.unmatched > 0) " ${plan.unmatched} unmatched line${if (plan.unmatched == 1) " is" else "s are"} skipped." else ""),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    db.buildProject(project.id, project.name, plan.use, boards)
                    confirmBuild = false
                    version++
                    UndoOffer.offer(context, "Used parts for $boards × ${project.name}", db.lastChange)
                }) { Text("Use parts") }
            },
            dismissButton = { TextButton(onClick = { confirmBuild = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete project?") },
            text = { Text("Removes \"${project.name}\" and its BOM. Your stock isn't changed.") },
            confirmButton = {
                TextButton(onClick = {
                    db.deleteProject(project.id)
                    confirmDelete = false
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun partLabel(s: BuildPlan.Stock): String = s.mpn.ifBlank { s.customerPn.ifBlank { s.distributorPn } }

/** Choose the stock part for a BOM line, with the inventory search pre-filled from its value and footprint. */
@Composable
private fun PickPartDialog(db: InventoryDb, line: ProjectLine, onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    val parts by produceState(emptyList<PartRow>()) { value = withContext(Dispatchers.IO) { db.all() } }
    var query by remember { mutableStateOf(line.toBomLine().searchHint()) }
    val shown = remember(query, parts) { if (query.isBlank()) parts else parts.filter { it.matches(query) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Part for ${line.toBomLine().label()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Search stock") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (shown.isEmpty()) Text("Nothing in stock matches.", style = MaterialTheme.typography.bodySmall)
                    shown.take(100).forEach { p ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(p.id) }.padding(vertical = 6.dp)) {
                            Text(p.mpn.ifBlank { p.customerPn.ifBlank { p.distributorPn } }, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${p.quantity} in stock" + (if (p.location.isNotBlank()) " · ${p.location}" else "") +
                                    (if (p.description.isNotBlank()) " · ${p.description}" else ""),
                                style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        dismissButton = if (line.partId != null) {
            { TextButton(onClick = { onPick(null) }) { Text("Match automatically") } }
        } else {
            null
        },
    )
}
