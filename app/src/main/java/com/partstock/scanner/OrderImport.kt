package com.partstock.scanner

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.Categories
import com.partstock.scanner.parse.Links
import com.partstock.scanner.parse.OrderCsv

/**
 * Inventory → Import order: pick a Mouser or DigiKey order CSV, tick the lines, add them to stock. Lines are matched
 * to existing parts like a scan. Each order line is remembered (order number + line) in the scan log, so importing
 * the same order again shows "Already imported" and leaves those lines unticked.
 *
 * [onClose] gets a message to show after an import, or null when nothing changed.
 */
@Composable
fun OrderImportFlow(db: InventoryDb, onClose: (String?) -> Unit) {
    val context = LocalContext.current
    var order by remember { mutableStateOf<OrderCsv.Order?>(null) }
    var readError by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            onClose(null)
            return@rememberLauncherForActivityResult
        }
        try {
            val o = OrderCsv.parse(readText(context, uri), displayName(context, uri))
            if (o.error != null) readError = o.error else order = o
        } catch (e: Exception) {
            readError = "Couldn't read the file: ${e.message}"
        }
    }
    LaunchedEffect(Unit) {
        pick.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
    }

    readError?.let { msg ->
        AlertDialog(
            onDismissRequest = { onClose(null) },
            title = { Text("Import order") },
            text = {
                Text(
                    msg + "\n\nExport the order as CSV (Mouser: Order History → export; DigiKey: order or cart → " +
                        "Download CSV). An Excel file can be saved as CSV from Excel or Google Sheets first.",
                )
            },
            confirmButton = { TextButton(onClick = { onClose(null) }) { Text("OK") } },
        )
    }

    order?.let { OrderLinesDialog(db, it, onClose) }
}

@Composable
private fun OrderLinesDialog(db: InventoryDb, order: OrderCsv.Order, onClose: (String?) -> Unit) {
    var orderNo by remember { mutableStateOf(order.orderNumber) }
    val matches = remember { order.lines.map { db.find(it.mpn, it.customerPn, it.distributorPn) } }
    val seen = remember(orderNo) { order.lines.map { db.findBag(it.fingerprint(order.distributor, orderNo)) } }
    // Lines already imported start unticked; ticking one adds it again on purpose.
    val checked = remember { mutableStateListOf<Boolean>().apply { addAll(seen.map { it == null }) } }
    var error by remember { mutableStateOf<String?>(null) }
    val count = checked.count { it }

    fun doImport() {
        val no = orderNo.trim()
        val label = "${order.distributor} order" + if (no.isNotEmpty()) " $no" else ""
        val entries = order.lines.filterIndexed { i, _ -> checked[i] }.map { l ->
            ScanEntry(
                mpn = l.mpn,
                description = l.description,
                manufacturer = l.manufacturer,
                quantity = l.quantity,
                addMode = true,
                location = "",
                distributor = order.distributor,
                distributorPn = l.distributorPn,
                customerPn = l.customerPn,
                source = label,
                notes = if (no.isNotEmpty()) "Order $no" else "",
                dateCode = "",
                refDes = "",
                raw = "$label, line ${l.line}",
                unitCost = l.unitPrice,
                productUrl = Links.productUrl(order.distributor, l.distributorPn, l.mpn).orEmpty(),
                category = Categories.classify(l.description),
                bagFingerprint = l.fingerprint(order.distributor, no),
            )
        }
        try {
            val newParts = order.lines.indices.count { checked[it] && matches[it] == null }
            db.saveAll(entries)
            onClose("Added ${entries.size} line${if (entries.size == 1) "" else "s"} from $label ($newParts new part${if (newParts == 1) "" else "s"})")
        } catch (e: Exception) {
            error = "Import failed, nothing was changed: ${e.message}"
        }
    }

    AlertDialog(
        onDismissRequest = { onClose(null) },
        title = { Text("${order.distributor} order · ${order.lines.size} line${if (order.lines.size == 1) "" else "s"}") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    orderNo,
                    { orderNo = it },
                    label = { Text("Order number") },
                    supportingText = { Text("Used to recognise this order if it's imported again") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val again = seen.count { it != null }
                if (again > 0) {
                    Text(
                        "$again line${if (again == 1) " was" else "s were"} already imported and " +
                            "${if (again == 1) "is" else "are"} unticked.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                order.lines.forEachIndexed { i, l ->
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(checked = checked[i], onCheckedChange = { checked[i] = it })
                        Column(Modifier.weight(1f)) {
                            Text(
                                l.mpn.ifBlank { l.distributorPn },
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (l.mpn.isNotBlank() && l.distributorPn.isNotBlank()) {
                                Text(l.distributorPn, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                            if (l.description.isNotBlank()) {
                                Text(l.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                            Text(
                                "Qty ${l.quantity}" + (l.unitPrice?.let { " × \$${InventoryDb.fmtCost(it)}" } ?: "") +
                                    " · " + (matches[i]?.let { "in stock: ${it.row.quantity} → ${it.row.quantity + l.quantity}" }
                                        ?: "new part"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            seen[i]?.let { hit ->
                                Text(
                                    "Already imported ${shortDay(hit.timestamp)} (qty ${hit.quantity})" +
                                        if (checked[i]) " · will be added again" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = ::doImport, enabled = count > 0) {
                Text("Add $count line${if (count == 1) "" else "s"}")
            }
        },
        dismissButton = { TextButton(onClick = { onClose(null) }) { Text("Cancel") } },
    )
}

/** "2026-10-17 14:03:11" → "Oct 17", like the duplicate-bag warning; anything else is shown as it is. */
private fun shortDay(ts: String): String = try {
    java.time.LocalDateTime.parse(ts.replace(' ', 'T'))
        .format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US))
} catch (_: Exception) {
    ts
}

private fun readText(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("empty file")
    // Excel's "Unicode text" saves UTF-16 with a byte-order mark; everything else is read as UTF-8.
    return if (bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
            (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
    ) String(bytes, Charsets.UTF_16) else String(bytes, Charsets.UTF_8)
}

private fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (e: Exception) {
    null
}
