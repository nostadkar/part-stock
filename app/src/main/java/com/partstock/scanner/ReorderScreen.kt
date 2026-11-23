package com.partstock.scanner

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.partstock.scanner.parse.ReorderList
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The reorder list on screen: parts at or below their minimum with editable order quantities, prices and an estimated
 * total; shares the same CSV as the menu (with the edited quantities), copies Mouser BOM text, and marks lines ordered.
 */
@Composable
fun ReorderPage(vm: InventoryViewModel, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val lines by vm.reorder.collectAsStateWithLifecycle()
            ReorderContent(lines, vm, onClose)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReorderContent(lines: List<ReorderLine>?, vm: InventoryViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // Edited quantities and left-out lines by part id; they survive the list reloading after "Mark ordered".
    val edits = remember { mutableStateMapOf<Long, Int>() }
    val leftOut = remember { mutableStateMapOf<Long, Boolean>() }
    var editing by remember { mutableStateOf<ReorderLine?>(null) }
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    val all = lines.orEmpty()
    // Lines marked ordered, or with nothing to order, start left out.
    fun included(l: ReorderLine): Boolean = !(leftOut[l.part.id] ?: (l.orderedOn != null || l.suggested <= 0))
    fun qtyOf(l: ReorderLine): Int = edits[l.part.id] ?: l.suggested
    fun itemOf(l: ReorderLine): ReorderList.Item = l.item.copy().apply { orderQty = edits[l.part.id] }
    val chosen = all.filter(::included)
    val chosenItems = chosen.map(::itemOf)
    val estimate = ReorderList.estimate(chosenItems)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reorder") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val none = estimate.lines == 0
                    FilledTonalButton(
                        onClick = {
                            Export.share(context, Export.fileName("reorder"), ReorderList.csv(chosenItems))
                        },
                        enabled = !none,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("Share CSV", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = {
                            val text = ReorderList.mouserBom(chosenItems)
                            if (text.isBlank()) {
                                toast("No part numbers to copy")
                            } else {
                                clipboard.setText(AnnotatedString(text))
                                val n = text.lines().count { it.isNotBlank() }
                                toast(if (n == 1) "Copied 1 line for Mouser's BOM tool" else "Copied $n lines for Mouser's BOM tool")
                            }
                        },
                        enabled = !none,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("Mouser BOM", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = {
                            val ids = chosen.filter { qtyOf(it) > 0 }.map { it.part.id }
                            vm.markOrdered(ids, LocalDate.now().toString())
                            ids.forEach { leftOut[it] = true }
                            toast(if (ids.size == 1) "Marked 1 part as ordered" else "Marked ${ids.size} parts as ordered")
                        },
                        enabled = !none,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("Mark ordered", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
    ) { padding ->
        when {
            lines == null -> Spacer(Modifier.fillMaxSize().padding(padding))
            all.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Nothing to reorder", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Parts show up here when their stock is at or below the minimum set on their page.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
            ) {
                item(key = "header") { EstimateCard(estimate, all.size, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                items(all, key = { it.part.id }) { l ->
                    ReorderRow(
                        line = l,
                        qty = qtyOf(l),
                        included = included(l),
                        onIncluded = { leftOut[l.part.id] = !it },
                        onQty = { q ->
                            edits[l.part.id] = q
                            if (q > 0) leftOut[l.part.id] = false
                        },
                        onEditQty = { editing = l },
                    )
                    HorizontalDivider(Modifier.padding(start = 64.dp))
                }
            }
        }
    }

    editing?.let { l ->
        QtyDialog(
            name = l.part.key(),
            initial = qtyOf(l),
            onDismiss = { editing = null },
            onDone = { q ->
                edits[l.part.id] = q
                if (q > 0) leftOut[l.part.id] = false
                editing = null
            },
        )
    }
}

/** "4 lines · 120 pieces · est. $12.40", and how many lines have no price (not counted). */
@Composable
private fun EstimateCard(e: ReorderList.Estimate, listed: Int, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Estimated \$${InventoryDb.fmtMoney(e.cost)}", style = MaterialTheme.typography.headlineSmall)
            val lines = if (e.lines == 1) "1 line" else "${e.lines} lines"
            Text("$lines · ${e.pieces} pieces · $listed low on stock", style = MaterialTheme.typography.bodyMedium)
            if (e.unpriced > 0) {
                Text(
                    if (e.unpriced == 1) "1 line has no price and isn't counted"
                    else "${e.unpriced} lines have no price and aren't counted",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ReorderRow(
    line: ReorderLine,
    qty: Int,
    included: Boolean,
    onIncluded: (Boolean) -> Unit,
    onQty: (Int) -> Unit,
    onEditQty: () -> Unit,
) {
    val context = LocalContext.current
    val p = line.part
    val item = line.item
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = included, onCheckedChange = onIncluded)
        Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(p.key(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.description.isNotBlank()) {
                Text(p.description, style = MaterialTheme.typography.bodySmall, color = muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StockBar(p.quantity, p.minQty, Modifier.fillMaxWidth().padding(vertical = 2.dp))
            Text("${p.quantity} on hand · min ${p.minQty ?: 0}", style = MaterialTheme.typography.bodySmall, color = muted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val dist = ReorderList.distributorName(item)
                if (dist.isNotEmpty()) {
                    AssistChip(onClick = {}, label = { Text(dist) })
                }
                line.orderedOn?.let { day ->
                    AssistChip(onClick = {}, label = { Text("Ordered ${shortDay(day)}") })
                }
                val link = item.link
                if (!link.isNullOrBlank()) {
                    TextButton(onClick = { openLink(context, link) }) { Text("Product page") }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 120.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onQty((qty - stepDown(qty)).coerceAtLeast(0)) }, enabled = qty > 0) {
                    Icon(Icons.Filled.Remove, contentDescription = "Order fewer ${p.key()}")
                }
                Text(
                    qty.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .clickable(role = Role.Button, onClickLabel = "Type a quantity", onClick = onEditQty)
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                )
                IconButton(onClick = { onQty(qty + stepUp(qty)) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Order more ${p.key()}")
                }
            }
            val cost = item.unitCost
            Text(
                if (cost != null) "\$${InventoryDb.fmtCost(cost)} each" else "price unknown",
                style = MaterialTheme.typography.bodySmall, color = muted,
            )
            if (cost != null) {
                Text("\$${InventoryDb.fmtMoney(cost * qty)}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Type an exact order quantity. */
@Composable
private fun QtyDialog(name: String, initial: Int, onDismiss: () -> Unit, onDone: (Int) -> Unit) {
    var text by remember { mutableStateOf(initial.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Order quantity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, fontFamily = FontFamily.Monospace)
                NumberField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() }.take(7) },
                    label = "Quantity",
                    last = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(text.toIntOrNull() ?: 0) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Steps that keep sensible amounts: 1 below 10, 5 below 100, 10 below 500, then 50. */
private fun stepUp(q: Int): Int = when {
    q < 10 -> 1
    q < 100 -> 5
    q < 500 -> 10
    else -> 50
}

private fun stepDown(q: Int): Int = stepUp((q - 1).coerceAtLeast(0))

private fun ReorderList.Item.copy(): ReorderList.Item {
    val src = this
    return ReorderList.Item().apply {
        distributor = src.distributor; distributorPn = src.distributorPn; mpn = src.mpn
        manufacturer = src.manufacturer; description = src.description; link = src.link
        onHand = src.onHand; minimum = src.minimum; unitCost = src.unitCost; id = src.id; orderQty = src.orderQty
    }
}

/** "2026-11-12" → "12 Nov". */
private fun shortDay(iso: String): String = try {
    LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
} catch (e: Exception) {
    iso
}
