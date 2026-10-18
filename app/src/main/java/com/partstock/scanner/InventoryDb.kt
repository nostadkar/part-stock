package com.partstock.scanner

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.partstock.scanner.parse.Categories
import com.partstock.scanner.parse.InventoryCsv
import com.partstock.scanner.parse.Links
import com.partstock.scanner.parse.ReorderList
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
    /** Low-stock minimum; null = not tracked. */
    val minQty: Int? = null,
    /** The user chose the category, so lookups and price refreshes leave it alone. */
    val categoryPicked: Boolean = false,
    /** Where the unit cost came from ("Mouser", "DigiKey") and when; blank for a typed or imported price. */
    val priceSource: String = "",
    val priceCheckedAt: String = "",
)

/** At or below its minimum (the Inventory screen's "Low stock" filter). */
fun PartRow.isLow(): Boolean = ReorderList.isLow(quantity, minQty)

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
    /** The user picked [category] by hand. */
    val categoryPicked: Boolean = false,
    /** [unitCost] came from this distributor's lookup just now ("Mouser", "DigiKey"); blank otherwise. */
    val priceSource: String = "",
    /** Identifies the physical bag (see ParsedPart.bagFingerprint); blank when the label can't. */
    val bagFingerprint: String = "",
    /** Entrust kit label on the bag: kit header, per-board qty and ref des, stored as kit membership. */
    val kitName: String = "",
    val kitPerBoard: Int? = null,
    val kitQuantity: Int? = null,
    val kitItem: Int? = null,
)

/** A kit with parts registered from scanned Entrust kit labels. */
data class KitSummary(val kit: String, val parts: Int, val kitQuantity: Int?)

/** One part of a kit build: [need] = per board × boards. */
data class KitLine(val part: PartRow, val perBoard: Int, val refDes: String, val need: Int) {
    val after: Int get() = part.quantity - need
}

/** An earlier save of the same bag. */
data class BagHit(val timestamp: String, val quantity: Int, val mode: String)

/** One part's result from a distributor lookup, applied by [InventoryDb.applyLookup]. */
data class LookupUpdate(
    val unitCost: Double?,
    val description: String,
    /** Bucket worked out from the distributor's category; ignored when the user picked one. */
    val category: String,
    val productUrl: String,
    val source: String,
)

/** What a CSV restore did (or would do, for a dry run). */
data class ImportSummary(val rows: Int, val added: Int, val updated: Int, val removed: Int)

/** Inventory stored on the phone: one row per part, plus a log of every save. */
class InventoryDb(context: Context) : SQLiteOpenHelper(context, "inventory.db", null, 7) {

    /** Called after every change to the parts table (used for auto backup). */
    @Volatile var onChanged: (() -> Unit)? = null

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
                category TEXT NOT NULL DEFAULT '', min_qty INTEGER,
                category_picked INTEGER NOT NULL DEFAULT 0, price_source TEXT NOT NULL DEFAULT '',
                price_checked_at TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL(
            """CREATE TABLE scan_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT, part_key TEXT, mode TEXT,
                qty INTEGER, total INTEGER, location TEXT, source TEXT,
                distributor_pn TEXT, customer_pn TEXT, raw TEXT, bag_fp TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS scan_log_bag_fp ON scan_log(bag_fp)")
        createKitItems(db)
    }

    private fun createKitItems(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS kit_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT, kit TEXT NOT NULL, part_id INTEGER NOT NULL,
                per_board INTEGER NOT NULL, ref_des TEXT NOT NULL DEFAULT '', item INTEGER, kit_qty INTEGER,
                updated_at TEXT NOT NULL DEFAULT '', UNIQUE(kit, part_id))"""
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
        if (oldVersion < 4) {
            // Adds a column and an index only; earlier scans have no fingerprint and never warn.
            db.execSQL("ALTER TABLE scan_log ADD COLUMN bag_fp TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS scan_log_bag_fp ON scan_log(bag_fp)")
        }
        if (oldVersion < 5) {
            // New table only; nothing existing is touched. Kits fill in as kit labels are scanned.
            createKitItems(db)
        }
        if (oldVersion < 6) {
            // Adds a nullable column only; existing parts have no minimum until one is set.
            db.execSQL("ALTER TABLE parts ADD COLUMN min_qty INTEGER")
        }
        if (oldVersion < 7) {
            // Adds columns only. Existing categories count as not picked; existing prices have no source date.
            db.execSQL("ALTER TABLE parts ADD COLUMN category_picked INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE parts ADD COLUMN price_source TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE parts ADD COLUMN price_checked_at TEXT NOT NULL DEFAULT ''")
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
    fun save(e: ScanEntry): Int = saveTx(e).also { onChanged?.invoke() }

    private fun saveTx(e: ScanEntry): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = now()
            val hit = find(e.mpn, e.customerPn, e.distributorPn)
            val total: Int
            val partId: Long
            if (hit != null) {
                val r = hit.row
                total = if (e.addMode) r.quantity + e.quantity else e.quantity
                val v = ContentValues().apply {
                    put("quantity", total)
                    // Fill blanks from this label without overwriting what's there.
                    fill("mpn", r.mpn, e.mpn)
                    if (e.descriptionFromMouser && e.description.isNotBlank()) put("description", e.description)
                    else fill("description", r.description, e.description)
                    // A category the user picked earlier stays unless they pick another one now.
                    if (e.category.isNotBlank() && (e.categoryPicked || !r.categoryPicked)) put("category", e.category)
                    if (e.categoryPicked) put("category_picked", 1)
                    fill("manufacturer", r.manufacturer, e.manufacturer)
                    fill("distributor", r.distributor, e.distributor)
                    fill("distributor_pn", r.distributorPn, e.distributorPn)
                    fill("customer_pn", r.customerPn, e.customerPn)
                    fill("date_code", r.dateCode, e.dateCode)
                    fill("ref_des", r.refDes, e.refDes)
                    if (e.location.isNotBlank()) put("location", e.location)
                    // Newest price and link win: they come from the latest lookup or an edit.
                    if (e.unitCost != null) put("unit_cost", e.unitCost)
                    if (e.unitCost != null && e.priceSource.isNotBlank()) {
                        put("price_source", e.priceSource); put("price_checked_at", now)
                    } else if (e.unitCost != null && e.unitCost != r.unitCost) {
                        put("price_source", ""); put("price_checked_at", "") // typed in: no longer from a lookup
                    }
                    if (e.productUrl.isNotBlank()) put("product_url", e.productUrl)
                    put("source", appendUnique(r.source, e.source))
                    put("notes", appendUnique(r.notes, e.notes))
                    put("scans", r.scans + 1)
                    put("updated_at", now)
                }
                db.update("parts", v, "id = ?", arrayOf(r.id.toString()))
                partId = r.id
            } else {
                total = e.quantity
                partId = db.insert("parts", null, ContentValues().apply {
                    put("mpn", e.mpn); put("description", e.description); put("manufacturer", e.manufacturer)
                    put("quantity", e.quantity); put("location", e.location); put("distributor", e.distributor)
                    put("distributor_pn", e.distributorPn); put("customer_pn", e.customerPn)
                    put("source", e.source); put("notes", e.notes); put("date_code", e.dateCode)
                    put("ref_des", e.refDes); put("scans", 1); put("updated_at", now)
                    put("product_url", e.productUrl); put("category", e.category)
                    if (e.categoryPicked) put("category_picked", 1)
                    if (e.unitCost != null) put("unit_cost", e.unitCost)
                    if (e.unitCost != null && e.priceSource.isNotBlank()) {
                        put("price_source", e.priceSource); put("price_checked_at", now)
                    }
                })
            }
            db.insert("scan_log", null, ContentValues().apply {
                put("ts", now)
                put("part_key", e.mpn.ifBlank { e.customerPn.ifBlank { e.distributorPn } })
                put("mode", if (e.addMode) "add" else "set")
                put("qty", e.quantity); put("total", total); put("location", e.location)
                put("source", e.source); put("distributor_pn", e.distributorPn)
                put("customer_pn", e.customerPn); put("raw", e.raw); put("bag_fp", e.bagFingerprint)
            })
            if (e.kitName.isNotBlank() && e.kitPerBoard != null && e.kitPerBoard > 0 && partId > 0) {
                db.insertWithOnConflict("kit_items", null, ContentValues().apply {
                    put("kit", e.kitName.trim()); put("part_id", partId); put("per_board", e.kitPerBoard)
                    put("ref_des", e.refDes); put("updated_at", now)
                    if (e.kitItem != null) put("item", e.kitItem)
                    if (e.kitQuantity != null) put("kit_qty", e.kitQuantity)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
            return total
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Loads parts from an inventory CSV. Replace wipes the parts table first (the scan log is kept); Merge matches each
     * row like a scan (MPN, then customer PN, then distributor PN), sets the quantity from the file and takes the file's
     * non-blank fields. With [dryRun] the work is rolled back and only the counts are returned.
     */
    fun importRows(rows: List<InventoryCsv.Row>, replace: Boolean, dryRun: Boolean): ImportSummary {
        val db = writableDatabase
        var added = 0
        var updated = 0
        var removed = 0
        db.beginTransaction()
        try {
            val now = now()
            if (replace) removed = db.delete("parts", null, null)
            for (r in rows) {
                val category = r.category.ifBlank { Categories.classify(r.description, r.notes) }
                val stamp = r.updatedAt.ifBlank { now }
                val hit = find(r.mpn, r.customerPn, r.distributorPn)
                if (hit != null) {
                    val old = hit.row
                    db.update("parts", ContentValues().apply {
                        put("quantity", r.quantity)
                        take("mpn", r.mpn); take("description", r.description); take("manufacturer", r.manufacturer)
                        take("location", r.location); take("distributor", r.distributor)
                        take("distributor_pn", r.distributorPn); take("customer_pn", r.customerPn)
                        take("product_url", r.link); take("source", r.source); take("notes", r.notes)
                        take("date_code", r.dateCode); take("ref_des", r.refDes)
                        if (r.category.isNotBlank()) put("category", r.category)
                        r.unitCost?.let { put("unit_cost", it) }
                        r.minimum?.let { put("min_qty", it) }
                        put("scans", r.scans ?: old.scans)
                        put("updated_at", stamp)
                    }, "id = ?", arrayOf(old.id.toString()))
                    updated++
                } else {
                    db.insert("parts", null, ContentValues().apply {
                        put("mpn", r.mpn); put("description", r.description); put("manufacturer", r.manufacturer)
                        put("quantity", r.quantity); put("location", r.location); put("distributor", r.distributor)
                        put("distributor_pn", r.distributorPn); put("customer_pn", r.customerPn)
                        put("source", r.source); put("notes", r.notes); put("date_code", r.dateCode)
                        put("ref_des", r.refDes); put("scans", r.scans ?: 1); put("updated_at", stamp)
                        put("product_url", r.link); put("category", category)
                        r.unitCost?.let { put("unit_cost", it) }
                        r.minimum?.let { put("min_qty", it) }
                    })
                    added++
                }
                if (!dryRun) {
                    db.insert("scan_log", null, ContentValues().apply {
                        put("ts", now)
                        put("part_key", r.mpn.ifBlank { r.customerPn.ifBlank { r.distributorPn } })
                        put("mode", if (replace) "restore" else "import")
                        put("qty", r.quantity); put("total", r.quantity); put("location", r.location)
                        put("source", "CSV import"); put("distributor_pn", r.distributorPn)
                        put("customer_pn", r.customerPn); put("raw", "")
                    })
                }
            }
            if (!dryRun) db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (!dryRun) onChanged?.invoke()
        return ImportSummary(rows.size, added, updated, removed)
    }

    /** Latest earlier save of the bag with this fingerprint, if any. */
    fun findBag(fingerprint: String?): BagHit? {
        if (fingerprint.isNullOrBlank()) return null
        return readableDatabase.rawQuery(
            "SELECT ts, qty, mode FROM scan_log WHERE bag_fp = ? AND mode IN ('add', 'set') ORDER BY id DESC LIMIT 1",
            arrayOf(fingerprint),
        ).use { c -> if (c.moveToFirst()) BagHit(c.getString(0).orEmpty(), c.getInt(1), c.getString(2).orEmpty()) else null }
    }

    fun all(): List<PartRow> =
        readableDatabase.rawQuery("SELECT * FROM parts ORDER BY category, UPPER(mpn), UPPER(customer_pn)", null).use { c ->
            buildList { while (c.moveToNext()) add(c.toRow()) }
        }

    fun update(
        id: Long, quantity: Int, location: String, description: String, unitCost: Double?, category: String,
        minQty: Int?,
    ) {
        val old = byId(id)
        val v = ContentValues().apply {
            put("quantity", quantity); put("location", location.trim()); put("category", category)
            put("description", description.trim()); put("updated_at", now())
        }
        // Changing the category by hand marks it as picked, so price refreshes don't move it again.
        if (old != null && category != old.category) v.put("category_picked", 1)
        if (unitCost != null) v.put("unit_cost", unitCost) else v.putNull("unit_cost")
        if (old != null && unitCost != old.unitCost) {
            v.put("price_source", ""); v.put("price_checked_at", "")
        }
        if (minQty != null && minQty > 0) v.put("min_qty", minQty) else v.putNull("min_qty")
        writableDatabase.update("parts", v, "id = ?", arrayOf(id.toString()))
        onChanged?.invoke()
    }

    private fun byId(id: Long): PartRow? =
        readableDatabase.rawQuery("SELECT * FROM parts WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.toRow() else null
        }

    /**
     * Applies a distributor lookup to a saved part: price (with source and date), description, link, and the
     * category unless the user picked it. Doesn't fire [onChanged]; the caller does once after a batch.
     */
    fun applyLookup(id: Long, u: LookupUpdate) {
        val old = byId(id) ?: return
        val now = now()
        val v = ContentValues().apply {
            if (u.unitCost != null) {
                put("unit_cost", u.unitCost); put("price_source", u.source); put("price_checked_at", now)
            }
            if (u.description.isNotBlank()) put("description", u.description.trim())
            if (u.productUrl.isNotBlank()) put("product_url", u.productUrl)
            if (!old.categoryPicked && u.category.isNotBlank()) put("category", u.category)
            put("updated_at", now)
        }
        writableDatabase.update("parts", v, "id = ?", arrayOf(id.toString()))
    }

    /** Tells listeners (auto backup) that parts changed, e.g. after a batch of [applyLookup] calls. */
    fun notifyChanged() {
        onChanged?.invoke()
    }

    fun delete(id: Long) {
        writableDatabase.delete("parts", "id = ?", arrayOf(id.toString()))
        writableDatabase.delete("kit_items", "part_id = ?", arrayOf(id.toString()))
        onChanged?.invoke()
    }

    /**
     * Takes [qty] pieces out of stock and logs a negative "use" entry. The count stops at 0 (callers confirm that
     * with the user first). Returns the new quantity, or null if the part is gone.
     */
    fun use(id: Long, qty: Int, note: String = "Used"): Int? {
        val db = writableDatabase
        db.beginTransaction()
        val total = try {
            useTx(db, id, qty, note, now()).also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
        onChanged?.invoke()
        return total
    }

    private fun useTx(db: SQLiteDatabase, id: Long, qty: Int, note: String, now: String): Int? {
        val r = db.rawQuery("SELECT * FROM parts WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.toRow() else null
        } ?: return null
        val total = maxOf(0, r.quantity - qty)
        db.update("parts", ContentValues().apply {
            put("quantity", total); put("updated_at", now)
        }, "id = ?", arrayOf(id.toString()))
        db.insert("scan_log", null, ContentValues().apply {
            put("ts", now)
            put("part_key", r.mpn.ifBlank { r.customerPn.ifBlank { r.distributorPn } })
            put("mode", "use")
            put("qty", -qty); put("total", total); put("location", r.location)
            put("source", note); put("distributor_pn", r.distributorPn)
            put("customer_pn", r.customerPn); put("raw", "")
        })
        return total
    }

    /** Kits registered from scanned kit labels, most recently scanned first. */
    fun kits(): List<KitSummary> =
        readableDatabase.rawQuery(
            "SELECT k.kit, COUNT(*), MAX(k.kit_qty) FROM kit_items k JOIN parts p ON p.id = k.part_id " +
                "GROUP BY k.kit ORDER BY MAX(k.updated_at) DESC", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(KitSummary(c.getString(0), c.getInt(1), if (c.isNull(2)) null else c.getInt(2)))
            }
        }

    /** What building [boards] of [kit] takes from stock, part by part. */
    fun kitPlan(kit: String, boards: Int): List<KitLine> {
        val members = readableDatabase.rawQuery(
            "SELECT part_id, per_board, ref_des FROM kit_items WHERE kit = ? ORDER BY item, id", arrayOf(kit)
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getInt(1), c.getString(2).orEmpty())) } }
        return members.mapNotNull { (id, perBoard, refDes) ->
            val row = readableDatabase.rawQuery("SELECT * FROM parts WHERE id = ?", arrayOf(id.toString())).use { c ->
                if (c.moveToFirst()) c.toRow() else null
            } ?: return@mapNotNull null
            KitLine(row, perBoard, refDes, perBoard * boards)
        }
    }

    /** Subtracts a kit build from stock in one transaction, logging a "use" per part. */
    fun buildKit(kit: String, boards: Int): List<KitLine> {
        val plan = kitPlan(kit, boards)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = now()
            for (line in plan) useTx(db, line.part.id, line.need, "Kit $kit × $boards", now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        onChanged?.invoke()
        return plan
    }

    fun inventoryCsv(): String {
        val header = InventoryCsv.HEADER
        val rows = all().map {
            listOf(
                it.category, it.mpn, it.description, it.manufacturer, it.quantity.toString(),
                it.unitCost?.let(::plain).orEmpty(),
                it.unitCost?.let { c -> plain(c * it.quantity) }.orEmpty(),
                it.location, it.distributor, it.distributorPn, it.customerPn, it.link(), it.source, it.notes,
                it.dateCode, it.refDes, it.scans.toString(), it.updatedAt, it.minQty?.toString().orEmpty(),
            )
        }
        return csv(header, rows)
    }

    /** Parts below their minimum with how many to order; Mouser PN + Quantity columns paste into Mouser's BOM tool. */
    fun reorderCsv(): String = ReorderList.csv(reorderItems())

    fun reorderCount(): Int = reorderItems().count { ReorderList.orderQuantity(it.onHand, it.minimum) > 0 }

    private fun reorderItems(): List<ReorderList.Item> = all().map { p ->
        ReorderList.Item().apply {
            distributor = p.distributor; distributorPn = p.distributorPn; mpn = p.mpn
            manufacturer = p.manufacturer; description = p.description; link = p.link()
            onHand = p.quantity; minimum = p.minQty; unitCost = p.unitCost
        }
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

    private fun ContentValues.take(column: String, new: String) {
        if (new.isNotBlank()) put(column, new)
    }

    private fun ContentValues.fill(column: String, current: String, new: String) {
        if (current.isBlank() && new.isNotBlank()) put(column, new)
    }

    private fun Cursor.str(col: String) = getString(getColumnIndexOrThrow(col)).orEmpty()
    private fun Cursor.int(col: String) = getInt(getColumnIndexOrThrow(col))

    private fun Cursor.toRow(): PartRow {
        val costIdx = getColumnIndexOrThrow("unit_cost")
        val minIdx = getColumnIndexOrThrow("min_qty")
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
            minQty = if (isNull(minIdx)) null else getInt(minIdx).takeIf { it > 0 },
            categoryPicked = int("category_picked") != 0,
            priceSource = str("price_source"),
            priceCheckedAt = str("price_checked_at"),
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
        fun csv(header: List<String>, rows: List<List<String>>): String = InventoryCsv.write(header, rows)
    }
}
