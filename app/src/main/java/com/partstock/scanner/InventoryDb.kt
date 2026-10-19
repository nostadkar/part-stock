package com.partstock.scanner

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.partstock.scanner.parse.BomCsv
import com.partstock.scanner.parse.BuildPlan
import com.partstock.scanner.parse.Categories
import com.partstock.scanner.parse.ChangeSummary
import com.partstock.scanner.parse.InventoryCsv
import com.partstock.scanner.parse.Links
import com.partstock.scanner.parse.Locations
import com.partstock.scanner.parse.PartSpecs
import com.partstock.scanner.parse.ReorderList
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

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
    /** Value (10kΩ, 100nF), tolerance, voltage and package; blank when unknown. */
    val specValue: String = "",
    val tolerance: String = "",
    val voltage: String = "",
    val pkg: String = "",
    val datasheetUrl: String = "",
    /** Pieces per bin, in order; [quantity] is their sum. Empty when not loaded (see [bins]). */
    val stock: Map<String, Int> = emptyMap(),
)

/** Pieces per bin; for a row loaded without its stock, the one bin in [PartRow.location]. */
fun PartRow.bins(): Map<String, Int> = stock.ifEmpty { mapOf(Locations.normalize(location) to quantity) }

fun PartRow.specs(): PartSpecs = PartSpecs(specValue, tolerance, voltage, pkg)

/** Search box match: text in any field, or equal values (10k = 10kΩ = 10000, 0.1uF = 100nF) and packages. */
fun PartRow.matches(query: String): Boolean = PartSpecs.matches(
    query, listOf(mpn, description, manufacturer, location, distributorPn, customerPn, category) + stock.keys, specs(),
)

/** At or below its minimum (the Inventory screen's "Low stock" filter). */
fun PartRow.isLow(): Boolean = ReorderList.isLow(quantity, minQty)

/** Saved link, or one built from the part numbers for rows saved before links existed. */
fun PartRow.link(): String =
    productUrl.ifBlank { Links.productUrl(distributor, distributorPn, mpn).orEmpty() }

/** The datasheet link, or a web search for one when no lookup saved it; blank when the part has no number. */
fun PartRow.datasheet(): String = Links.datasheetUrl(datasheetUrl, distributorPn, mpn).orEmpty()

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
    /** Datasheet link from a lookup; blank otherwise. */
    val datasheetUrl: String = "",
    /** Specs from a lookup's attributes; blanks are filled from [description] when saving. */
    val specs: PartSpecs = PartSpecs.EMPTY,
    /**
     * The scan's label photo, already written by [PartPhotos.save]; blank = none. Kept as the part's first photo when
     * the part has none yet, otherwise the file is deleted.
     */
    val photoFile: String = "",
)

/** A photo attached to a part ([file] is a name in [PartPhotos.dir]); [kind] is "label" (from a scan) or "photo". */
data class PartPhoto(val id: Long, val partId: Long, val file: String, val kind: String, val createdAt: String)

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
    val datasheetUrl: String = "",
    /** From the distributor's attributes; blanks are filled from [description]. */
    val specs: PartSpecs = PartSpecs.EMPTY,
)

/** A project (board) with a BOM. */
data class ProjectSummary(val id: Long, val name: String, val source: String, val lines: Int, val updatedAt: String)

/** One BOM line of a project. [partId] is a stock part picked by hand; null = matched automatically. */
data class ProjectLine(
    val id: Long,
    val mpn: String,
    val customerPn: String,
    val distributorPn: String,
    val manufacturer: String,
    val value: String,
    val footprint: String,
    val description: String,
    val refDes: String,
    val perBoard: Int,
    val partId: Long?,
) {
    fun toBomLine(): BomCsv.Line = BomCsv.Line().also {
        it.mpn = mpn; it.customerPn = customerPn; it.distributorPn = distributorPn; it.manufacturer = manufacturer
        it.value = value; it.footprint = footprint; it.description = description; it.refDes = refDes
        it.perBoard = perBoard
    }

    fun toReq(): BuildPlan.Req = BuildPlan.Req.of(toBomLine()).also { it.pickedId = partId }
}

/** A storage location with how many different parts and pieces it holds. */
data class LocationSummary(val name: String, val parts: Int, val pieces: Int)

/**
 * One line of a part's History. [group] is what [InventoryDb.undo] takes; [legacy] entries come from the scan log of
 * saves made before history existed (no undo, quantity before only when it can be worked out).
 */
data class HistoryEntry(
    val ts: String,
    val action: String,
    val qtyBefore: Int?,
    val qtyAfter: Int?,
    val summary: String,
    val group: Long = 0,
    val undone: Boolean = false,
    val legacy: Boolean = false,
)

/** What a CSV restore did (or would do, for a dry run). */
data class ImportSummary(val rows: Int, val added: Int, val updated: Int, val removed: Int)

/** Inventory stored on the phone: one row per part, plus a log of every save. */
class InventoryDb(context: Context) : SQLiteOpenHelper(context, "inventory.db", null, 12) {

    /** Part photos (see [PartPhotos]); photos of deleted parts wait in trash/ until Undo can't bring them back. */
    private val photoDir = File(context.applicationContext.filesDir, "photos")
    private val trashDir = File(photoDir, "trash")

    /** Called after every change to the parts table (used for auto backup). */
    @Volatile var onChanged: (() -> Unit)? = null

    /** Change group of the latest recorded change, for the Undo snackbar ([undo]); 0 = none yet. */
    @Volatile var lastChange: Long = 0
        private set

    /** Set while [grouped] runs: every change recorded meanwhile undoes together. */
    @Volatile private var openGroup: Long = 0

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
                price_checked_at TEXT NOT NULL DEFAULT '', spec_value TEXT NOT NULL DEFAULT '',
                tolerance TEXT NOT NULL DEFAULT '', voltage TEXT NOT NULL DEFAULT '',
                package TEXT NOT NULL DEFAULT '', datasheet_url TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL(
            """CREATE TABLE scan_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT, part_key TEXT, mode TEXT,
                qty INTEGER, total INTEGER, location TEXT, source TEXT,
                distributor_pn TEXT, customer_pn TEXT, raw TEXT, bag_fp TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS scan_log_bag_fp ON scan_log(bag_fp)")
        createKitItems(db)
        createProjects(db)
        createStock(db)
        createHistory(db, 0)
        createPhotos(db)
    }

    /** Photos per part: file names only, the JPEGs are in [photoDir]. */
    private fun createPhotos(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS part_photos (
                id INTEGER PRIMARY KEY AUTOINCREMENT, part_id INTEGER NOT NULL, file TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT 'photo', created_at TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS part_photos_part ON part_photos(part_id)")
    }

    /**
     * Change history (one row per part per change, with the part as it was before so it can be undone) and a small
     * key/value table. [legacyLogId]: scan log rows up to this id are shown as history from before it existed.
     */
    private fun createHistory(db: SQLiteDatabase, legacyLogId: Long) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS part_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT, grp INTEGER NOT NULL, part_id INTEGER NOT NULL,
                ts TEXT NOT NULL DEFAULT '', action TEXT NOT NULL DEFAULT '', qty_before INTEGER, qty_after INTEGER,
                summary TEXT NOT NULL DEFAULT '', snapshot TEXT NOT NULL DEFAULT '', log_id INTEGER,
                undone INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS part_history_part ON part_history(part_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS part_history_grp ON part_history(grp)")
        db.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL DEFAULT '')")
        db.execSQL("INSERT OR IGNORE INTO meta (key, value) VALUES ('legacy_log_id', ?)", arrayOf(legacyLogId.toString()))
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (db.isReadOnly) return
        // Undo is for recent mistakes: snapshots (including deleted parts) are dropped after 30 days; the history
        // lines themselves stay.
        val cutoff = LocalDateTime.now().minusDays(SNAPSHOT_DAYS).format(STAMP)
        db.execSQL(
            "UPDATE part_history SET snapshot = ? WHERE ts < ? AND snapshot NOT IN ('', ?)",
            arrayOf(PURGED, cutoff, PURGED),
        )
        try {
            cleanPhotoFiles(db)
        } catch (_: Exception) {
            // Tidying photo files is best effort; never block opening the inventory.
        }
    }

    /**
     * Removes photos of deleted parts once Undo can't bring them back ([SNAPSHOT_DAYS]), and photo files no part refers
     * to (left behind by a save that failed) after a day.
     */
    private fun cleanPhotoFiles(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        trashDir.listFiles()?.forEach { f ->
            if (f.isFile && now - f.lastModified() > SNAPSHOT_DAYS * DAY_MS) f.delete()
        }
        val files = photoDir.listFiles()?.filter { it.isFile } ?: return
        if (files.isEmpty()) return
        val used = db.rawQuery("SELECT file FROM part_photos", null).use { c ->
            HashSet<String>().apply { while (c.moveToNext()) add(c.getString(0)) }
        }
        for (f in files) if (f.name !in used && now - f.lastModified() > DAY_MS) f.delete()
    }

    private fun trashPhoto(name: String) {
        val f = File(photoDir, name)
        if (!f.isFile) return
        trashDir.mkdirs()
        val t = File(trashDir, name)
        if (f.renameTo(t)) t.setLastModified(System.currentTimeMillis()) else f.delete()
        PartPhotos.forget(name)
    }

    private fun photoFiles(db: SQLiteDatabase, partId: Long): List<String> =
        db.rawQuery("SELECT file FROM part_photos WHERE part_id = ? ORDER BY id", arrayOf(partId.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** A part's photos, oldest (the label photo from the first scan) first. */
    fun photos(partId: Long): List<PartPhoto> =
        readableDatabase.rawQuery(
            "SELECT id, part_id, file, kind, created_at FROM part_photos WHERE part_id = ? ORDER BY id",
            arrayOf(partId.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(PartPhoto(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3).orEmpty(), c.getString(4).orEmpty())) }
        }

    /** Every part's photo file names, oldest first (thumbnails use the first; the photo export uses all). */
    fun allPhotos(): Map<Long, List<String>> {
        val m = LinkedHashMap<Long, MutableList<String>>()
        readableDatabase.rawQuery("SELECT part_id, file FROM part_photos ORDER BY id", null).use { c ->
            while (c.moveToNext()) m.getOrPut(c.getLong(0)) { mutableListOf() }.add(c.getString(1))
        }
        return m
    }

    /** Attaches a photo file (already in [photoDir]) to a part. Photos aren't stock, so this isn't in the history. */
    fun addPhoto(partId: Long, file: String, kind: String = "photo") {
        writableDatabase.insert("part_photos", null, ContentValues().apply {
            put("part_id", partId); put("file", file); put("kind", kind); put("created_at", now())
        })
    }

    /** Removes one photo and deletes its file. */
    fun deletePhoto(photoId: Long) {
        val db = writableDatabase
        val file = db.rawQuery("SELECT file FROM part_photos WHERE id = ?", arrayOf(photoId.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: return
        db.delete("part_photos", "id = ?", arrayOf(photoId.toString()))
        File(photoDir, file).delete()
        PartPhotos.forget(file)
    }

    /** Pieces per part per bin. parts.quantity stays as the total and parts.location as the main bin. */
    private fun createStock(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS part_stock (
                id INTEGER PRIMARY KEY AUTOINCREMENT, part_id INTEGER NOT NULL, location TEXT NOT NULL,
                qty INTEGER NOT NULL DEFAULT 0, UNIQUE(part_id, location))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS part_stock_location ON part_stock(location)")
    }

    /** Projects and their BOM lines. New tables only: nothing in the parts table changes. */
    private fun createProjects(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, source TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL DEFAULT '', updated_at TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS project_lines (
                id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL, line INTEGER NOT NULL DEFAULT 0,
                mpn TEXT NOT NULL DEFAULT '', customer_pn TEXT NOT NULL DEFAULT '',
                distributor_pn TEXT NOT NULL DEFAULT '', manufacturer TEXT NOT NULL DEFAULT '',
                value TEXT NOT NULL DEFAULT '', footprint TEXT NOT NULL DEFAULT '',
                description TEXT NOT NULL DEFAULT '', ref_des TEXT NOT NULL DEFAULT '',
                per_board INTEGER NOT NULL DEFAULT 1, part_id INTEGER)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS project_lines_project ON project_lines(project_id)")
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
        if (oldVersion < 8) {
            // Adds columns only; value / tolerance / voltage / package are filled in from each saved description.
            for (col in listOf("spec_value", "tolerance", "voltage", "package", "datasheet_url")) {
                db.execSQL("ALTER TABLE parts ADD COLUMN $col TEXT NOT NULL DEFAULT ''")
            }
            val rows = db.rawQuery("SELECT id, description FROM parts", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1).orEmpty()) }
            }
            for ((id, desc) in rows) {
                val sp = PartSpecs.fromDescription(desc)
                if (sp.isEmpty) continue
                db.execSQL(
                    "UPDATE parts SET spec_value = ?, tolerance = ?, voltage = ?, package = ? WHERE id = ?",
                    arrayOf(sp.value, sp.tolerance, sp.voltage, sp.pkg, id),
                )
            }
        }
        if (oldVersion < 9) {
            // New tables only (projects / BOMs); existing parts, scan log and kits are untouched.
            createProjects(db)
        }
        if (oldVersion < 10) {
            // New table only: each part's quantity goes into its bin (blank bin = "Unassigned"). The parts table keeps
            // its quantity (now the total) and location (the main bin), so nothing is lost or changed.
            createStock(db)
            db.execSQL(
                "INSERT OR IGNORE INTO part_stock (part_id, location, qty) " +
                    "SELECT id, CASE WHEN TRIM(location) = '' THEN ? ELSE TRIM(location) END, MAX(quantity, 0) FROM parts",
                arrayOf(Locations.UNASSIGNED),
            )
        }
        if (oldVersion < 11) {
            // New tables only (history + meta). The existing scan log is kept and shown as history for older saves.
            val lastLog = db.rawQuery("SELECT IFNULL(MAX(id), 0) FROM scan_log", null).use { c -> c.moveToFirst(); c.getLong(0) }
            createHistory(db, lastLog)
        }
        if (oldVersion < 12) {
            // New table only (part photos); nothing existing changes.
            createPhotos(db)
        }
    }

    // ---------------------------------------------------------------- history and undo

    /** The part row, its bins and kit memberships as JSON, exactly as stored; null if the part doesn't exist. */
    private fun snapshot(db: SQLiteDatabase, id: Long): JSONObject? {
        val o = db.rawQuery("SELECT * FROM parts WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            JSONObject().apply {
                for (i in 0 until c.columnCount) {
                    put(c.getColumnName(i), when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        else -> c.getString(i)
                    })
                }
            }
        }
        o.put("_stock", JSONArray().apply { for ((k, v) in stockOf(db, id)) put(JSONArray().put(k).put(v)) })
        o.put("_kits", db.rawQuery(
            "SELECT kit, per_board, ref_des, item, kit_qty, updated_at FROM kit_items WHERE part_id = ? ORDER BY id",
            arrayOf(id.toString()),
        ).use { c ->
            JSONArray().apply {
                while (c.moveToNext()) {
                    put(JSONObject().apply {
                        put("kit", c.getString(0)); put("per_board", c.getInt(1)); put("ref_des", c.getString(2).orEmpty())
                        put("item", if (c.isNull(3)) JSONObject.NULL else c.getInt(3))
                        put("kit_qty", if (c.isNull(4)) JSONObject.NULL else c.getInt(4))
                        put("updated_at", c.getString(5).orEmpty())
                    })
                }
            }
        })
        o.put("_photos", db.rawQuery(
            "SELECT file, kind, created_at FROM part_photos WHERE part_id = ? ORDER BY id", arrayOf(id.toString()),
        ).use { c ->
            JSONArray().apply {
                while (c.moveToNext()) put(JSONObject().apply {
                    put("file", c.getString(0)); put("kind", c.getString(1).orEmpty()); put("created_at", c.getString(2).orEmpty())
                })
            }
        })
        return o
    }

    /** Column → text for [ChangeSummary]; "stock" holds the bins as in the CSV. */
    private fun flat(o: JSONObject?): Map<String, String>? {
        if (o == null) return null
        val m = HashMap<String, String>()
        for (k in o.keys()) if (!k.startsWith("_")) m[k] = if (o.isNull(k)) "" else o.get(k).toString()
        val stock = LinkedHashMap<String, Int>()
        o.optJSONArray("_stock")?.let { a -> for (i in 0 until a.length()) a.getJSONArray(i).let { stock[it.getString(0)] = it.getInt(1) } }
        m["stock"] = Locations.format(stock)
        return m
    }

    private fun newGroup(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT IFNULL(MAX(grp), 0) + 1 FROM part_history", null).use { c -> c.moveToFirst(); c.getLong(0) }

    /**
     * Records a change to one part: [before] is the part as it was (null = it didn't exist yet). Call inside the
     * change's transaction, after the change. Returns the change group.
     */
    private fun record(db: SQLiteDatabase, partId: Long, action: String, before: JSONObject?, logId: Long? = null, group: Long = 0): Long {
        val after = snapshot(db, partId)
        if (before == null && after == null) return 0
        val grp = when {
            group != 0L -> group
            openGroup != 0L -> openGroup
            else -> newGroup(db)
        }
        db.insert("part_history", null, ContentValues().apply {
            put("grp", grp); put("part_id", partId); put("ts", now()); put("action", action)
            if (before != null) put("qty_before", before.optInt("quantity"))
            if (after != null) put("qty_after", after.optInt("quantity"))
            put("summary", ChangeSummary.describe(flat(before), flat(after)))
            put("snapshot", before?.toString() ?: "")
            if (logId != null && logId > 0) put("log_id", logId)
        })
        lastChange = grp
        return grp
    }

    /** Runs [block] so that every change it makes is one Undo (e.g. an edit plus a use, or a kit build). */
    fun <T> grouped(block: () -> T): T {
        if (openGroup != 0L) return block()
        openGroup = newGroup(writableDatabase)
        try {
            return block()
        } finally {
            openGroup = 0
        }
    }

    /** True if [group] can still be undone (not undone already, snapshots not purged). */
    fun canUndo(group: Long): Boolean = group > 0 && readableDatabase.rawQuery(
        "SELECT COUNT(*), SUM(undone = 0 AND snapshot != ?) FROM part_history WHERE grp = ?", arrayOf(PURGED, group.toString()),
    ).use { c -> c.moveToFirst() && c.getInt(0) > 0 && c.getInt(0) == c.getInt(1) }

    /**
     * Puts every part touched by change [group] back exactly as it was (row, bins and kit memberships): a new part is
     * removed again, a deleted one comes back with the same id. The scan log keeps its lines, marked "undone" so the
     * duplicate-bag check ignores them. The undo itself is recorded in the history. Returns parts restored.
     */
    fun undo(group: Long): Int {
        if (!canUndo(group)) return 0
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            data class Rec(val id: Long, val partId: Long, val snap: String, val logId: Long?)
            val recs = db.rawQuery(
                "SELECT id, part_id, snapshot, log_id FROM part_history WHERE grp = ? ORDER BY id DESC", arrayOf(group.toString()),
            ).use { c ->
                buildList { while (c.moveToNext()) add(Rec(c.getLong(0), c.getLong(1), c.getString(2).orEmpty(), if (c.isNull(3)) null else c.getLong(3))) }
            }
            val undoGroup = newGroup(db)
            for (r in recs) {
                val before = snapshot(db, r.partId)
                restore(db, r.partId, r.snap)
                if (r.logId != null) db.execSQL("UPDATE scan_log SET mode = 'undone' WHERE id = ?", arrayOf(r.logId))
                db.execSQL("UPDATE part_history SET undone = 1 WHERE id = ?", arrayOf(r.id))
                record(db, r.partId, "undo", before, group = undoGroup)
                n++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (n > 0) onChanged?.invoke()
        return n
    }

    private fun restore(db: SQLiteDatabase, id: Long, snap: String) {
        val args = arrayOf(id.toString())
        db.delete("part_stock", "part_id = ?", args)
        db.delete("kit_items", "part_id = ?", args)
        if (snap.isEmpty()) { // the change created the part
            db.delete("parts", "id = ?", args)
            photoFiles(db, id).forEach(::trashPhoto)
            db.delete("part_photos", "part_id = ?", args)
            return
        }
        val o = JSONObject(snap)
        val v = ContentValues()
        for (k in o.keys()) {
            if (k.startsWith("_")) continue
            when (val x = o.get(k)) {
                JSONObject.NULL -> v.putNull(k)
                is Int -> v.put(k, x)
                is Long -> v.put(k, x)
                is Double -> v.put(k, x)
                is Number -> v.put(k, x.toDouble())
                else -> v.put(k, x.toString())
            }
        }
        v.put("id", id)
        val exists = db.rawQuery("SELECT 1 FROM parts WHERE id = ?", args).use { it.moveToFirst() }
        if (exists) db.update("parts", v, "id = ?", args) else db.insert("parts", null, v)
        o.optJSONArray("_stock")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.getJSONArray(i)
                db.insert("part_stock", null, ContentValues().apply {
                    put("part_id", id); put("location", e.getString(0)); put("qty", e.getInt(1))
                })
            }
        }
        o.optJSONArray("_kits")?.let { a ->
            for (i in 0 until a.length()) {
                val k = a.getJSONObject(i)
                db.insert("kit_items", null, ContentValues().apply {
                    put("part_id", id); put("kit", k.getString("kit")); put("per_board", k.getInt("per_board"))
                    put("ref_des", k.optString("ref_des")); put("updated_at", k.optString("updated_at"))
                    if (!k.isNull("item")) put("item", k.getInt("item"))
                    if (!k.isNull("kit_qty")) put("kit_qty", k.getInt("kit_qty"))
                })
            }
        }
        // Photos aren't in the history: Undo only brings back the ones a delete took away, and never removes any.
        o.optJSONArray("_photos")?.let { a ->
            val have = photoFiles(db, id).toSet()
            for (i in 0 until a.length()) {
                val p = a.getJSONObject(i)
                val name = p.getString("file")
                if (name in have) continue
                val f = File(photoDir, name)
                val t = File(trashDir, name)
                if (!f.isFile && t.isFile) t.renameTo(f)
                if (!f.isFile) continue
                db.insert("part_photos", null, ContentValues().apply {
                    put("part_id", id); put("file", name); put("kind", p.optString("kind", "photo"))
                    put("created_at", p.optString("created_at"))
                })
            }
        }
    }

    /**
     * A part's history, newest first: every recorded change, then (for saves from before history existed) the matching
     * scan log lines.
     */
    fun history(part: PartRow): List<HistoryEntry> {
        val db = readableDatabase
        val out = db.rawQuery(
            "SELECT ts, action, qty_before, qty_after, summary, grp, undone FROM part_history WHERE part_id = ? ORDER BY id DESC",
            arrayOf(part.id.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(HistoryEntry(
                    ts = c.getString(0).orEmpty(), action = c.getString(1).orEmpty(),
                    qtyBefore = if (c.isNull(2)) null else c.getInt(2), qtyAfter = if (c.isNull(3)) null else c.getInt(3),
                    summary = c.getString(4).orEmpty(), group = c.getLong(5), undone = c.getInt(6) != 0,
                ))
            }
        }.toMutableList()
        val cutoff = db.rawQuery("SELECT value FROM meta WHERE key = 'legacy_log_id'", null).use { c ->
            if (c.moveToFirst()) c.getString(0).toLongOrNull() ?: 0 else 0
        }
        val keys = listOf(part.mpn, part.customerPn, part.distributorPn).map { norm(it) }.filter { it.isNotEmpty() }.distinct()
        if (cutoff > 0 && keys.isNotEmpty()) {
            val marks = keys.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT ts, mode, qty, total, location, source FROM scan_log " +
                    "WHERE id <= ? AND UPPER(REPLACE(part_key, ' ', '')) IN ($marks) ORDER BY id DESC",
                arrayOf(cutoff.toString()) + keys,
            ).use { c ->
                while (c.moveToNext()) {
                    val mode = c.getString(1).orEmpty()
                    val qty = c.getInt(2)
                    val total = c.getInt(3)
                    val detail = listOf(
                        if (mode == "use") "used ${-qty}" else "qty $qty",
                        c.getString(4).orEmpty().let { if (it.isNotBlank()) "bin $it" else "" },
                        c.getString(5).orEmpty(),
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    out += HistoryEntry(
                        ts = c.getString(0).orEmpty(), action = mode,
                        qtyBefore = if (mode == "add" || mode == "use") total - qty else null, qtyAfter = total,
                        summary = detail, legacy = true,
                    )
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- stock per location

    private fun stockOf(db: SQLiteDatabase, partId: Long): LinkedHashMap<String, Int> =
        db.rawQuery("SELECT location, qty FROM part_stock WHERE part_id = ? ORDER BY id", arrayOf(partId.toString())).use { c ->
            val m = LinkedHashMap<String, Int>()
            while (c.moveToNext()) m[c.getString(0).orEmpty()] = c.getInt(1)
            m
        }

    /** Stock of a part loaded without it; falls back to its quantity in its main bin (rows from before v10). */
    private fun stockOrLegacy(db: SQLiteDatabase, r: PartRow): LinkedHashMap<String, Int> =
        stockOf(db, r.id).ifEmpty { linkedMapOf(Locations.normalize(r.location) to r.quantity) }

    /** Stores the part's bins (empty ones dropped, see [Locations.compact]) and sets parts.quantity to the total. */
    private fun writeStock(db: SQLiteDatabase, partId: Long, stock: Map<String, Int>, primary: String): Int {
        val kept = Locations.compact(stock, Locations.normalize(primary))
        db.delete("part_stock", "part_id = ?", arrayOf(partId.toString()))
        for ((bin, q) in kept) {
            db.insert("part_stock", null, ContentValues().apply {
                put("part_id", partId); put("location", bin); put("qty", q)
            })
        }
        val total = Locations.total(kept)
        db.update("parts", ContentValues().apply { put("quantity", total) }, "id = ?", arrayOf(partId.toString()))
        return total
    }

    /** Every bin in use, with part and piece counts, in name order ("Unassigned" last). */
    fun locations(): List<LocationSummary> =
        readableDatabase.rawQuery(
            "SELECT s.location, COUNT(DISTINCT s.part_id), SUM(s.qty) FROM part_stock s JOIN parts p ON p.id = s.part_id " +
                "GROUP BY s.location COLLATE NOCASE ORDER BY s.location = ?, s.location COLLATE NOCASE",
            arrayOf(Locations.UNASSIGNED),
        ).use { c ->
            buildList { while (c.moveToNext()) add(LocationSummary(c.getString(0).orEmpty(), c.getInt(1), c.getInt(2))) }
        }

    /**
     * Moves up to [qty] pieces of a part from one bin to another and logs it. Returns how many moved, or null if the
     * part is gone. The main bin follows the stock when the old main bin ends up empty.
     */
    fun move(id: Long, from: String, to: String, qty: Int): Int? {
        val db = writableDatabase
        db.beginTransaction()
        val moved = try {
            val r = byId(id) ?: return null
            val snap = snapshot(db, id)
            val before = stockOrLegacy(db, r)
            val n = minOf(maxOf(0, qty), Locations.qtyIn(before, from))
            if (n == 0 || Locations.same(from, to)) return 0
            val after = Locations.move(before, from, to, n)
            val primary = if (Locations.same(r.location, from) && Locations.qtyIn(after, from) == 0) Locations.plain(to)
                else r.location
            val now = now()
            val total = writeStock(db, id, after, primary)
            db.update("parts", ContentValues().apply { put("location", primary); put("updated_at", now) },
                "id = ?", arrayOf(id.toString()))
            val logId = db.insert("scan_log", null, ContentValues().apply {
                put("ts", now); put("part_key", r.key()); put("mode", "move")
                put("qty", n); put("total", total); put("location", "${Locations.normalize(from)} → ${Locations.normalize(to)}")
                put("source", "Move"); put("distributor_pn", r.distributorPn)
                put("customer_pn", r.customerPn); put("raw", "")
            })
            record(db, id, "move", snap, logId)
            db.setTransactionSuccessful()
            n
        } finally {
            db.endTransaction()
        }
        onChanged?.invoke()
        return moved
    }

    /** Renames a bin on every part at once (merging into [to] where a part already has it). Returns parts changed. */
    fun renameLocation(from: String, to: String): Int {
        val target = Locations.normalize(to)
        if (Locations.normalize(from) == target) return 0
        val db = writableDatabase
        var changed = 0
        val group = newGroup(db)
        db.beginTransaction()
        try {
            val ids = db.rawQuery(
                "SELECT DISTINCT part_id FROM part_stock WHERE location = ? COLLATE NOCASE", arrayOf(Locations.normalize(from))
            ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
            for (id in ids) {
                val r = byId(id) ?: continue
                val snap = snapshot(db, id)
                val stock = Locations.rename(stockOf(db, id), from, target)
                val primary = if (Locations.same(r.location, from)) Locations.plain(target) else r.location
                writeStock(db, id, stock, primary)
                db.update("parts", ContentValues().apply { put("location", primary) }, "id = ?", arrayOf(id.toString()))
                record(db, id, "rename bin", snap, group = group)
                changed++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (changed > 0) onChanged?.invoke()
        return changed
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
            if (row != null) return Match(row.copy(stock = stockOf(readableDatabase, row.id)), label)
        }
        return null
    }

    /** Adds or updates the part and logs the scan. Returns the new quantity. */
    fun save(e: ScanEntry): Int = saveTx(e).also { onChanged?.invoke() }

    /** Saves several entries (e.g. the lines of an imported order) in one transaction: all or none. */
    fun saveAll(entries: List<ScanEntry>) {
        if (entries.isEmpty()) return
        val db = writableDatabase
        grouped {
            db.beginTransaction()
            try {
                for (e in entries) saveTx(e)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        onChanged?.invoke()
    }

    private fun saveTx(e: ScanEntry): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = now()
            val hit = find(e.mpn, e.customerPn, e.distributorPn)
            val snap = hit?.let { snapshot(db, it.row.id) }
            val specs = e.specs.orElse(PartSpecs.fromDescription(e.description))
            val total: Int
            val partId: Long
            if (hit != null) {
                val r = hit.row
                // Add puts the pieces in this scan's bin (the part's main bin if none was given); Set sets that bin's count.
                val bin = Locations.normalize(e.location.ifBlank { r.location })
                val before = stockOrLegacy(db, r)
                val stock = if (e.addMode) Locations.add(before, bin, e.quantity) else Locations.set(before, bin, e.quantity)
                total = writeStock(db, r.id, stock, e.location.ifBlank { r.location })
                val v = ContentValues().apply {
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
                    if (e.datasheetUrl.isNotBlank()) put("datasheet_url", e.datasheetUrl)
                    // A lookup's specs replace older ones; a label only fills what's missing.
                    putSpecs(specs, if (e.descriptionFromMouser) null else r.specs())
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
                    put("datasheet_url", e.datasheetUrl); putSpecs(specs, null)
                    if (e.categoryPicked) put("category_picked", 1)
                    if (e.unitCost != null) put("unit_cost", e.unitCost)
                    if (e.unitCost != null && e.priceSource.isNotBlank()) {
                        put("price_source", e.priceSource); put("price_checked_at", now)
                    }
                })
                if (partId > 0) writeStock(db, partId, mapOf(Locations.normalize(e.location) to e.quantity), e.location)
            }
            val logId = db.insert("scan_log", null, ContentValues().apply {
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
            if (e.photoFile.isNotBlank()) {
                // The label photo becomes the part's thumbnail; later scans of the same part don't pile up photos.
                if (partId > 0 && photoFiles(db, partId).isEmpty()) addPhoto(partId, e.photoFile, "label")
                else File(photoDir, e.photoFile).delete()
            }
            if (partId > 0) record(db, partId, if (e.addMode) "scan add" else "scan set", snap, logId)
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
        var replacedPhotos = emptyList<String>()
        db.beginTransaction()
        try {
            val now = now()
            if (replace) {
                removed = db.delete("parts", null, null)
                db.delete("part_stock", null, null)
                // The parts come back from the file with new ids, so their photos can't follow; they go with them.
                if (!dryRun) {
                    replacedPhotos = db.rawQuery("SELECT file FROM part_photos", null).use { c ->
                        buildList { while (c.moveToNext()) add(c.getString(0)) }
                    }
                    db.delete("part_photos", null, null)
                }
            }
            for (r in rows) {
                // The Locations column wins when present; otherwise the whole quantity is in the Location bin.
                val fileStock = Locations.parse(r.locations).ifEmpty { linkedMapOf(Locations.normalize(r.location) to r.quantity) }
                val fileQty = Locations.total(fileStock)
                val category = r.category.ifBlank { Categories.classify(r.description, r.notes) }
                val stamp = r.updatedAt.ifBlank { now }
                val hit = find(r.mpn, r.customerPn, r.distributorPn)
                if (hit != null) {
                    val old = hit.row
                    db.update("parts", ContentValues().apply {
                        put("quantity", fileQty)
                        take("mpn", r.mpn); take("description", r.description); take("manufacturer", r.manufacturer)
                        take("location", r.location); take("distributor", r.distributor)
                        take("distributor_pn", r.distributorPn); take("customer_pn", r.customerPn)
                        take("product_url", r.link); take("source", r.source); take("notes", r.notes)
                        take("date_code", r.dateCode); take("ref_des", r.refDes)
                        if (r.category.isNotBlank()) put("category", r.category)
                        take("spec_value", r.value); take("tolerance", r.tolerance); take("voltage", r.voltage)
                        take("package", r.pkg); take("datasheet_url", r.datasheet)
                        r.unitCost?.let { put("unit_cost", it) }
                        r.minimum?.let { put("min_qty", it) }
                        put("scans", r.scans ?: old.scans)
                        put("updated_at", stamp)
                    }, "id = ?", arrayOf(old.id.toString()))
                    writeStock(db, old.id, fileStock, r.location.ifBlank { old.location })
                    updated++
                } else {
                    val newId = db.insert("parts", null, ContentValues().apply {
                        put("mpn", r.mpn); put("description", r.description); put("manufacturer", r.manufacturer)
                        put("quantity", fileQty); put("location", r.location); put("distributor", r.distributor)
                        put("distributor_pn", r.distributorPn); put("customer_pn", r.customerPn)
                        put("source", r.source); put("notes", r.notes); put("date_code", r.dateCode)
                        put("ref_des", r.refDes); put("scans", r.scans ?: 1); put("updated_at", stamp)
                        put("product_url", r.link); put("category", category)
                        put("datasheet_url", r.datasheet)
                        putSpecs(PartSpecs(r.value, r.tolerance, r.voltage, r.pkg).orElse(PartSpecs.fromDescription(r.description)), null)
                        r.unitCost?.let { put("unit_cost", it) }
                        r.minimum?.let { put("min_qty", it) }
                    })
                    if (newId > 0) writeStock(db, newId, fileStock, r.location)
                    added++
                }
                if (!dryRun) {
                    db.insert("scan_log", null, ContentValues().apply {
                        put("ts", now)
                        put("part_key", r.mpn.ifBlank { r.customerPn.ifBlank { r.distributorPn } })
                        put("mode", if (replace) "restore" else "import")
                        put("qty", fileQty); put("total", fileQty); put("location", r.location)
                        put("source", "CSV import"); put("distributor_pn", r.distributorPn)
                        put("customer_pn", r.customerPn); put("raw", "")
                    })
                }
            }
            if (!dryRun) db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        replacedPhotos.forEach(::trashPhoto)
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

    fun all(): List<PartRow> {
        val stock = HashMap<Long, LinkedHashMap<String, Int>>()
        readableDatabase.rawQuery("SELECT part_id, location, qty FROM part_stock ORDER BY id", null).use { c ->
            while (c.moveToNext()) stock.getOrPut(c.getLong(0)) { LinkedHashMap() }[c.getString(1).orEmpty()] = c.getInt(2)
        }
        return readableDatabase.rawQuery("SELECT * FROM parts ORDER BY category, UPPER(mpn), UPPER(customer_pn)", null).use { c ->
            buildList { while (c.moveToNext()) c.toRow().let { add(it.copy(stock = stock[it.id].orEmpty())) } }
        }
    }

    /** Saves the edit dialog. [stock] is the part's pieces per bin (one bin for most parts); [location] its main bin. */
    fun update(
        id: Long, stock: Map<String, Int>, location: String, description: String, unitCost: Double?, category: String,
        minQty: Int?,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val snap = snapshot(db, id) ?: return
            updateTx(db, id, stock, location, description, unitCost, category, minQty)
            record(db, id, "edit", snap)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        onChanged?.invoke()
    }

    private fun updateTx(
        db: SQLiteDatabase, id: Long, stock: Map<String, Int>, location: String, description: String, unitCost: Double?,
        category: String, minQty: Int?,
    ) {
        val old = byId(id)
        writeStock(db, id, stock, location)
        val v = ContentValues().apply {
            put("location", location.trim()); put("category", category)
            put("description", description.trim()); put("updated_at", now())
        }
        // A new description: re-read value / package from it, keeping what it doesn't mention.
        if (old != null && description.trim() != old.description) {
            v.putSpecs(PartSpecs.fromDescription(description).orElse(old.specs()), null)
        }
        // Changing the category by hand marks it as picked, so price refreshes don't move it again.
        if (old != null && category != old.category) v.put("category_picked", 1)
        if (unitCost != null) v.put("unit_cost", unitCost) else v.putNull("unit_cost")
        if (old != null && unitCost != old.unitCost) {
            v.put("price_source", ""); v.put("price_checked_at", "")
        }
        if (minQty != null && minQty > 0) v.put("min_qty", minQty) else v.putNull("min_qty")
        db.update("parts", v, "id = ?", arrayOf(id.toString()))
    }

    private fun byId(id: Long): PartRow? =
        readableDatabase.rawQuery("SELECT * FROM parts WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.toRow() else null
        }?.let { it.copy(stock = stockOf(readableDatabase, it.id)) }

    /**
     * Applies a distributor lookup to a saved part: price (with source and date), description, link, and the
     * category unless the user picked it. Doesn't fire [onChanged]; the caller does once after a batch.
     */
    fun applyLookup(id: Long, u: LookupUpdate) {
        val old = byId(id) ?: return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val snap = snapshot(db, id)
            applyLookupTx(db, old, u)
            record(db, id, "lookup ${u.source}".trim(), snap)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun applyLookupTx(db: SQLiteDatabase, old: PartRow, u: LookupUpdate) {
        val id = old.id
        val now = now()
        val v = ContentValues().apply {
            if (u.unitCost != null) {
                put("unit_cost", u.unitCost); put("price_source", u.source); put("price_checked_at", now)
            }
            if (u.description.isNotBlank()) put("description", u.description.trim())
            if (u.productUrl.isNotBlank()) put("product_url", u.productUrl)
            if (!old.categoryPicked && u.category.isNotBlank()) put("category", u.category)
            if (u.datasheetUrl.isNotBlank()) put("datasheet_url", u.datasheetUrl)
            putSpecs(u.specs.orElse(PartSpecs.fromDescription(u.description)), null)
            put("updated_at", now)
        }
        db.update("parts", v, "id = ?", arrayOf(id.toString()))
    }

    /** Tells listeners (auto backup) that parts changed, e.g. after a batch of [applyLookup] calls. */
    fun notifyChanged() {
        onChanged?.invoke()
    }

    /** Deletes the part. The history keeps it as it was, so Undo brings it back (same id) for [SNAPSHOT_DAYS] days. */
    fun delete(id: Long) {
        val db = writableDatabase
        var photos = emptyList<String>()
        db.beginTransaction()
        try {
            val snap = snapshot(db, id)
            photos = photoFiles(db, id)
            db.delete("parts", "id = ?", arrayOf(id.toString()))
            db.delete("kit_items", "part_id = ?", arrayOf(id.toString()))
            db.delete("part_stock", "part_id = ?", arrayOf(id.toString()))
            db.delete("part_photos", "part_id = ?", arrayOf(id.toString()))
            if (snap != null) record(db, id, "delete", snap)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // Its photos leave the part now; the files are removed for good when Undo can no longer bring the part back.
        photos.forEach(::trashPhoto)
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
        val snap = snapshot(db, id)
        // Taken from the main bin first, then the other bins in order.
        val total = writeStock(db, id, Locations.take(stockOrLegacy(db, r), r.location, qty), r.location)
        db.update("parts", ContentValues().apply { put("updated_at", now) }, "id = ?", arrayOf(id.toString()))
        val logId = db.insert("scan_log", null, ContentValues().apply {
            put("ts", now)
            put("part_key", r.mpn.ifBlank { r.customerPn.ifBlank { r.distributorPn } })
            put("mode", "use")
            put("qty", -qty); put("total", total); put("location", r.location)
            put("source", note); put("distributor_pn", r.distributorPn)
            put("customer_pn", r.customerPn); put("raw", "")
        })
        record(db, id, if (note == "Used") "use" else "use · $note", snap, logId)
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
        grouped {
            db.beginTransaction()
            try {
                val now = now()
                for (line in plan) useTx(db, line.part.id, line.need, "Kit $kit × $boards", now)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        onChanged?.invoke()
        return plan
    }

    // ---------------------------------------------------------------- projects / BOMs

    /** Saves a new project with its BOM lines; [partIds] (same order, optional) are parts already known. */
    fun createProject(name: String, source: String, lines: List<BomCsv.Line>, partIds: List<Long?> = emptyList()): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = now()
            val id = db.insert("projects", null, ContentValues().apply {
                put("name", name.trim().ifBlank { "Project" }); put("source", source)
                put("created_at", now); put("updated_at", now)
            })
            lines.forEachIndexed { i, l ->
                db.insert("project_lines", null, ContentValues().apply {
                    put("project_id", id); put("line", i + 1)
                    put("mpn", l.mpn); put("customer_pn", l.customerPn); put("distributor_pn", l.distributorPn)
                    put("manufacturer", l.manufacturer); put("value", l.value); put("footprint", l.footprint)
                    put("description", l.description); put("ref_des", l.refDes); put("per_board", maxOf(1, l.perBoard))
                    partIds.getOrNull(i)?.let { put("part_id", it) }
                })
            }
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    fun projects(): List<ProjectSummary> =
        readableDatabase.rawQuery(
            "SELECT p.id, p.name, p.source, (SELECT COUNT(*) FROM project_lines l WHERE l.project_id = p.id), " +
                "p.updated_at FROM projects p ORDER BY p.updated_at DESC, p.id DESC", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(ProjectSummary(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3), c.getString(4).orEmpty()))
                }
            }
        }

    fun project(id: Long): ProjectSummary? = projects().firstOrNull { it.id == id }

    fun projectLines(projectId: Long): List<ProjectLine> =
        readableDatabase.rawQuery(
            "SELECT * FROM project_lines WHERE project_id = ? ORDER BY line, id", arrayOf(projectId.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val pc = c.getColumnIndexOrThrow("part_id")
                    add(ProjectLine(
                        id = c.getLong(c.getColumnIndexOrThrow("id")), mpn = c.str("mpn"), customerPn = c.str("customer_pn"),
                        distributorPn = c.str("distributor_pn"), manufacturer = c.str("manufacturer"),
                        value = c.str("value"), footprint = c.str("footprint"), description = c.str("description"),
                        refDes = c.str("ref_des"), perBoard = c.int("per_board"),
                        partId = if (c.isNull(pc)) null else c.getLong(pc),
                    ))
                }
            }
        }

    /** Picks the stock part for a BOM line by hand (null = back to automatic matching). */
    fun setLinePart(lineId: Long, partId: Long?) {
        writableDatabase.update("project_lines", ContentValues().apply {
            if (partId == null) putNull("part_id") else put("part_id", partId)
        }, "id = ?", arrayOf(lineId.toString()))
    }

    /** Deletes the project and its BOM lines; stock is not touched. */
    fun deleteProject(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("project_lines", "project_id = ?", arrayOf(id.toString()))
            db.delete("projects", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Stock as the build maths sees it. */
    fun planStock(): List<BuildPlan.Stock> = all().map { p ->
        BuildPlan.Stock().apply {
            id = p.id; mpn = p.mpn; customerPn = p.customerPn; distributorPn = p.distributorPn
            distributor = p.distributor; manufacturer = p.manufacturer; description = p.description
            link = p.link(); onHand = p.quantity; unitCost = p.unitCost
        }
    }

    /** Subtracts a project build ([use]: part id → pieces) in one transaction, logging a "use" per part. */
    fun buildProject(projectId: Long, name: String, use: Map<Long, Int>, boards: Int) {
        val db = writableDatabase
        grouped {
            db.beginTransaction()
            try {
                val now = now()
                for ((partId, qty) in use) if (qty > 0) useTx(db, partId, qty, "Project $name × $boards", now)
                db.update("projects", ContentValues().apply { put("updated_at", now) }, "id = ?", arrayOf(projectId.toString()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        onChanged?.invoke()
    }

    /** A scanned Entrust kit as BOM lines, each already tied to its stock part. */
    fun kitAsBom(kit: String): Pair<List<BomCsv.Line>, List<Long?>> {
        val plan = kitPlan(kit, 1)
        val lines = plan.map { k ->
            BomCsv.Line().also {
                it.mpn = k.part.mpn; it.customerPn = k.part.customerPn; it.distributorPn = k.part.distributorPn
                it.manufacturer = k.part.manufacturer; it.description = k.part.description
                it.refDes = k.refDes; it.perBoard = k.perBoard
            }
        }
        return lines to plan.map { it.part.id }
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
                it.specValue, it.tolerance, it.voltage, it.pkg, it.datasheetUrl, Locations.format(it.bins()),
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

    /**
     * Writes the non-blank spec fields. With [current], only fills fields that are blank there (a label read never
     * overwrites specs that came from a lookup).
     */
    private fun ContentValues.putSpecs(sp: PartSpecs, current: PartSpecs?) {
        val pairs = listOf(
            Triple("spec_value", sp.value, current?.value), Triple("tolerance", sp.tolerance, current?.tolerance),
            Triple("voltage", sp.voltage, current?.voltage), Triple("package", sp.pkg, current?.pkg),
        )
        for ((col, new, cur) in pairs) {
            if (new.isNotBlank() && (cur == null || cur.isBlank())) put(col, new)
        }
    }

    private fun ContentValues.take(column: String, new: String) {
        if (new.isNotBlank()) put(column, new)
    }

    private fun ContentValues.fill(column: String, current: String, new: String) {
        if (current.isBlank() && new.isNotBlank()) put(column, new)
    }

    private fun PartRow.key() = mpn.ifBlank { customerPn.ifBlank { distributorPn } }

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
            specValue = str("spec_value"), tolerance = str("tolerance"), voltage = str("voltage"),
            pkg = str("package"), datasheetUrl = str("datasheet_url"),
        )
    }

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        /** Days a change can still be undone (snapshots older than this are dropped). */
        const val SNAPSHOT_DAYS = 30L
        private const val PURGED = "purged"
        private const val DAY_MS = 24L * 60 * 60 * 1000
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
