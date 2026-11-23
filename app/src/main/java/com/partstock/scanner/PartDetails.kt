package com.partstock.scanner

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.Links
import com.partstock.scanner.parse.Locations
import com.partstock.scanner.parse.PriceRefresh
import java.time.LocalDate
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.partstock.scanner.parse.PriceBreaks

/** What the details page can do; everything the old edit dialog did. */
class PartDetailsActions(
    val back: () -> Unit,
    /** Pieces per bin, main bin, description, cost, category, minimum. */
    val save: (Map<String, Int>, String, String, Double?, String, Int?) -> Unit,
    val use: (Int) -> Unit,
    val addOne: () -> Unit,
    val delete: () -> Unit,
    val move: (from: String, to: String, qty: Int) -> Unit,
    val history: () -> Unit,
    /** Null hides the Label button. */
    val label: (() -> Unit)?,
    /** Back to the list searching for a spec (tap the 0603 chip → search "0603"). */
    val search: ((String) -> Unit)? = null,
)

/** Full-screen part page: photo, big quantity with steppers, actions, and sections edited in place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartDetails(
    part: PartRow,
    photos: List<String>,
    knownBins: List<String>,
    scopes: SharedScopes?,
    animate: Boolean,
    actions: PartDetailsActions,
    datasheets: DatasheetOpener,
    /** The photo strip with add / delete (see [PartPhotosRow]). */
    photoStrip: @Composable () -> Unit,
    /** Reads the part's history (off the main thread) for the Stock over time card. */
    loadHistory: suspend () -> List<HistoryEntry>,
) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var using by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }

    // Edit fields start from the part each time editing starts.
    val startBins = remember(editing) { part.bins() }
    val multi = startBins.size > 1
    var qty by remember(editing) { mutableStateOf(part.quantity.toString()) }
    val binQty = remember(editing) { mutableStateListOf(*startBins.values.map { it.toString() }.toTypedArray()) }
    var location by remember(editing) {
        mutableStateOf(if (multi) part.location else Locations.plain(startBins.keys.first()))
    }
    var description by remember(editing) { mutableStateOf(part.description) }
    var cost by remember(editing) { mutableStateOf(part.unitCost?.let { InventoryDb.fmtCost(it) }.orEmpty()) }
    var category by remember(editing) { mutableStateOf(part.category) }
    var minimum by remember(editing) { mutableStateOf(part.minQty?.toString().orEmpty()) }

    fun stockMap(): Map<String, Int> =
        if (multi) startBins.keys.withIndex().associate { (i, b) -> b to (binQty[i].toIntOrNull() ?: startBins.getValue(b)) }
        else mapOf(Locations.normalize(location) to (qty.toIntOrNull() ?: part.quantity))

    fun save() {
        actions.save(
            stockMap(), location, description, cost.toDoubleOrNull(), category,
            minimum.toIntOrNull()?.takeIf { it > 0 },
        )
        editing = false
    }

    BackHandler { if (editing) editing = false else actions.back() }

    val link = part.link()
    val sheet = part.datasheet()
    val priceLabel = remember(part.priceSource, part.priceCheckedAt) {
        PriceRefresh.label(part.priceSource, part.priceCheckedAt, LocalDate.now())
    }
    val freshness = remember(part.priceSource, part.priceCheckedAt) {
        PriceRefresh.freshness(part.priceSource, part.priceCheckedAt, LocalDate.now())
    }
    // Price breaks from a lookup this session (they aren't stored); a refresh updates the row and so this too.
    val priceTable = remember(part) { PriceTables.forPart(part) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editing) "Edit part" else "") },
                navigationIcon = {
                    if (editing) {
                        IconButton(onClick = { editing = false }) { Icon(Icons.Filled.Close, contentDescription = "Cancel edit") }
                    } else {
                        IconButton(onClick = actions.back) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (editing) {
                        TextButton(onClick = ::save) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                            Text("Save", modifier = Modifier.padding(start = 4.dp))
                        }
                    } else {
                        IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Delete part", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                    onClick = {
                                        menu = false
                                        confirmDelete = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Photo (flies in from the row) and the strip to add more.
            Row(verticalAlignment = Alignment.CenterVertically) {
                PartImage(part, photos.firstOrNull(), 112.dp, Modifier.sharedPart(scopes, "img-${part.id}"))
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(
                        part.key(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.sharedPart(scopes, "mpn-${part.id}"),
                    )
                    val sub = listOf(part.manufacturer, part.category).filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotBlank()) {
                        Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SpecHero(part.specs(), Modifier.padding(top = 8.dp), onTap = actions.search)
                }
            }
            photoStrip()

            if (editing) {
                OutlinedTextField(description, { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                CategoryPicker(category) { category = it }
            } else if (part.description.isNotBlank()) {
                Text(part.description, style = MaterialTheme.typography.bodyLarge)
            }

            // Big quantity with steppers.
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (part.isLow()) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = { actions.use(1) }, enabled = part.quantity > 0 && !editing,
                        modifier = Modifier.size(56.dp),
                    ) { Icon(Icons.Filled.Remove, contentDescription = "Use 1, current count ${part.quantity}") }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        QuantityText(
                            part.quantity, animate,
                            color = if (part.isLow()) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.displayMedium,
                        )
                        Text(
                            when {
                                part.minQty != null && part.isLow() -> "in stock · low (min ${part.minQty})"
                                part.minQty != null -> "in stock · min ${part.minQty}"
                                else -> "in stock"
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                        part.minQty?.let { m ->
                            val on = if (part.isLow()) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onPrimaryContainer
                            StockBar(
                                part.quantity, m,
                                Modifier.padding(top = 8.dp, start = 12.dp, end = 12.dp).fillMaxWidth(),
                                height = 8.dp,
                                track = on.copy(alpha = 0.15f),
                                neutral = on.copy(alpha = 0.7f),
                                tick = on,
                                animate = animate,
                            )
                        }
                    }
                    FilledTonalIconButton(
                        onClick = actions.addOne, enabled = !editing, modifier = Modifier.size(56.dp),
                    ) { Icon(Icons.Filled.Add, contentDescription = "Add 1, current count ${part.quantity}") }
                }
            }

            // Prominent links, then the other actions.
            if (sheet.isNotBlank() || link.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sheet.isNotBlank()) {
                        FilledTonalButton(
                            onClick = { datasheets.open(part) }, enabled = datasheets.busyId == null,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (datasheets.busyId == part.id) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Text(
                                when {
                                    datasheets.busyId == part.id -> "Finding…"
                                    datasheets.isSearch(part) -> "Find datasheet"
                                    else -> "Datasheet"
                                },
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                    if (link.isNotBlank()) {
                        FilledTonalButton(onClick = { openLink(context, link) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                Links.siteName(link).let { if (it == "web") "Open on web" else it },
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Use…", Icons.Filled.Remove, enabled = !editing) { using = true }
                ActionButton("Move", Icons.Filled.SwapHoriz, enabled = !editing) { moving = true }
                actions.label?.let { onLabel -> ActionButton("Label", Icons.Filled.QrCode2, onClick = onLabel) }
                ActionButton("History", Icons.Filled.History, onClick = actions.history)
            }

            Section("Stock by location", Icons.Filled.Inventory2) {
                if (editing) {
                    if (multi) {
                        startBins.keys.forEachIndexed { i, b ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    b + if (Locations.same(b, part.location)) " (main)" else "",
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    binQty[i], { v -> binQty[i] = v.filter { it.isDigit() }.take(7) }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(110.dp),
                                )
                            }
                        }
                    } else {
                        OutlinedTextField(
                            qty, { v -> qty = v.filter { it.isDigit() } }, label = { Text("Quantity") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            location, { location = it }, label = { Text("Bin / location") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        minimum, { v -> minimum = v.filter { it.isDigit() }.take(7) },
                        label = { Text("Minimum (optional)") }, singleLine = true,
                        supportingText = { Text("Low stock when the count is at or below this") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    part.bins().forEach { (b, q) ->
                        InfoRow(b + if (part.bins().size > 1 && Locations.same(b, part.location)) " (main)" else "", q.toString())
                    }
                    InfoRow("Minimum", part.minQty?.toString() ?: "not set")
                }
            }

            if (!editing) StockOverTimeCard(part, loadHistory, actions.history)

            Section("Price", null) {
                if (editing) {
                    OutlinedTextField(
                        cost, { v -> cost = v.filter { it.isDigit() || it == '.' } }, label = { Text("Unit cost (each)") },
                        singleLine = true, prefix = { Text("$") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    val c = part.unitCost
                    InfoRow("Unit cost", if (c != null) "\$" + InventoryDb.fmtCost(c) else "not set")
                    if (c != null) InfoRow("Stock value", "\$" + InventoryDb.fmtMoney(c * part.quantity))
                    if (c != null || priceLabel.isNotBlank()) FreshnessChip(freshness, priceLabel)
                    priceTable?.let { PriceBreaksView(it, part.quantity, animate) }
                }
            }

            val sp = part.specs()
            if (!sp.isEmpty) {
                Section("Specs", null) {
                    if (part.specValue.isNotBlank()) InfoRow("Value", part.specValue)
                    if (part.tolerance.isNotBlank()) InfoRow("Tolerance", part.tolerance)
                    if (part.voltage.isNotBlank()) InfoRow("Voltage", part.voltage)
                    if (part.pkg.isNotBlank()) InfoRow("Package", part.pkg)
                }
            }

            Section("Identifiers", null) {
                if (part.mpn.isNotBlank()) InfoRow("MPN", part.mpn, mono = true)
                if (part.distributorPn.isNotBlank()) InfoRow(part.distributor.ifBlank { "Distributor" } + " PN", part.distributorPn, mono = true)
                if (part.customerPn.isNotBlank()) InfoRow("Customer PN", part.customerPn, mono = true)
                if (part.dateCode.isNotBlank()) InfoRow("Date code", part.dateCode)
                if (part.refDes.isNotBlank()) InfoRow("Ref des", part.refDes)
            }

            if (part.notes.isNotBlank()) {
                Section("Notes", null) { Text(part.notes, style = MaterialTheme.typography.bodyMedium) }
            }

            Text(
                "Scanned ${part.scans}× · updated ${part.updatedAt}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${part.key()}?") },
            text = { Text("You can undo this from the message at the bottom.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    actions.delete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (using) {
        UseDialog(part.quantity, onDismiss = { using = false }) { n ->
            using = false
            actions.use(n)
        }
    }

    if (moving) {
        MoveDialog(
            bins = part.bins(),
            knownBins = knownBins,
            onDismiss = { moving = false },
            onMove = { from, to, n ->
                moving = false
                actions.move(from, to, n)
            },
        )
    }
}

@Composable
private fun ActionButton(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text, modifier = Modifier.padding(start = 6.dp), maxLines = 1)
    }
}

@Composable
private fun Section(title: String, icon: ImageVector?, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f),
        )
        Text(
            value, style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.weight(0.55f),
        )
    }
}

/** How many to use; more than in stock needs a second tap and sets the count to 0. */
@Composable
private fun UseDialog(stock: Int, onDismiss: () -> Unit, onUse: (Int) -> Unit) {
    var text by remember { mutableStateOf("1") }
    var confirm by remember { mutableStateOf(false) }
    val n = text.toIntOrNull() ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Use parts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text, { v ->
                        text = v.filter { it.isDigit() }.take(6)
                        confirm = false
                    },
                    label = { Text("How many") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    if (n > stock) "Only $stock in stock; this sets it to 0." else "After use: ${stock - n}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (n > stock) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = n > 0, onClick = {
                if (n > stock && !confirm) confirm = true else onUse(n)
            }) { Text(if (confirm) "Tap again to use" else "Use") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Fresh · Price from Mouser, Oct 18" as a colored chip: green, amber (30+ days), red (90+ days) or grey (never). */
@Composable
private fun FreshnessChip(f: PriceRefresh.Freshness, label: String) {
    val (color, icon) = when (f) {
        PriceRefresh.Freshness.FRESH -> ChartColors.positive() to Icons.Filled.CheckCircle
        PriceRefresh.Freshness.AGING -> ChartColors.warning() to Icons.Filled.Schedule
        PriceRefresh.Freshness.OLD -> MaterialTheme.colorScheme.error to Icons.Filled.ErrorOutline
        PriceRefresh.Freshness.NEVER -> MaterialTheme.colorScheme.onSurfaceVariant to Icons.AutoMirrored.Filled.HelpOutline
    }
    val text = PriceRefresh.freshnessText(f) + if (label.isNotBlank()) " · $label" else ""
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** The last lookup's price breaks: a step chart, a quantity / price row per break, and the next-break hint. */
@Composable
private fun PriceBreaksView(t: PriceTable, have: Int, animate: Boolean) {
    val shown = t.qty.size.coerceAtMost(MAX_BREAKS)
    val qty = t.qty.copyOf(shown)
    val price = t.price.copyOf(shown)
    val marked = PriceBreaks.pick(qty, have)
    fun money(d: Double) = (if (t.currency.isBlank() || t.currency == "USD") "\$" else t.currency + " ") + InventoryDb.fmtCost(d)
    val description = "Price breaks from ${t.source}: " +
        qty.indices.joinToString(", ") { "${qty[it]}+ at ${money(price[it])}" } +
        ". Your $have pieces are at the ${qty.getOrNull(marked) ?: 0}+ break."
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            "Price breaks (${t.source})", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PriceStepChart(price, marked, description, Modifier.padding(top = 4.dp), animate = animate)
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            qty.indices.forEach { i ->
                val on = i == marked
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "%,d+".format(qty[i]), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        fontWeight = if (on) FontWeight.Bold else null,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        InventoryDb.fmtCost(price[i]), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        fontWeight = if (on) FontWeight.Bold else null,
                    )
                }
            }
        }
        Text(
            "▲ your $have pcs", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {},
        )
        PriceBreaks.nextBreakHint(qty, price, have) { money(it) }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** More breaks than this don't fit a phone's width; the rest are the large reel quantities. */
private const val MAX_BREAKS = 7

