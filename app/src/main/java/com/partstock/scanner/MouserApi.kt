package com.partstock.scanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.partstock.scanner.parse.PartNumberVariants
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class MouserPart(
    val mouserPn: String,
    val mpn: String,
    val manufacturer: String,
    val description: String,
    val url: String,
    val unitPrice: Double?,
    /** Quantity of the price break the price comes from. */
    val priceAtQty: Int?,
    val currency: String,
)

/** A problem Mouser reported (bad key, rate limit). The message is safe to show: it never contains the key. */
class MouserException(message: String) : Exception(message)

/** Mouser Search API v1, part number search. Called straight from the phone with the user's own key. */
object MouserApi {
    private const val ENDPOINT = "https://api.mouser.com/api/v1/search/partnumber"

    /**
     * Searches Mouser for [partNumber] and returns the result matching [wanted] (a Mouser or manufacturer part
     * number) exactly, or else the one within [maxDistance] characters of it (fixes OCR slips like PBE → PBF).
     */
    suspend fun lookup(
        apiKey: String,
        partNumber: String,
        qty: Int,
        wanted: String = partNumber,
        maxDistance: Int = 0,
    ): MouserPart? = withContext(Dispatchers.IO) {
        val conn = URL("$ENDPOINT?apiKey=" + URLEncoder.encode(apiKey, "UTF-8")).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            val body = JSONObject()
                .put("SearchByPartRequest", JSONObject().put("mouserPartNumber", partNumber))
                .toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                code == 401 || code == 403 -> throw MouserException("Mouser rejected the API key. Check it in Settings.")
                code == 429 -> throw MouserException("Mouser rate limit reached. Try again in a minute.")
                code !in 200..299 -> throw MouserException("Mouser error (HTTP $code).")
            }
            parse(text, wanted, qty, maxDistance)
        } finally {
            conn.disconnect()
        }
    }

    internal fun parse(text: String, wanted: String, qty: Int, maxDistance: Int = 0): MouserPart? {
        val json = JSONObject(text)
        val errors = json.optJSONArray("Errors")
        if (errors != null && errors.length() > 0) {
            val msg = errors.getJSONObject(0).optString("Message", "")
            throw MouserException(msg.ifBlank { "Mouser returned an error." })
        }
        val parts = json.optJSONObject("SearchResults")?.optJSONArray("Parts") ?: return null
        if (parts.length() == 0) return null

        // Only trust an exact match (or a single result): a different part would carry the wrong price.
        val want = norm(wanted)
        var pick: JSONObject? = null
        for (i in 0 until parts.length()) {
            val o = parts.getJSONObject(i)
            if (norm(o.optString("MouserPartNumber")) == want || norm(o.optString("ManufacturerPartNumber")) == want) {
                pick = o
                break
            }
        }
        if (pick == null && maxDistance > 0) {
            // No exact hit: take the nearest Mouser part number, if it's clearly the nearest.
            val pns = (0 until parts.length()).map { parts.getJSONObject(it).optString("MouserPartNumber") }
            var i = PartNumberVariants.closest(pns, wanted, maxDistance)
            if (i < 0) {
                val mpns = (0 until parts.length()).map { parts.getJSONObject(it).optString("ManufacturerPartNumber") }
                i = PartNumberVariants.closest(mpns, wanted, maxDistance)
            }
            if (i >= 0) pick = parts.getJSONObject(i)
        }
        val o = pick ?: return null

        var price: Double? = null
        var priceQty: Int? = null
        var currency = ""
        val breaks = o.optJSONArray("PriceBreaks")
        if (breaks != null) {
            var best: JSONObject? = null
            for (i in 0 until breaks.length()) {
                val b = breaks.getJSONObject(i)
                val q = b.optInt("Quantity", Int.MAX_VALUE)
                if (q <= qty && (best == null || q > best.optInt("Quantity", 0))) best = b
            }
            if (best == null && breaks.length() > 0) best = breaks.getJSONObject(0) // below the first break
            if (best != null) {
                price = parsePrice(best.optString("Price"))
                priceQty = best.optInt("Quantity")
                currency = best.optString("Currency")
            }
        }
        return MouserPart(
            mouserPn = o.optString("MouserPartNumber"),
            mpn = o.optString("ManufacturerPartNumber"),
            manufacturer = o.optString("Manufacturer"),
            description = o.optString("Description"),
            url = o.optString("ProductDetailUrl"),
            unitPrice = price,
            priceAtQty = priceQty,
            currency = currency,
        )
    }

    /** "$1,234.56" -> 1234.56, "0,123 €" -> 0.123. */
    internal fun parsePrice(s: String): Double? {
        var t = s.filter { it.isDigit() || it == '.' || it == ',' }
        if (t.isEmpty()) return null
        t = if (t.contains('.')) t.replace(",", "") else t.replace(',', '.')
        return t.toDoubleOrNull()
    }

    private fun norm(s: String) = s.uppercase().replace(Regex("\\s+"), "")
}
