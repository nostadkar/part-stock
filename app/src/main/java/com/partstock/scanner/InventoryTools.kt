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


/**
 * Looks up every part with a Mouser part number, one request every couple of seconds (Mouser allows about 30 a
 * minute), and updates price, description, link and category (unless picked by hand). Stops on a rate-limit or key
 * error; parts done so far stay updated.
 */
@Composable
internal fun RefreshPricesDialog(db: InventoryDb, secrets: SecretStore, parts: List<PartRow>, onClose: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val key = remember { secrets.getMouserKey() }
    val targets = remember { parts.filter { PriceRefresh.hasMouserPn(it.distributor, it.distributorPn, it.productUrl) } }
    var job by remember { mutableStateOf<Job?>(null) }
    var done by remember { mutableIntStateOf(0) }
    var updated by remember { mutableIntStateOf(0) }
    var notFound by remember { mutableIntStateOf(0) }
    var failed by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<String?>(null) }
    val running = job != null && result == null

    fun start(k: String) {
        job = scope.launch {
            var stopped: String? = null
            for ((i, p) in targets.withIndex()) {
                if (i > 0) delay(PriceRefresh.MOUSER_PACE_MS)
                try {
                    val r = MouserApi.lookup(k, p.distributorPn.trim(), maxOf(1, p.quantity))
                    if (r == null) {
                        notFound++
                    } else {
                        val cat = Categories.classify(r.category, r.description, p.description)
                        withContext(Dispatchers.IO) {
                            db.applyLookup(
                                p.id,
                                LookupUpdate(r.unitPrice, r.description, cat, r.url, "Mouser", r.datasheetUrl, r.specs),
                            )
                        }
                        updated++
                    }
                } catch (e: MouserException) {
                    if (e.rateLimited || e.badKey) {
                        stopped = e.message ?: "Mouser error"
                        break
                    }
                    failed++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                }
                done = i + 1
            }
            if (updated > 0) withContext(Dispatchers.IO) { db.notifyChanged() }
            result = PriceRefresh.summary(updated, notFound, failed, stopped, targets.size - done)
        }
    }

    fun stop() {
        job?.cancel()
        if (updated > 0) db.notifyChanged()
        result = PriceRefresh.summary(updated, notFound, failed, "you stopped it", targets.size - done)
    }

    AlertDialog(
        onDismissRequest = { if (!running) onClose(updated > 0) },
        title = { Text("Refresh prices") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    key == null -> Text("Add a Mouser API key in Settings first.")
                    targets.isEmpty() -> Text("No parts have a Mouser part number.")
                    result != null -> Text(result.orEmpty())
                    running -> {
                        Text("Checking ${done + 1} of ${targets.size}…")
                        LinearProgressIndicator(
                            progress = { done.toFloat() / targets.size },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Updated $updated so far. One request every ${PriceRefresh.MOUSER_PACE_MS / 1000.0} s " +
                                "to stay under Mouser's limit.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    else -> {
                        val secs = targets.size * PriceRefresh.MOUSER_PACE_MS / 1000
                        Text(
                            "Looks up ${targets.size} part${if (targets.size == 1) "" else "s"} with a Mouser part number " +
                                "and updates unit cost, description and category (categories you picked stay). " +
                                "Takes about ${if (secs < 90) "$secs seconds" else "${(secs + 59) / 60} minutes"}; " +
                                "keep this screen open.",
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                key != null && targets.isNotEmpty() && job == null ->
                    TextButton(onClick = { start(key) }) { Text("Start") }
                running -> TextButton(onClick = ::stop) { Text("Stop") }
                else -> TextButton(onClick = { onClose(updated > 0) }) { Text("Close") }
            }
        },
        dismissButton = {
            if (job == null) TextButton(onClick = { onClose(false) }) { Text("Cancel") }
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
