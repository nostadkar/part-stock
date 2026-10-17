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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.ParsedPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    part: ParsedPart,
    db: InventoryDb,
    prefs: Prefs,
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
    var addMode by remember { mutableStateOf(true) }

    var existing by remember { mutableStateOf<Match?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showRaw by remember { mutableStateOf(false) }

    // Check whether this part is already in stock; re-checks as the part numbers are edited.
    LaunchedEffect(mpn, customerPn, distributorPn) {
        existing = withContext(Dispatchers.IO) { db.find(mpn.trim(), customerPn.trim(), distributorPn.trim()) }
    }
    val hasKey = mpn.isNotBlank() || customerPn.isNotBlank() || distributorPn.isNotBlank()

    fun save() {
        error = null
        val q = qty.trim().toIntOrNull()
        when {
            !hasKey -> error = "Need at least a part number (MPN, customer PN or distributor PN)."
            q == null || q < 0 -> error = "Quantity must be a whole number."
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
                AssistChip(onClick = {}, label = { Text(part.labelType + " label") })
                if (part.refDesCount > 0) AssistChip(onClick = {}, label = { Text("${part.refDesCount} ref des") })
            }

            StockBanner(existing, hasKey, qty.trim().toIntOrNull(), addMode)

            Field("Manufacturer part number", mpn, { mpn = it }, mono = true,
                hint = if (part.mpnDerived) "Worked out from the Mouser P/N — check it" else null)
            Field("Description", description, { description = it }, singleLine = false)
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

/** Entrust kit labels print a per-board quantity and a kit count; offer the product. */
@Composable
private fun KitHint(part: ParsedPart, onUse: (Int) -> Unit) {
    val q = part.quantity
    val k = part.kitQuantity
    if (part.labelType != ParsedPart.ENTRUST || q == null) return
    Column {
        if (k != null && k > 1) {
            Row {
                Text(
                    "Label says $q per board × $k kits = ${q * k}. Count the bag if unsure.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).padding(top = 12.dp),
                )
                TextButton(onClick = { onUse(q * k) }) { Text("Use ${q * k}") }
            }
        }
        if (part.refDesCount > 0 && part.refDesCount != q) {
            Text(
                "Ref des list has ${part.refDesCount} entries but Qty reads $q.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
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
