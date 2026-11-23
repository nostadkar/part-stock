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
import com.partstock.scanner.parse.BuildPlan
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


/**
 * Asks before refreshing every part with a Mouser part number. The refresh itself runs in [JobsViewModel] with a
 * progress card, so you can keep browsing; while one runs, this shows it instead of offering a new one.
 */
@Composable
internal fun RefreshPricesDialog(secrets: SecretStore, parts: List<PartRow>, onClose: () -> Unit) {
    val jobs = LocalJobs.current
    val key = remember { secrets.getMouserKey() }
    val targets = remember { parts.filter { PriceRefresh.hasMouserPn(it.distributor, it.distributorPn, it.productUrl) } }
    val running = jobs.isRunning(JobKind.PRICES)

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Refresh prices") },
        text = {
            when {
                running -> Text("A price refresh is already running; its card at the bottom shows how far it is.")
                key == null -> Text("Add a Mouser API key in Settings first.")
                targets.isEmpty() -> Text("No parts have a Mouser part number.")
                else -> {
                    val secs = targets.size * PriceRefresh.MOUSER_PACE_MS / 1000
                    Text(
                        "Looks up ${targets.size} part${if (targets.size == 1) "" else "s"} with a Mouser part number " +
                            "and updates unit cost, description and category (categories you picked stay). " +
                            "Takes about ${if (secs < 90) "$secs seconds" else "${(secs + 59) / 60} minutes"}; " +
                            "it runs in the background, and you'll see what changed when it's done.",
                    )
                }
            }
        },
        confirmButton = {
            if (!running && key != null && targets.isNotEmpty()) {
                TextButton(onClick = {
                    jobs.startPriceRefresh(key, targets)
                    onClose()
                }) { Text("Start") }
            } else {
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
        dismissButton = {
            if (!running && key != null && targets.isNotEmpty()) TextButton(onClick = onClose) { Text("Cancel") }
        },
    )
}

/** Pick a kit, enter how many boards, check the list, then subtract everything at once. */
@Composable
internal fun BuildKitDialog(db: InventoryDb, onDismiss: () -> Unit, onBuilt: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    // null while loading (read on IO, not the main thread)
    val kitsOrNull by produceState<List<KitSummary>?>(null) { value = withContext(Dispatchers.IO) { db.kits() } }
    val kits = kitsOrNull.orEmpty()
    var picked by remember { mutableStateOf<String?>(null) }
    val kit = picked ?: kits.firstOrNull()?.kit
    var boardsText by remember { mutableStateOf("1") }
    var confirmShort by remember { mutableStateOf(false) }
    var building by remember { mutableStateOf(false) }
    val boards = boardsText.toIntOrNull() ?: 0
    val plan by produceState(emptyList<KitLine>(), kit, boards) {
        val k = kit
        value = if (k != null && boards > 0) withContext(Dispatchers.IO) { db.kitPlan(k, boards) } else emptyList()
    }
    val short = plan.filter { it.after < 0 }

    if (kitsOrNull == null) return

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
                            picked = k.kit
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
                if (boards > 0 && short.isNotEmpty()) {
                    // Same "Short for N boards" chart as a project.
                    Text("Short for $boards board${if (boards == 1) "" else "s"}", style = MaterialTheme.typography.titleSmall)
                    ShortageChart(
                        short.sortedByDescending { it.need - maxOf(0, it.part.quantity) }.take(5).map { line ->
                            val p = line.part
                            ShortBar(
                                p.mpn.ifBlank { p.customerPn.ifBlank { p.distributorPn } }, line.need - maxOf(0, p.quantity),
                                "need ${line.need}, have ${maxOf(0, p.quantity)}",
                            )
                        },
                    )
                }
                plan.forEach { line ->
                    val p = line.part
                    Column {
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
                        val have = maxOf(0, p.quantity)
                        CoverageBar(
                            BuildPlan.status(have, line.need), BuildPlan.coverage(have, line.need), have, line.need,
                            Modifier.padding(top = 4.dp),
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
                    enabled = kit != null && boards > 0 && plan.isNotEmpty() && !building,
                    onClick = {
                        val k = kit
                        if (k != null) {
                            if (short.isNotEmpty() && !confirmShort) {
                                confirmShort = true
                            } else {
                                building = true
                                val n = boards
                                scope.launch {
                                    withContext(Dispatchers.IO) { db.buildKit(k, n) }
                                    onBuilt("Used parts for $n board${if (n == 1) "" else "s"} of $k")
                                }
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
