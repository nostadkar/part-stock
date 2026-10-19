package com.partstock.scanner

import com.partstock.scanner.parse.BatchRules
import com.partstock.scanner.parse.PartNumberVariants
import com.partstock.scanner.parse.PartSpecs
import com.partstock.scanner.parse.PriceRefresh
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a background lookup found, kept with a queued batch bag and saved with it. */
data class LookupFound(
    val source: String,
    val mpn: String,
    val description: String,
    val manufacturer: String,
    /** The distributor's part number; only used on a label from that distributor. */
    val distributorPn: String,
    val unitPrice: Double?,
    val url: String,
    val datasheetUrl: String,
    /** Distributor categories, most specific first (fed to Categories.classify). */
    val categories: List<String>,
    val specs: PartSpecs,
)

/** [state] is one of the BatchRules.LOOKUP_* values; [note] is a short line for the batch card. */
data class LookupResult(val state: String, val found: LookupFound? = null, val note: String = "")

/**
 * Spaces out API requests made in the background so a batch of bags stays under the distributors' limits
 * (Mouser allows about 30 searches a minute; DigiKey is more generous, one a second is plenty).
 */
object ApiPacer {
    private val mutex = Mutex()
    private var lastMouser = 0L
    private var lastDigiKey = 0L
    private const val DIGIKEY_PACE_MS = 1000L

    suspend fun mouser() = mutex.withLock {
        val wait = lastMouser + PriceRefresh.MOUSER_PACE_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastMouser = System.currentTimeMillis()
    }

    suspend fun digiKey() = mutex.withLock {
        val wait = lastDigiKey + DIGIKEY_PACE_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastDigiKey = System.currentTimeMillis()
    }
}

/**
 * Background part lookups for batch scanning: the same search steps as the review screen (exact, nearest within two
 * characters, start of the number, look-alike swaps), each request paced by [ApiPacer]. A rate limit or rejected key
 * is thrown (MouserException / DigiKeyException) so the caller can wait or stop.
 */
object PartLookup {

    /** [pn] is the Mouser P/N on Mouser labels, otherwise the MPN. */
    suspend fun mouser(key: String, pn: String, qty: Int, isMouserLabel: Boolean): LookupResult {
        if (pn.isBlank()) return LookupResult(BatchRules.LOOKUP_NOT_FOUND, note = "No part number to look up")
        suspend fun ask(q: String, wanted: String = q, maxDistance: Int = 0): MouserPart? {
            ApiPacer.mouser()
            return MouserApi.lookup(key, q, qty, wanted = wanted, maxDistance = maxDistance)
        }
        var found = ask(pn, maxDistance = 2)
        if (found == null) {
            val stem = pn.substringBefore('#').let { if (it.length < pn.length) it else pn.dropLast(3) }
            if (stem.length >= 6) found = ask(stem, wanted = pn, maxDistance = 2)
        }
        if (found == null) {
            for (v in PartNumberVariants.confusions(pn, 6)) {
                found = ask(v) ?: continue
                break
            }
        }
        val r = found ?: return LookupResult(BatchRules.LOOKUP_NOT_FOUND, note = "Mouser has no match for $pn")
        val exact = BatchRules.exactHit(pn, r.mouserPn, r.mpn)
        val f = LookupFound(
            source = "Mouser", mpn = r.mpn, description = r.description, manufacturer = r.manufacturer,
            distributorPn = if (isMouserLabel) r.mouserPn else "", unitPrice = r.unitPrice, url = r.url,
            datasheetUrl = r.datasheetUrl, categories = listOf(r.category).filter { it.isNotBlank() }, specs = r.specs,
        )
        val price = r.unitPrice?.let { "${r.currency} ${InventoryDb.fmtCost(it)} each" } ?: "no price listed"
        return if (exact) LookupResult(BatchRules.LOOKUP_EXACT, f, "Mouser: $price")
        else LookupResult(BatchRules.LOOKUP_CORRECTED, f, "Mouser: $pn → ${r.mouserPn.ifBlank { r.mpn }}?")
    }

    /** Tries the DigiKey part number, then the MPN. [labelMpn] is compared with DigiKey's MPN too. */
    suspend fun digiKey(id: String, secret: String, dpn: String, labelMpn: String, qty: Int): LookupResult {
        val tries = listOf(dpn, labelMpn).filter { it.isNotBlank() }.distinct()
        if (tries.isEmpty()) return LookupResult(BatchRules.LOOKUP_NOT_FOUND, note = "No part number to look up")
        var found: DigiKeyPart? = null
        var exact = false
        var tried = ""
        for (pn in tries) {
            tried = pn
            ApiPacer.digiKey()
            found = DigiKeyApi.details(id, secret, pn, qty)
            exact = found != null && BatchRules.exactHit(pn, found.digiKeyPn, found.mpn)
            if (found == null) {
                ApiPacer.digiKey()
                found = DigiKeyApi.search(id, secret, pn, qty, maxDistance = 2)
                exact = found != null && BatchRules.exactHit(pn, found.digiKeyPn, found.mpn)
            }
            if (found == null) {
                val stem = if (pn.uppercase().endsWith("-ND")) pn.dropLast(6) else pn.dropLast(3)
                if (stem.length >= 6) {
                    ApiPacer.digiKey()
                    found = DigiKeyApi.search(id, secret, stem, qty, wanted = pn, maxDistance = 2)
                }
            }
            if (found == null) {
                for (v in PartNumberVariants.confusions(pn, 6)) {
                    ApiPacer.digiKey()
                    found = DigiKeyApi.details(id, secret, v, qty) ?: continue
                    break
                }
            }
            if (found != null) break
        }
        val r = found ?: return LookupResult(BatchRules.LOOKUP_NOT_FOUND, note = "DigiKey has no match for $tried")
        // The label's MPN must agree too (when one was read), or the bag needs a look.
        if (exact && labelMpn.isNotBlank() && r.mpn.isNotBlank() && !BatchRules.exactHit(labelMpn, r.mpn, null)) exact = false
        val f = LookupFound(
            source = "DigiKey", mpn = r.mpn, description = r.description, manufacturer = r.manufacturer,
            distributorPn = r.digiKeyPn, unitPrice = r.unitPrice, url = r.url, datasheetUrl = r.datasheetUrl,
            categories = r.categories, specs = PartSpecs.EMPTY,
        )
        val price = r.unitPrice?.let { "${r.currency} ${InventoryDb.fmtCost(it)} each" } ?: "no price listed"
        return if (exact) LookupResult(BatchRules.LOOKUP_EXACT, f, "DigiKey: $price")
        else LookupResult(BatchRules.LOOKUP_CORRECTED, f, "DigiKey: $tried → ${r.digiKeyPn.ifBlank { r.mpn }}?")
    }
}
