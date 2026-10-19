package com.partstock.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.partstock.scanner.parse.BatchRules
import com.partstock.scanner.parse.Categories
import com.partstock.scanner.parse.LabelBarcode
import com.partstock.scanner.parse.LabelParser
import com.partstock.scanner.parse.Links
import com.partstock.scanner.parse.OcrLine
import com.partstock.scanner.parse.ParsedPart
import com.partstock.scanner.parse.PartSpecs
import com.partstock.scanner.parse.ScanMerger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Where a queued bag stands, shown on its card in the batch tray. */
enum class BatchStatus { LOOKING_UP, READY, CHECK, DUPLICATE }

/**
 * One bag scanned in batch mode. The OCR lines and barcodes are kept (not the parsed fields), so the bag is parsed the
 * same way as a normal scan when the queue is loaded again. [photoFile] is a name in [BatchQueue.photoDir].
 */
data class BatchItem(
    val id: String,
    val createdAt: Long,
    val frames: List<List<OcrLine>>,
    val codes: List<String>,
    val photoFile: String,
    val lookup: String,
    val lookupNote: String = "",
    val found: LookupFound? = null,
    /** This bag's fingerprint is in the scan log already (checked when queued and again before saving). */
    val savedBefore: Boolean = false,
) {
    /** Parsed once per bag (status changes copy the item but never its OCR lines). */
    val part: ParsedPart get() = BatchQueue.partOf(this)
}

/** A bag with what the confidence rules say about it, in the context of the whole queue. */
data class BatchCard(val item: BatchItem, val status: BatchStatus, val reasons: List<String>) {
    val part: ParsedPart get() = item.part
}

/** Batch file format, parsing and evaluation (no Android UI). */
object BatchQueue {
    private const val FILE = "queue.json"

    fun dir(context: Context): File = File(context.applicationContext.filesDir, "batch").apply { mkdirs() }

    fun photoDir(context: Context): File = dir(context)

    private val parsed = ConcurrentHashMap<String, ParsedPart>()

    fun partOf(item: BatchItem): ParsedPart = parsed.getOrPut(item.id) { parse(item.frames, item.codes) }

    fun parse(frames: List<List<OcrLine>>, codes: List<String>): ParsedPart =
        LabelBarcode.apply(ScanMerger.merge(frames.map { LabelParser.parse(it) }), codes)

    /** Rules applied to every bag; a bag is compared with the bags queued before it. */
    fun evaluate(items: List<BatchItem>): List<BatchCard> = items.mapIndexed { i, item ->
        var same = BatchRules.DIFFERENT
        for (j in 0 until i) same = maxOf(same, BatchRules.sameBag(items[j].part, item.part))
        val reasons = BatchRules.reasons(item.part, item.lookup, item.savedBefore, same)
        val status = when {
            BatchRules.isDuplicate(item.savedBefore, same) -> BatchStatus.DUPLICATE
            item.lookup == BatchRules.LOOKUP_PENDING -> BatchStatus.LOOKING_UP
            reasons.isEmpty() -> BatchStatus.READY
            else -> BatchStatus.CHECK
        }
        BatchCard(item, status, reasons)
    }

    fun load(context: Context): List<BatchItem> {
        val f = File(dir(context), FILE)
        if (!f.isFile) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }
        } catch (e: Exception) {
            emptyList() // an unreadable queue file is dropped rather than crashing the scan screen
        }
    }

    fun store(context: Context, items: List<BatchItem>) {
        val dir = dir(context)
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(JSONArray().apply { items.forEach { put(toJson(it)) } }.toString())
        if (!tmp.renameTo(File(dir, FILE))) {
            File(dir, FILE).writeText(tmp.readText())
            tmp.delete()
        }
    }

    /** Writes the label photo as a JPEG in the batch folder. Returns its file name. Call off the main thread. */
    fun savePhoto(context: Context, bmp: Bitmap): String {
        val scale = minOf(1f, PartPhotos.MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height))
        val out = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        } else bmp
        val name = "b-" + UUID.randomUUID().toString().replace("-", "").take(16) + ".jpg"
        try {
            FileOutputStream(File(photoDir(context), name)).use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } finally {
            if (out !== bmp) out.recycle()
        }
        return name
    }

    fun loadPhoto(context: Context, name: String): Bitmap? {
        if (name.isBlank()) return null
        val f = File(photoDir(context), name)
        return if (f.isFile) BitmapFactory.decodeFile(f.path) else null
    }

    fun deletePhoto(context: Context, name: String) {
        if (name.isNotBlank()) File(photoDir(context), name).delete()
    }

    /** The entry "Save all confident" saves for [item]: added to stock in [bin], with the lookup's details. */
    fun entry(item: BatchItem, bin: String): ScanEntry {
        val p = item.part
        val f = item.found
        val isLabelDistributor = f != null && f.source == p.distributor
        val mpn = (if ((p.mpn.isNullOrBlank() || p.mpnDerived) && f != null && f.mpn.isNotBlank()) f.mpn else p.mpn).orEmpty()
        val distPn = (if (isLabelDistributor && f!!.distributorPn.isNotBlank()) f.distributorPn else p.distributorPn).orEmpty()
        val description = f?.description?.takeIf { it.isNotBlank() } ?: p.description.orEmpty()
        val manufacturer = p.manufacturer?.takeIf { it.isNotBlank() } ?: f?.manufacturer.orEmpty()
        val category = if (f != null) Categories.classify(*(f.categories + listOf(f.description, description)).toTypedArray())
        else Categories.classify(p.description, p.notes)
        val url = f?.url?.takeIf { it.isNotBlank() } ?: Links.productUrl(p.distributor, distPn, mpn).orEmpty()
        return ScanEntry(
            mpn = mpn.trim(), description = description.trim(), manufacturer = manufacturer.trim(),
            quantity = p.quantity ?: 0, addMode = true, location = bin.trim(),
            distributor = p.distributor.orEmpty(), distributorPn = distPn.trim(),
            customerPn = p.customerPn.orEmpty().trim(), source = p.labelType, notes = p.notes.orEmpty().trim(),
            dateCode = p.dateCode.orEmpty(), refDes = p.refDes.orEmpty(), raw = p.rawText.take(1500),
            unitCost = f?.unitPrice, productUrl = url,
            category = category, descriptionFromMouser = f != null && f.description.isNotBlank(),
            priceSource = if (f?.unitPrice != null) f.source else "",
            bagFingerprint = p.bagFingerprint().orEmpty(),
            kitName = p.kitName.orEmpty(), kitPerBoard = p.kitPerBoard, kitQuantity = p.kitQuantity, kitItem = p.kitItem,
            datasheetUrl = f?.datasheetUrl.orEmpty(), specs = f?.specs ?: PartSpecs.EMPTY,
        )
    }

    private fun toJson(i: BatchItem): JSONObject = JSONObject().apply {
        put("id", i.id); put("createdAt", i.createdAt); put("photo", i.photoFile)
        put("lookup", i.lookup); put("note", i.lookupNote); put("savedBefore", i.savedBefore)
        put("codes", JSONArray(i.codes))
        put("frames", JSONArray().apply {
            for (frame in i.frames) put(JSONArray().apply {
                for (l in frame) put(JSONArray().put(l.text).put(l.left).put(l.top).put(l.right).put(l.bottom))
            })
        })
        i.found?.let { f ->
            put("found", JSONObject().apply {
                put("source", f.source); put("mpn", f.mpn); put("description", f.description)
                put("manufacturer", f.manufacturer); put("distributorPn", f.distributorPn)
                if (f.unitPrice != null) put("unitPrice", f.unitPrice)
                put("url", f.url); put("datasheetUrl", f.datasheetUrl); put("categories", JSONArray(f.categories))
                put("specs", JSONArray().put(f.specs.value).put(f.specs.tolerance).put(f.specs.voltage).put(f.specs.pkg))
            })
        }
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }

    private fun fromJson(o: JSONObject): BatchItem? {
        val framesJson = o.optJSONArray("frames") ?: return null
        val frames = (0 until framesJson.length()).map { fi ->
            val lines = framesJson.optJSONArray(fi) ?: JSONArray()
            (0 until lines.length()).mapNotNull { li ->
                val a = lines.optJSONArray(li) ?: return@mapNotNull null
                OcrLine(a.optString(0), a.optInt(1), a.optInt(2), a.optInt(3), a.optInt(4))
            }
        }
        val found = o.optJSONObject("found")?.let { f ->
            val s = f.optJSONArray("specs").strings()
            LookupFound(
                source = f.optString("source"), mpn = f.optString("mpn"), description = f.optString("description"),
                manufacturer = f.optString("manufacturer"), distributorPn = f.optString("distributorPn"),
                unitPrice = if (f.has("unitPrice")) f.optDouble("unitPrice") else null,
                url = f.optString("url"), datasheetUrl = f.optString("datasheetUrl"),
                categories = f.optJSONArray("categories").strings(),
                specs = if (s.size == 4) PartSpecs(s[0], s[1], s[2], s[3]) else PartSpecs.EMPTY,
            )
        }
        return BatchItem(
            id = o.optString("id").ifBlank { return null }, createdAt = o.optLong("createdAt"),
            frames = frames, codes = o.optJSONArray("codes").strings(), photoFile = o.optString("photo"),
            lookup = o.optString("lookup", BatchRules.LOOKUP_PENDING), lookupNote = o.optString("note"),
            found = found, savedBefore = o.optBoolean("savedBefore"),
        )
    }
}

/**
 * Holds the batch queue for the scan screen: adds bags, looks them up one at a time in the background (paced, waiting
 * out rate limits), keeps the queue in a file so it survives leaving the app, and saves the confident bags.
 */
class BatchViewModel(
    private val context: Context,
    private val db: InventoryDb,
    private val prefs: Prefs,
    private val secrets: SecretStore,
) : ViewModel() {
    private val _items = MutableStateFlow<List<BatchItem>>(emptyList())
    val items: StateFlow<List<BatchItem>> = _items

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val writeLock = Mutex()
    /** Set after a key was rejected: the remaining bags are not looked up until the app is restarted. */
    @Volatile private var stopReason: String? = null
    /** False until the saved queue was read, so an early write can't replace the file with a shorter list. */
    @Volatile private var loaded = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = BatchQueue.load(context).map { it.copy(savedBefore = db.findBag(it.part.bagFingerprint()) != null) }
            _items.update { current -> saved + current.filter { c -> saved.none { it.id == c.id } } }
            loaded = true
            persist()
            worker()
        }
    }

    private suspend fun persist() = writeLock.withLock {
        if (!loaded) return@withLock
        try {
            withContext(Dispatchers.IO) { BatchQueue.store(context, _items.value) }
        } catch (_: Exception) {
            // Keeping the queue on disk is best effort; the bags are still in memory.
        }
    }

    private fun change(f: (List<BatchItem>) -> List<BatchItem>) {
        _items.update(f)
        viewModelScope.launch { persist() }
    }

    /** Which lookup a bag gets: DigiKey for DigiKey labels when set up, otherwise Mouser when on. */
    private fun lookupPossible(p: ParsedPart): Boolean =
        (p.distributor == ParsedPart.DIGIKEY && secrets.hasDigiKey()) || (secrets.hasMouserKey() && prefs.autoMouserLookup)

    /** Queues a scanned bag; parsing, the duplicate check and the photo file happen off the main thread. */
    fun add(frames: List<List<OcrLine>>, photo: Bitmap?, codes: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val photoFile = photo?.let {
                try {
                    BatchQueue.savePhoto(context, it)
                } catch (e: Exception) {
                    null
                }
            }.orEmpty()
            val draft = BatchItem(
                id = UUID.randomUUID().toString(), createdAt = System.currentTimeMillis(),
                frames = frames, codes = codes, photoFile = photoFile, lookup = BatchRules.LOOKUP_PENDING,
            )
            val p = draft.part
            val item = draft.copy(
                lookup = if (lookupPossible(p)) BatchRules.LOOKUP_PENDING else BatchRules.LOOKUP_NONE,
                savedBefore = db.findBag(p.bagFingerprint()) != null,
            )
            change { it + item }
            wake.trySend(Unit)
        }
    }

    fun remove(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        change { list -> list.filterNot { it.id == id } }
        viewModelScope.launch(Dispatchers.IO) { BatchQueue.deletePhoto(context, item.photoFile) }
    }

    fun clear() {
        val all = _items.value
        change { emptyList() }
        viewModelScope.launch(Dispatchers.IO) { all.forEach { BatchQueue.deletePhoto(context, it.photoFile) } }
    }

    fun photo(item: BatchItem): Bitmap? = BatchQueue.loadPhoto(context, item.photoFile)

    /**
     * Saves every bag that passes the rules, rechecked just now against the scan log, as one change (one Undo).
     * Returns how many were saved.
     */
    suspend fun saveConfident(bin: String): Int = withContext(Dispatchers.IO) {
        val fresh = _items.value.map { it.copy(savedBefore = db.findBag(it.part.bagFingerprint()) != null) }
        val ready = BatchQueue.evaluate(fresh).filter { it.status == BatchStatus.READY }.map { it.item }
        if (ready.isEmpty()) {
            _items.update { list -> list.map { i -> fresh.firstOrNull { it.id == i.id } ?: i } }
            return@withContext 0
        }
        val copied = ArrayList<String>()
        val entries = ready.map { item ->
            var photo = ""
            val src = File(BatchQueue.photoDir(context), item.photoFile)
            if (item.photoFile.isNotBlank() && src.isFile) {
                val name = "p-" + UUID.randomUUID().toString().replace("-", "").take(16) + ".jpg"
                try {
                    src.copyTo(PartPhotos.file(context, name))
                    photo = name
                    copied.add(name)
                } catch (_: Exception) {
                    // the photo never blocks the save
                }
            }
            BatchQueue.entry(item, bin).copy(photoFile = photo)
        }
        try {
            db.saveAll(entries)
        } catch (e: Exception) {
            copied.forEach { PartPhotos.file(context, it).delete() }
            throw e
        }
        val savedIds = ready.map { it.id }.toSet()
        change { list -> list.filterNot { it.id in savedIds }.map { i -> fresh.firstOrNull { it.id == i.id } ?: i } }
        ready.forEach { BatchQueue.deletePhoto(context, it.photoFile) }
        ready.size
    }

    /** Looks up pending bags one at a time, oldest first, until none is left; then waits for the next bag. */
    private suspend fun worker() {
        while (true) {
            val next = _items.value.firstOrNull { it.lookup == BatchRules.LOOKUP_PENDING }
            if (next == null) {
                wake.receive()
                continue
            }
            val result = lookUp(next)
            if (result == null) {
                // Rate limit: wait a minute and try the same bag again.
                setLookup(next.id, BatchRules.LOOKUP_PENDING, "Rate limit reached, waiting a minute…", null)
                delay(60_000)
                continue
            }
            setLookup(next.id, result.state, result.note, result.found)
        }
    }

    private fun setLookup(id: String, state: String, note: String, found: LookupFound?) {
        change { list -> list.map { if (it.id == id) it.copy(lookup = state, lookupNote = note, found = found ?: it.found) else it } }
    }

    /** Null = rate limited (retry later). */
    private suspend fun lookUp(item: BatchItem): LookupResult? {
        stopReason?.let { return LookupResult(BatchRules.LOOKUP_FAILED, note = it) }
        val p = item.part
        val qty = p.quantity ?: 1
        val digiKey = if (p.distributor == ParsedPart.DIGIKEY) secrets.getDigiKey() else null
        val mouserKey = if (prefs.autoMouserLookup) secrets.getMouserKey() else null
        return try {
            when {
                digiKey != null -> PartLookup.digiKey(digiKey.first, digiKey.second, p.distributorPn.orEmpty().trim(), p.mpn.orEmpty().trim(), qty)
                mouserKey != null -> {
                    val isMouser = p.distributor == ParsedPart.MOUSER
                    val pn = (if (isMouser && !p.distributorPn.isNullOrBlank()) p.distributorPn else p.mpn).orEmpty().trim()
                    PartLookup.mouser(mouserKey, pn, qty, isMouser)
                }
                else -> LookupResult(BatchRules.LOOKUP_NONE)
            }
        } catch (e: MouserException) {
            when {
                e.rateLimited -> null
                e.badKey -> {
                    stopReason = e.message
                    LookupResult(BatchRules.LOOKUP_FAILED, note = e.message.orEmpty())
                }
                else -> LookupResult(BatchRules.LOOKUP_FAILED, note = e.message.orEmpty())
            }
        } catch (e: DigiKeyException) {
            val msg = e.message.orEmpty()
            if (msg.contains("rate limit", ignoreCase = true)) {
                null
            } else {
                if (msg.contains("rejected", ignoreCase = true)) stopReason = msg
                LookupResult(BatchRules.LOOKUP_FAILED, note = msg)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            var msg = e.message ?: e.javaClass.simpleName
            mouserKey?.let { msg = msg.replace(it, "***") }
            digiKey?.let { msg = msg.replace(it.second, "***") }
            LookupResult(BatchRules.LOOKUP_FAILED, note = "Lookup failed: $msg")
        }
    }
}
