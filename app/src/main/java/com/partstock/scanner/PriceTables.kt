package com.partstock.scanner

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A distributor's price breaks for one part: [qty] ascending, [price] per piece at each. */
class PriceTable(val qty: IntArray, val price: DoubleArray, val currency: String, val source: String) {
    val isEmpty: Boolean get() = qty.isEmpty()

    companion object {
        /** Sorted by quantity, dropping breaks without a usable price; null when none are left. */
        fun of(pairs: List<Pair<Int, Double?>>, currency: String, source: String): PriceTable? {
            val ok = pairs.filter { (q, p) -> q in 1 until Int.MAX_VALUE && p != null && p >= 0 }
                .distinctBy { it.first }.sortedBy { it.first }
            if (ok.isEmpty()) return null
            return PriceTable(ok.map { it.first }.toIntArray(), ok.map { it.second!! }.toDoubleArray(), currency, source)
        }
    }
}

/**
 * Price breaks from the most recent Mouser / DigiKey lookup of each part while the app runs. They aren't saved in
 * the database, so the part page shows its price-break chart only after a lookup or price refresh this session.
 */
object PriceTables {
    private val byPn = ConcurrentHashMap<String, PriceTable>()

    fun put(partNumbers: List<String>, table: PriceTable?) {
        if (table == null) return
        partNumbers.filter { it.isNotBlank() }.forEach { byPn[norm(it)] = table }
    }

    fun forPart(p: PartRow): PriceTable? =
        listOf(p.distributorPn, p.mpn).filter { it.isNotBlank() }.firstNotNullOfOrNull { byPn[norm(it)] }

    private fun norm(s: String) = s.trim().uppercase(Locale.US)
}
