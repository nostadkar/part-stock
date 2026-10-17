package com.partstock.scanner

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.partstock.scanner.parse.Categories
import com.partstock.scanner.parse.Links
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PartRow(
    val id: Long,
    val mpn: String,
    val description: String,
    val manufacturer: String,
    val quantity: Int,
    val location: String,
    val distributor: String,
    val distributorPn: String,
    val customerPn: String,
    val source: String,
    val notes: String,
    val dateCode: String,
    val refDes: String,
    val scans: Int,
    val updatedAt: String,
    val unitCost: Double?,
    val productUrl: String,
    val category: String,
)

/** Saved link, or one built from the part numbers for rows saved before links existed. */
fun PartRow.link(): String =
    productUrl.ifBlank { Links.productUrl(distributor, distributorPn, mpn).orEmpty() }

data class Match(val row: PartRow, val matchedOn: String)

/** One save from the review screen. */
data class ScanEntry(
    val mpn: String,
    val description: String,
    val manufacturer: String,
    val quantity: Int,
    val addMode: Boolean,
    val location: String,
    val distributor: String,
    val distributorPn: String,
    val customerPn: String,
    val source: String,
    val notes: String,
    val dateCode: String,
    val refDes: String,
    val raw: String,
    val unitCost: Double?,
    val productUrl: String,
    val category: String,
    /** Description came from Mouser's listing, so it replaces the one saved from a label. */
    val descriptionFromMouser: Boolean = false,
)

/** Inventory stored on the phone: one row per part, plus a log of every save. */
class InventoryDb(context: Context) : SQLiteOpenHelper(context, "inventory.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE parts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                mpn TEXT NOT NULL DEFAULT '', description TEXT NOT NULL DEFAULT '',
                manufacturer TEXT NOT NULL DEFAULT '', quantity INTEGER NOT NULL DEFAULT 0,
                location TEXT NOT NULL DEFAULT '', distributor TEXT NOT NULL DEFAULT '',
                distributor_pn TEXT NOT NULL DEFAULT '', customer_pn TEXT NOT NULL DEFAULT '',
                source TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '',
                date_code TEXT NOT NULL DEFAULT '', ref_des TEXT NOT NULL DEFAULT '',
                scans INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL DEFAULT '',
                unit_cost REAL, product_url TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL(
            """CREATE TABLE scan_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT, part_key TEXT, mode TEXT,
                qty INTEGER, total INTEGER, location TEXT, source TEXT,
                distributor_pn TEXT, customer_pn TEXT, raw TEXT)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE parts ADD COLUMN unit_cost REAL")
            db.execSQL("ALTER TABLE parts ADD COLUMN product_url TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) {
            // Adds a column only; existing parts are sorted into buckets from their description.
            db.execSQL("ALTER TABLE parts ADD COLUMN category TEXT NOT NULL DEFAULT ''")
            val rows = db.rawQuery("SELECT id, description, notes FROM parts", null).use { c ->
                buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())) }
            }
            for ((id, desc, notes) in rows) {
                db.execSQL("UPDATE parts SET category = ? WHERE id = ?", arrayOf(Categories.classify(desc, notes), id))
            }
        }
    }

    /** Matches on MPN, then customer PN, then distributor PN, ignoring case and spaces. */
    fun find(mpn: String, customerPn: String, distributorPn: String): Match? {
        val checks = listOf(
            "mpn" to mpn to "MPN",
            "customer_pn" to customerPn to "customer PN",
            "distributor_pn" to distributorPn to "distributor PN",
        )
        for ((pair, label) in checks) {
            val (column, key) = pair
            val k = norm(key)
            if (k.isEmpty()) continue
            val row = readableDatabase.rawQuery(
                "SELECT * FROM parts WHERE UPPER(REPLACE($column, ' ', '')) = ? LIMIT 1", arrayOf(k)
            ).use { c -> if (c.moveToFirst()) c.toRow() else null }
            if (row != null) return Match(row, label)
        }
        return null
    }

    /** Adds or updates the part and logs the scan. Returns the new quantity. */
    fun save(e: ScanEntry): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = now()
            val hit = find(e.mpn, e.customerPn, e.distributorPn)
            val total: Int
            if (hit != null) {
                val r = hit.row
                total = if (e.addMode) r.quantity + e.quantity else e.quantity
                val v = ContentValues().apply {
                    put("quantity", total)
                    // Fill blanks from this label without overwriting what's there.
                    fill("mpn", r.mpn, e.mpn)
                    if (e.descriptionFromMouser && e.description.isNotBlank()) put("description", e.description)
                    else fill("description", r.description, e.description)
                    if (e.category.isNotBlank()) put("category", e.category)
                    fill("manufacturer", r.manufacturer, e.manufacturer)
                    fill("distributor", r.distributor, e.distributor)
                    fill("distributor_pn", r.distributorPn, e.distributorPn)
                    fill("customer_pn", r.customerPn, e.customerPn)
                    fill("date_code", r.dateCode, e.dateCode)
                    fill("ref_des", r.refDes, e.refDes)
                    if (e.location.isNotBlank()) put("location", e.location)
                    // Newest price and link win: they come from the latest lookup or an edit.
                    if (e.unitCost != null) put("unit_cost", e.unitCost)
                    if (e.productUrl.isNotBlank()) put("product_url", e.productUrl)
                    put("source", appendUnique(r.source, e.source))
                    put("notes", appendUnique(r.notes, e.notes))
                    put("scans", r.scans + 1)
                    put("updated_at", now)
                }
                db.update("parts", v, "id = ?", arrayOf(r.id.toString()))
            } else {
                total = e.quantity
                db.insert("parts", null, ContentValues().apply {
                    put("mpn", e.mpn); put("description", e.description); put("manufacturer", e.manufacturer)
                    put("quantity", e.quantity); put("location", e.location); put("distributor", e.distributor)
                    put("distributor_pn", e.distributorPn); put("customer_pn", e.customerPn)
                    put("source", e.source); put("notes", e.notes); put("date_code", e.dateCode)
                    put("ref_des", e.refDes); put("scans", 1); put("updated_at", now)
                    put("product_url", e.productUrl); put("category", e.category)
                    if (e.unitCost != null) put("unit_cost", e.unitCost)
                })
            }
            db.insert("scan_log", null, ContentValues().apply {
                put("ts", now)
                put("part_key", e.mpn.ifBlank { e.customerPn.ifBlank { e.distributorPn } })
                put("mode", if (e.addMode) "add" else "set")
                put("qty", e.quantity); put("total", total); put("location", e.location)
                put("source", e.source); put("distributor_pn", e.distributorPn)
                put("customer_pn", e.customerPn); put("raw", e.raw)
            })
            db.setTransactionSuccessful()
            return total
        } finally {
            db.endTransaction()
        }
    }

    fun all(): List<PartRow> =
        readableDatabase.rawQuery("SELECT * FROM parts ORDER BY category, UPPER(mpn), UPPER(customer_pn)", null).use { c ->
            buildList { while (c.moveToNext()) add(c.toRow()) }
        }

    fun update(id: Long, quantity: Int, location: String, description: String, unitCost: Double?, category: String) {
        val v = ContentValues().apply {
            put("quantity", quantity); put("location", location.trim()); put("category", category)
            put("description", description.trim()); put("updated_at", now())
        }
        if (unitCost != null) v.put("unit_cost", unitCost) else v.putNull("unit_cost")
        writableDatabase.update("parts", v, "id = ?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("parts", "id = ?", arrayOf(id.toString()))
    }

    fun inventoryCsv(): String {
        val header = listOf(
            "Category", "MPN", "Description", "Manufacturer", "Quantity", "Unit Cost", "Total Value", "Location",
            "Distributor", "Distributor PN", "Customer PN", "Link", "Source", "Notes", "Date Code", "Ref Des",
            "Scans", "Last Updated",
        )
        val rows = all().map {
            listOf(
                it.category, it.mpn, it.description, it.manufacturer, it.quantity.toString(),
                it.unitCost?.let(::plain).orEmpty(),
                it.unitCost?.let { c -> plain(c * it.quantity) }.orEmpty(),
                it.location, it.distributor, it.distributorPn, it.customerPn, it.link(), it.source, it.notes,
                it.dateCode, it.refDes, it.scans.toString(), it.updatedAt,
            )
        }
        return csv(header, rows)
    }

    fun logCsv(): String {
        val header = listOf(
            "Timestamp", "Key", "Mode", "Qty", "New Total", "Location", "Source", "Distributor PN", "Customer PN",
            "Raw Text",
        )
        val rows = readableDatabase.rawQuery(
            "SELECT ts, part_key, mode, qty, total, location, source, distributor_pn, customer_pn, raw " +
                "FROM scan_log ORDER BY id", null
        ).use { c ->
            buildList { while (c.moveToNext()) add((0 until 10).map { c.getString(it).orEmpty() }) }
        }
        return csv(header, rows)
    }

    private fun ContentValues.fill(column: String, current: String, new: String) {
        if (current.isBlank() && new.isNotBlank()) put(column, new)
    }

    private fun Cursor.str(col: String) = getString(getColumnIndexOrThrow(col)).orEmpty()
    private fun Cursor.int(col: String) = getInt(getColumnIndexOrThrow(col))

    private fun Cursor.toRow(): PartRow {
        val costIdx = getColumnIndexOrThrow("unit_cost")
        return PartRow(
            id = getLong(getColumnIndexOrThrow("id")),
            mpn = str("mpn"), description = str("description"), manufacturer = str("manufacturer"),
            quantity = int("quantity"), location = str("location"), distributor = str("distributor"),
            distributorPn = str("distributor_pn"), customerPn = str("customer_pn"), source = str("source"),
            notes = str("notes"), dateCode = str("date_code"), refDes = str("ref_des"), scans = int("scans"),
            updatedAt = str("updated_at"),
            unitCost = if (isNull(costIdx)) null else getDouble(costIdx),
            productUrl = str("product_url"),
            category = str("category").ifBlank { Categories.OTHER },
        )
    }

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        fun now(): String = LocalDateTime.now().format(STAMP)
        fun norm(s: String) = s.uppercase().replace(Regex("\\s+"), "")

        fun appendUnique(existing: String, add: String): String {
            val cur = existing.trim()
            val a = add.trim()
            if (a.isEmpty() || cur.contains(a)) return cur
            return if (cur.isEmpty()) a else "$cur; $a"
        }

        /** Plain number for CSV (no currency symbol, no exponent). */
        fun plain(d: Double): String =
            BigDecimal.valueOf(d).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

        /** Unit cost for display: at least 2 decimals, up to 4 (parts can cost fractions of a cent). */
        fun fmtCost(d: Double): String {
            val s = BigDecimal.valueOf(d).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros()
            return (if (s.scale() < 2) s.setScale(2) else s).toPlainString()
        }

        fun fmtMoney(d: Double): String = String.format(Locale.US, "%.2f", d)

        /** RFC 4180 CSV; imports cleanly into Google Sheets and Excel. */
        fun csv(header: List<String>, rows: List<List<String>>): String {
            fun cell(s: String): String =
                if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
            val sb = StringBuilder()
            (listOf(header) + rows).forEach { r -> sb.append(r.joinToString(",") { cell(it) }).append("\r\n") }
            return sb.toString()
        }
    }
}
