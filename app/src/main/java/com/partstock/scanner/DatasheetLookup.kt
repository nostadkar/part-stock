package com.partstock.scanner

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.partstock.scanner.parse.Links
import com.partstock.scanner.parse.ParsedPart
import com.partstock.scanner.parse.PriceRefresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Finds a part's datasheet link on Mouser (or DigiKey, for DigiKey parts) when no lookup saved one yet. */
object DatasheetLookup {
    /** The datasheet link, or null when the distributor lists none or no key is set up. Mouser errors are thrown. */
    suspend fun find(p: PartRow, secrets: SecretStore): String? = withContext(Dispatchers.IO) {
        val digi = secrets.getDigiKey()
        if (digi != null && (p.distributor == ParsedPart.DIGIKEY || p.distributorPn.trim().uppercase().endsWith("-ND"))) {
            for (pn in listOf(p.distributorPn, p.mpn).map { it.trim() }.filter { it.isNotBlank() }.distinct()) {
                val r = try {
                    DigiKeyApi.details(digi.first, digi.second, pn, 1)
                } catch (e: DigiKeyException) {
                    null
                }
                if (r != null && r.datasheetUrl.isNotBlank()) return@withContext r.datasheetUrl
            }
        }
        val key = secrets.getMouserKey() ?: return@withContext null
        val pns = buildList {
            if (PriceRefresh.hasMouserPn(p.distributor, p.distributorPn, p.productUrl)) add(p.distributorPn.trim())
            if (p.mpn.isNotBlank()) add(p.mpn.trim())
        }.distinct()
        for (pn in pns) {
            val r = MouserApi.lookup(key, pn, 1) ?: continue
            if (r.datasheetUrl.isNotBlank()) return@withContext r.datasheetUrl
        }
        null
    }
}

/**
 * What the Datasheet buttons do: open the saved link; otherwise, with a Mouser key (or DigiKey for DigiKey parts),
 * look the datasheet up, save it on the part and open it; fall back to a web search when none is found.
 */
class DatasheetOpener(
    private val context: Context,
    private val scope: CoroutineScope,
    private val db: InventoryDb,
    private val secrets: SecretStore,
) {
    /** A Mouser or DigiKey key is saved, so a missing datasheet can be looked up. */
    var canLookUp by mutableStateOf(false)
        internal set

    /** Part whose datasheet is being looked up right now; null = none. */
    var busyId by mutableStateOf<Long?>(null)
        private set

    /** True when tapping goes to a web search (no saved link and nothing to look it up with). */
    fun isSearch(p: PartRow): Boolean = Links.isDatasheetSearch(p.datasheet()) && !canLookUp

    fun open(p: PartRow) {
        val sheet = p.datasheet()
        if (sheet.isBlank()) return
        if (!Links.isDatasheetSearch(sheet) || !canLookUp) {
            openLink(context, sheet)
            return
        }
        if (busyId != null) return
        busyId = p.id
        scope.launch {
            val found = try {
                DatasheetLookup.find(p, secrets)
            } catch (e: MouserException) {
                toast(e.message ?: "Mouser lookup failed")
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Datasheet lookup failed")
                null
            }
            busyId = null
            // Normalizes protocol-relative links; anything that isn't a web link counts as not found.
            val url = found?.let { Links.datasheetUrl(it, "", "") }
            if (url != null) {
                withContext(Dispatchers.IO) { db.setDatasheet(p.id, url) }
                openLink(context, url)
            } else {
                toast("No datasheet listed for ${p.key()}; searching the web")
                openLink(context, sheet)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
}

@Composable
fun rememberDatasheetOpener(db: InventoryDb, secrets: SecretStore): DatasheetOpener {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val opener = remember { DatasheetOpener(context, scope, db, secrets) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        opener.canLookUp = withContext(Dispatchers.IO) { secrets.getMouserKey() != null || secrets.getDigiKey() != null }
    }
    return opener
}
