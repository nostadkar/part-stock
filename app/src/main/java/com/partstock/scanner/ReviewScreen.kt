package com.partstock.scanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.Categories
import com.partstock.scanner.parse.Links
import com.partstock.scanner.parse.ParsedPart
import com.partstock.scanner.parse.PartNumberVariants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    part: ParsedPart,
    db: InventoryDb,
    prefs: Prefs,
    secrets: SecretStore,
    onSaved: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var mpn by remember { mutableStateOf(part.mpn.orEmpty()) }
    var description by remember { mutableStateOf(part.description.orEmpty()) }
    var manufacturer by remember { mutableStateOf(part.manufacturer.orEmpty()) }
    var qty by remember { mutableStateOf(part.quantity?.toString().orEmpty()) }
    var location by remember { mutableStateOf(prefs.lastLocation) }
    var distributorPn by remember { mutableStateOf(part.distributorPn.orEmpty()) }
    var customerPn by remember { mutableStateOf(part.customerPn.orEmpty()) }
    var notes by remember { mutableStateOf(part.notes.orEmpty()) }
    var category by remember { mutableStateOf(Categories.classify(part.description, part.notes)) }
    var categoryPicked by remember { mutableStateOf(false) } // the user chose one; lookups leave it alone
    var descriptionFromMouser by remember { mutableStateOf(false) }
    var addMode by remember { mutableStateOf(true) }
    var unitCost by remember { mutableStateOf("") }
    // Product page returned by Mouser's lookup; otherwise a link is built from the part numbers.
    var apiUrl by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }
    var lookupMsg by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val mouserKey = remember { secrets.getMouserKey() }

    var existing by remember { mutableStateOf<Match?>(null) }
    val fingerprint = remember { part.bagFingerprint() }
    var seenBag by remember { mutableStateOf<BagHit?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showRaw by remember { mutableStateOf(false) }

    // Check whether this part is already in stock; re-checks as the part numbers are edited.
    LaunchedEffect(mpn, customerPn, distributorPn) {
        existing = withContext(Dispatchers.IO) { db.find(mpn.trim(), customerPn.trim(), distributorPn.trim()) }
    }
    // Same bag saved before? Then a second "Add" would double-count it, so default to "Set".
    LaunchedEffect(fingerprint) {
        seenBag = withContext(Dispatchers.IO) { db.findBag(fingerprint) }
        if (seenBag != null) addMode = false
    }
    val hasKey = mpn.isNotBlank() || customerPn.isNotBlank() || distributorPn.isNotBlank()
    val link: String? = apiUrl ?: Links.productUrl(part.distributor, distributorPn, mpn)

    // Keep the price already on file when this part is rescanned, unless a new one is typed or looked up.
    LaunchedEffect(existing) {
        val known = existing?.row?.unitCost
        if (known != null && unitCost.isBlank()) unitCost = InventoryDb.fmtCost(known)
        val savedCat = existing?.row?.category
        if (savedCat != null && savedCat != Categories.OTHER && !categoryPicked && !descriptionFromMouser) category = savedCat
    }

    fun lookupMouser() {
        val key = mouserKey ?: return
        val pn = if (part.distributor == ParsedPart.MOUSER && distributorPn.isNotBlank()) distributorPn.trim() else mpn.trim()
        if (pn.isBlank()) {
            lookupMsg = "Enter a part number first."
            return
        }
        val q = qty.trim().toIntOrNull() ?: 1
        scope.launch {
            looking = true
            lookupMsg = null
            try {
                // 1. As read, accepting the nearest result if one or two characters were misread.
                var found = MouserApi.lookup(key, pn, q, maxDistance = 2)
                // 2. Mouser may not list near-misses for a wrong number, so search on the start of it
                //    (misreads at the end, like #PBE for #PBF, are common) and pick the nearest.
                if (found == null) {
                    val stem = pn.substringBefore('#').let { if (it.length < pn.length) it else pn.dropLast(3) }
                    if (stem.length >= 6) found = MouserApi.lookup(key, stem, q, wanted = pn, maxDistance = 2)
                }
                // 3. Swap look-alike characters (I/1/L, O/0, E/F...) one at a time.
                if (found == null) {
                    for (v in PartNumberVariants.confusions(pn, 12)) {
                        found = MouserApi.lookup(key, v, q) ?: continue
                        break
                    }
                }
                val r = found
                val fixedFrom = if (r != null && r.mouserPn.isNotBlank() &&
                    PartNumberVariants.distance(r.mouserPn, pn) > 0 &&
                    PartNumberVariants.distance(r.mpn, pn) > 0) pn else null
                if (r == null) {
                    lookupMsg = "Mouser has no match for $pn. Check the number against the label and try again."
                } else {
                    if (r.unitPrice != null) unitCost = InventoryDb.fmtCost(r.unitPrice)
                    if (r.url.isNotBlank()) apiUrl = r.url
                    if (part.distributor == ParsedPart.MOUSER && r.mouserPn.isNotBlank()) distributorPn = r.mouserPn
                    if (r.description.isNotBlank()) {
                        description = r.description
                        descriptionFromMouser = true
                    }
                    if (!categoryPicked) category = Categories.classify(r.category, r.description, description)
                    if (manufacturer.isBlank() && r.manufacturer.isNotBlank()) manufacturer = r.manufacturer
                    // A part number worked out from the label can be off by an OCR slip; Mouser's is exact.
                    if ((mpn.isBlank() || part.mpnDerived) && r.mpn.isNotBlank()) mpn = r.mpn
                    val base = if (r.unitPrice != null) {
                        "Mouser: ${r.currency} ${InventoryDb.fmtCost(r.unitPrice)} each at ${r.priceAtQty}+"
                    } else {
                        "Found on Mouser, but no price listed."
                    }
                    lookupMsg = if (fixedFrom != null) "$base. Fixed a likely misread: $fixedFrom → ${r.mouserPn}" else base
                }
            } catch (e: MouserException) {
                lookupMsg = e.message
            } catch (e: Exception) {
                lookupMsg = "Lookup failed: " + (e.message ?: e.javaClass.simpleName).replace(key, "***")
            } finally {
                looking = false
            }
        }
    }

    fun save() {
        error = null
        val q = qty.trim().toIntOrNull()
        val cost = unitCost.trim().toDoubleOrNull()
        when {
            !hasKey -> error = "Need at least a part number (MPN, customer PN or distributor PN)."
            q == null || q < 0 -> error = "Quantity must be a whole number."
            unitCost.isNotBlank() && (cost == null || cost < 0) -> error = "Unit cost must be a number."
            else -> scope.launch {
                saving = true
                prefs.lastLocation = location
                val entry = ScanEntry(
                    mpn = mpn.trim(), description = description.trim(), manufacturer = manufacturer.trim(),
                    quantity = q, addMode = addMode, location = location.trim(),
                    distributor = part.distributor.orEmpty(), distributorPn = distributorPn.trim(),
                    customerPn = customerPn.trim(), source = part.labelType, notes = notes.trim(),
                    dateCode = part.dateCode.orEmpty(), refDes = part.refDes.orEmpty(),
                    raw = part.rawText.take(1500),
                    unitCost = cost, productUrl = link.orEmpty(),
                    category = category, descriptionFromMouser = descriptionFromMouser,
                    bagFingerprint = fingerprint.orEmpty(),
                )
                val name = mpn.ifBlank { customerPn.ifBlank { distributorPn } }
                try {
                    val total = withContext(Dispatchers.IO) { db.save(entry) }
                    onSaved("Saved $name — $total in stock")
                } catch (e: Exception) {
                    error = "Save failed: ${e.message}"
                    saving = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check & save") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Rescan")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text(if (part.labelType.contains("+")) part.labelType + " labels" else part.labelType + " label") },
                )
                if (part.refDesCount > 0) AssistChip(onClick = {}, label = { Text("${part.refDesCount} ref des") })
            }

            seenBag?.let { DuplicateBagCard(it) }
            StockBanner(existing, hasKey, qty.trim().toIntOrNull(), addMode)

            Field("Manufacturer part number", mpn, { mpn = it }, mono = true,
                hint = if (part.mpnDerived) "Worked out from the Mouser P/N — check it" else null)
            Field("Description", description, { description = it }, singleLine = false)
            CategoryPicker(category) {
                category = it
                categoryPicked = true
            }
            Field("Manufacturer", manufacturer, { manufacturer = it })

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = qty,
                    onValueChange = { v -> qty = v.filter { it.isDigit() } },
                    label = { Text("Quantity") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Bin / location") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.weight(1f),
                )
            }

            KitHint(part) { qty = it.toString() }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = addMode, onClick = { addMode = true }, label = { Text("Add to stock") })
                FilterChip(selected = !addMode, onClick = { addMode = false }, label = { Text("Set count to this") })
            }

            OutlinedTextField(
                value = unitCost,
                onValueChange = { v -> unitCost = v.filter { it.isDigit() || it == '.' } },
                label = { Text("Unit cost (each)") },
                singleLine = true,
                prefix = { Text("$") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = {
                    val total = unitCost.toDoubleOrNull()?.let { c -> qty.toIntOrNull()?.let { n -> c * n } }
                    Text(if (total != null) "This entry: \$${InventoryDb.fmtMoney(total)}" else "Labels don't print a price")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (mouserKey != null) {
                OutlinedButton(onClick = ::lookupMouser, enabled = !looking, modifier = Modifier.fillMaxWidth()) {
                    if (looking) CircularProgressIndicator(Modifier.size(18.dp)) else Text("Look up price on Mouser")
                }
            } else {
                Text(
                    "Add a Mouser API key in Settings to fill in the price automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            lookupMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (link != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        link,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { openLink(context, link) }) { Text("Open on ${Links.siteName(link)}") }
                }
            }

            Field(
                (part.distributor ?: "Distributor") + " part number", distributorPn, { distributorPn = it }, mono = true
            )
            Field("Customer / Entrust PN", customerPn, { customerPn = it }, mono = true)
            Field("Notes", notes, { notes = it }, singleLine = false)

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(onClick = ::save, enabled = !saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (saving) CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text(if (addMode) "Add to inventory" else "Set inventory count")
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Rescan") }

            TextButton(onClick = { showRaw = !showRaw }) {
                Text(if (showRaw) "Hide text read from label" else "Show text read from label")
            }
            if (showRaw) {
                Text(part.rawText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DuplicateBagCard(hit: BagHit) {
    val day = try {
        java.time.LocalDateTime.parse(hit.timestamp.replace(' ', 'T'))
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US))
    } catch (_: Exception) {
        hit.timestamp
    }
    val how = if (hit.mode == "set") "counted" else "added"
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp).fillMaxWidth()) {
            Text(
                "This bag was already $how on $day (qty ${hit.quantity})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "Same order details on the label. Switched to \"Set count to this\" so it isn't counted twice; " +
                    "pick \"Add to stock\" if it really is another bag.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun StockBanner(existing: Match?, hasKey: Boolean, qty: Int?, addMode: Boolean) {
    if (existing != null) {
        val r = existing.row
        val after = when {
            qty == null -> r.quantity
            addMode -> r.quantity + qty
            else -> qty
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(12.dp).fillMaxWidth()) {
                Text("Already in stock: ${r.quantity}", style = MaterialTheme.typography.titleSmall)
                val where = if (r.location.isNotBlank()) " · bin ${r.location}" else ""
                Text("${r.mpn.ifBlank { r.customerPn }} (matched by ${existing.matchedOn})$where",
                    style = MaterialTheme.typography.bodySmall)
                Text("After save: $after", style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else if (hasKey) {
        Text("New part — not in inventory yet", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Entrust kit labels print a per-board quantity and a kit count. */
@Composable
private fun KitHint(part: ParsedPart, onUse: (Int) -> Unit) {
    val perBoard = part.kitPerBoard ?: return
    val kits = part.kitQuantity
    val needs = if (kits != null && kits > 0) perBoard * kits else null
    Column {
        if (part.distributorPn == null) {
            // Kit label only: its Qty is per board, so offer the whole-kit total.
            if (kits != null && kits > 1 && needs != null) {
                Row {
                    Text(
                        "Label says $perBoard per board × $kits kits = $needs. Count the bag if unsure.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(top = 12.dp),
                    )
                    TextButton(onClick = { onUse(needs) }) { Text("Use $needs") }
                }
            }
            if (part.refDesCount > 0 && part.refDesCount != perBoard) {
                Text(
                    "Ref des list has ${part.refDesCount} entries but Qty reads $perBoard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else if (needs != null) {
            // Distributor label present: its quantity is the bag count, the kit label says how many are used.
            Text(
                "Kit label: $perBoard per board × $kits boards = $needs used. " +
                    "Quantity above is what's in this bag (${part.quantity ?: "?"}).",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = true,
    mono: Boolean = false,
    hint: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        textStyle = if (mono) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
        else MaterialTheme.typography.bodyLarge,
        supportingText = if (hint != null) {
            { Text(hint) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth(),
    )
}
