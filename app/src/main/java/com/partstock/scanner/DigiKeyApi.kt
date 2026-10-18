package com.partstock.scanner

import com.partstock.scanner.parse.PartNumberVariants
import com.partstock.scanner.parse.PriceBreaks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DigiKeyPart(
    /** The DigiKey part number that matched (a packaging variation such as ...CT-ND), or the first one listed. */
    val digiKeyPn: String,
    val mpn: String,
    val manufacturer: String,
    val description: String,
    val url: String,
    val unitPrice: Double?,
    /** Quantity of the price break the price comes from. */
    val priceAtQty: Int?,
    val currency: String,
    /** Most specific DigiKey category first, then its parents, e.g. ["Single FETs, MOSFETs", "Transistors"]. */
    val categories: List<String> = emptyList(),
)

/** A problem DigiKey reported (bad credentials, rate limit). The message never contains the secret or a token. */
class DigiKeyException(message: String) : Exception(message)

/**
 * DigiKey Product Information API v4, called straight from the phone with the user's own Client ID and Secret
 * (OAuth2 client credentials). The access token is kept in memory only, until shortly before it expires.
 * Nothing here logs requests, tokens or secrets.
 */
object DigiKeyApi {
    private const val TOKEN_URL = "https://api.digikey.com/v1/oauth2/token"
    private const val BASE = "https://api.digikey.com/products/v4/search"

    private class Token(val clientId: String, val value: String, val expiresAtMs: Long)

    @Volatile private var token: Token? = null

    /** Forget the cached token (e.g. after the credentials change in Settings). */
    fun clearToken() {
        token = null
    }

    private fun accessToken(clientId: String, clientSecret: String): String {
        token?.let { if (it.clientId == clientId && System.currentTimeMillis() < it.expiresAtMs) return it.value }
        val conn = URL(TOKEN_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Accept", "application/json")
            val form = "client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret) +
                "&grant_type=client_credentials"
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = readBody(conn, code)
            when {
                code == 400 || code == 401 || code == 403 ->
                    throw DigiKeyException("DigiKey rejected the Client ID / Secret. Check them in Settings.")
                code == 429 -> throw DigiKeyException("DigiKey rate limit reached. Try again in a minute.")
                code !in 200..299 -> throw DigiKeyException("DigiKey sign-in error (HTTP $code).")
            }
            val json = JSONObject(text)
            val value = json.optString("access_token")
            if (value.isBlank()) throw DigiKeyException("DigiKey sign-in returned no token.")
            val seconds = json.optLong("expires_in", 600L)
            // Renew a little early so a request never goes out with a token about to expire.
            val expires = System.currentTimeMillis() + maxOf(30L, seconds - 60L) * 1000L
            token = Token(clientId, value, expires)
            return value
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Product details for [partNumber] (a DigiKey or manufacturer part number). Null when DigiKey has no such part.
     */
    suspend fun details(clientId: String, clientSecret: String, partNumber: String, qty: Int): DigiKeyPart? =
        withContext(Dispatchers.IO) {
            val path = "$BASE/" + encPath(partNumber.trim()) + "/productdetails"
            val text = request(clientId, clientSecret, path, null) ?: return@withContext null
            val product = JSONObject(text).optJSONObject("Product") ?: return@withContext null
            toPart(product, partNumber, qty)
        }

    /**
     * Keyword search for [keywords]; returns the result whose DigiKey PN or MPN equals [wanted], or else the one
     * within [maxDistance] characters of it (fixes OCR slips), or null.
     */
    suspend fun search(
        clientId: String,
        clientSecret: String,
        keywords: String,
        qty: Int,
        wanted: String = keywords,
        maxDistance: Int = 2,
    ): DigiKeyPart? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("Keywords", keywords.trim()).put("Limit", 25).put("Offset", 0).toString()
        val text = request(clientId, clientSecret, "$BASE/keyword", body) ?: return@withContext null
        val json = JSONObject(text)
        val products = ArrayList<JSONObject>()
        for (key in listOf("ExactMatches", "Products")) {
            val arr = json.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(products::add)
        }
        if (products.isEmpty()) return@withContext null
        val want = norm(wanted)
        // One candidate per DigiKey PN of every packaging variation, plus each product's MPN.
        val pns = ArrayList<String>()
        val owner = ArrayList<JSONObject>()
        for (p in products) {
            for (v in variations(p)) {
                pns.add(v.optString("DigiKeyProductNumber")); owner.add(p)
            }
        }
        val mpns = products.map { it.optString("ManufacturerProductNumber") }
        val exact = pns.indexOfFirst { norm(it) == want }.takeIf { it >= 0 }?.let { owner[it] }
            ?: mpns.indexOfFirst { norm(it) == want }.takeIf { it >= 0 }?.let { products[it] }
        if (exact != null) return@withContext toPart(exact, wanted, qty)
        var i = PartNumberVariants.closest(pns, wanted, maxDistance)
        if (i >= 0) return@withContext toPart(owner[i], pns[i], qty)
        i = PartNumberVariants.closest(mpns, wanted, maxDistance)
        if (i >= 0) return@withContext toPart(products[i], wanted, qty)
        null
    }

    /** GET (body null) or POST JSON. Returns the body, or null for 404 (no such part). */
    private fun request(clientId: String, clientSecret: String, url: String, body: String?): String? {
        var retried = false
        while (true) {
            val bearer = accessToken(clientId, clientSecret)
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = if (body == null) "GET" else "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $bearer")
                conn.setRequestProperty("X-DIGIKEY-Client-Id", clientId)
                conn.setRequestProperty("X-DIGIKEY-Locale-Site", "US")
                conn.setRequestProperty("X-DIGIKEY-Locale-Language", "en")
                conn.setRequestProperty("X-DIGIKEY-Locale-Currency", "USD")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val text = readBody(conn, code)
                when {
                    code == 401 && !retried -> {
                        // Token revoked or expired early: get a fresh one once.
                        token = null
                        retried = true
                        continue
                    }
                    code == 401 || code == 403 ->
                        throw DigiKeyException("DigiKey rejected the Client ID / Secret. Check them in Settings.")
                    code == 404 -> return null
                    code == 429 -> throw DigiKeyException("DigiKey rate limit reached. Try again in a minute.")
                    code !in 200..299 -> throw DigiKeyException("DigiKey error (HTTP $code).")
                }
                return text
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun variations(product: JSONObject): List<JSONObject> {
        val arr: JSONArray = product.optJSONArray("ProductVariations") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /** Builds the result; the price comes from the variation matching [wanted] (the label's DigiKey PN) if any. */
    internal fun toPart(p: JSONObject, wanted: String, qty: Int): DigiKeyPart {
        val vars = variations(p)
        val want = norm(wanted)
        val variation = vars.firstOrNull { norm(it.optString("DigiKeyProductNumber")) == want }
            ?: vars.firstOrNull { (it.optJSONArray("StandardPricing")?.length() ?: 0) > 0 }
            ?: vars.firstOrNull()

        var price: Double? = null
        var priceQty: Int? = null
        val pricing = variation?.optJSONArray("StandardPricing")
        if (pricing != null && pricing.length() > 0) {
            val qtys = IntArray(pricing.length()) { pricing.optJSONObject(it)?.optInt("BreakQuantity", Int.MAX_VALUE) ?: Int.MAX_VALUE }
            val i = PriceBreaks.pick(qtys, qty)
            if (i >= 0) {
                val b = pricing.getJSONObject(i)
                price = b.optDouble("UnitPrice").takeIf { !it.isNaN() && it >= 0 }
                priceQty = b.optInt("BreakQuantity")
            }
        }
        if (price == null) {
            price = p.optDouble("UnitPrice").takeIf { !it.isNaN() && it > 0 }
            priceQty = if (price != null) 1 else null
        }

        val desc = p.optJSONObject("Description")
        val description = desc?.optString("ProductDescription").orEmpty().ifBlank { desc?.optString("DetailedDescription").orEmpty() }

        // Category tree: the deepest child is the most specific name.
        val cats = ArrayList<String>()
        var c = p.optJSONObject("Category")
        while (c != null) {
            c.optString("Name").takeIf { it.isNotBlank() }?.let { cats.add(0, it) }
            c = c.optJSONArray("ChildCategories")?.optJSONObject(0)
        }

        return DigiKeyPart(
            digiKeyPn = variation?.optString("DigiKeyProductNumber").orEmpty(),
            mpn = p.optString("ManufacturerProductNumber"),
            manufacturer = p.optJSONObject("Manufacturer")?.optString("Name").orEmpty(),
            description = description,
            url = p.optString("ProductUrl"),
            unitPrice = price,
            priceAtQty = priceQty,
            currency = "USD",
            categories = cats,
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Path segment: URLEncoder writes spaces as '+', which a path doesn't decode; '#' and '/' must be escaped. */
    private fun encPath(s: String) = enc(s).replace("+", "%20")

    private fun norm(s: String) = s.uppercase().replace(Regex("\\s+"), "")
}
