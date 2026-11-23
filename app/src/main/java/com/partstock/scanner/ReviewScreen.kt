package com.partstock.scanner

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.partstock.scanner.parse.Categories
import com.partstock.scanner.parse.FieldReader
import com.partstock.scanner.parse.FieldStatus
import com.partstock.scanner.parse.LabelBarcode
import com.partstock.scanner.parse.Locations
import com.partstock.scanner.parse.OcrLine
import com.partstock.scanner.parse.PhotoBoxes
import com.partstock.scanner.parse.Links
import com.partstock.scanner.parse.ParsedPart
import com.partstock.scanner.parse.PartNumberVariants
import com.partstock.scanner.parse.PartSpecs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    part: ParsedPart,
    /** One of the photos that was read, to compare the fields against; null for older callers. */
    photo: Bitmap? = null,
    /** That photo's OCR lines as size-independent boxes (PhotoBoxes), drawn over it; empty = just the photo. */
    photoBoxes: List<OcrLine> = emptyList(),
    db: InventoryDb,
    prefs: Prefs,
    secrets: SecretStore,
    onSaved: (String) -> Unit,
    onCancel: () -> Unit,
    /** Bin to start with instead of the last one used (a batch's bin). */
    initialLocation: String? = null,
) {
    val scope = rememberCoroutineScope()
    var mpn by remember { mutableStateOf(part.mpn.orEmpty()) }
    var description by remember { mutableStateOf(part.description.orEmpty()) }
    var manufacturer by remember { mutableStateOf(part.manufacturer.orEmpty()) }
    var qty by remember { mutableStateOf(part.quantity?.toString().orEmpty()) }
    var location by remember { mutableStateOf(initialLocation?.takeIf { it.isNotBlank() } ?: prefs.lastLocation) }
    var distributorPn by remember { mutableStateOf(part.distributorPn.orEmpty()) }
    var customerPn by remember { mutableStateOf(part.customerPn.orEmpty()) }
    var notes by remember { mutableStateOf(part.notes.orEmpty()) }
    var category by remember { mutableStateOf(Categories.classify(part.description, part.notes)) }
    var categoryPicked by remember { mutableStateOf(false) } // the user chose one; lookups leave it alone
    // Set by a Mouser or DigiKey lookup: the distributor's description replaces the one saved from a label.
    var descriptionFromMouser by remember { mutableStateOf(false) }
    var addMode by remember { mutableStateOf(true) }
    var unitCost by remember { mutableStateOf("") }
    var priceSource by remember { mutableStateOf("") } // "Mouser" / "DigiKey" when a lookup filled the price
    // Product page returned by Mouser's lookup; otherwise a link is built from the part numbers.
    var apiUrl by remember { mutableStateOf<String?>(null) }
    // Datasheet link and value / package attributes from a lookup.
    var datasheetUrl by remember { mutableStateOf("") }
    var apiSpecs by remember { mutableStateOf(PartSpecs.EMPTY) }
    var looking by remember { mutableStateOf(false) }
    var lookingAt by remember { mutableStateOf("") } // "Mouser" / "DigiKey" while a lookup runs
    var lookupMsg by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val mouserKey = remember { secrets.getMouserKey() }
    val digiKey = remember { secrets.getDigiKey() }
    val isDigiKeyLabel = part.distributor == ParsedPart.DIGIKEY

    var existing by remember { mutableStateOf<Match?>(null) }
    val fingerprint = remember { part.bagFingerprint() }
    var seenBag by remember { mutableStateOf<BagHit?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showRaw by remember { mutableStateOf(false) }
    // Per-field rescan: which field the camera is re-reading, and what came of the last one.
    var rescan by remember { mutableStateOf<FieldReader.Field?>(null) }
    var rescanMsg by remember { mutableStateOf<String?>(null) }
    var showPhoto by remember { mutableStateOf(false) }
    // The field being edited: its source line is highlighted on the photo.
    var focusedField by remember { mutableStateOf<FieldReader.Field?>(null) }
    fun focusChanged(f: FieldReader.Field, focused: Boolean) {
        if (focused) focusedField = f else if (focusedField == f) focusedField = null
    }
    val thumb = remember(photo) { photo?.asImageBitmap() }
    // Field → (value, who set it) for values put there by a lookup or a close-up re-read, so the status line can say
    // where a value came from and notice when it's typed over.
    val setBy = remember { mutableStateMapOf<String, Pair<String, String>>() }
    fun mark(field: String, value: String, by: String) {
        setBy[field] = value to by
    }
    val focus = remember { STATUS_FIELDS.associateWith { FocusRequester() } }

    /** Fills [f] from [lines] read off a close-up, or ([fromPhoto]) from a line tapped on the review photo. */
    fun applyRescan(f: FieldReader.Field, lines: List<OcrLine>, fromPhoto: Boolean = false) {
        val v = FieldReader.read(f, lines)
        val name = FieldReader.label(f)
        val saw = lines.joinToString(" ") { it.text }.take(60)
        if (v == null) {
            rescanMsg = if (fromPhoto) "That line doesn't look like a $name (\"$saw\"). Pick another line, or type it."
            else "Couldn't read a $name there (saw \"$saw\"). Tap the camera icon to try again, or type it."
            return
        }
        when (f) {
            FieldReader.Field.MPN -> mpn = v
            FieldReader.Field.DISTRIBUTOR_PN -> distributorPn = v
            FieldReader.Field.CUSTOMER_PN -> customerPn = v
            FieldReader.Field.QUANTITY -> qty = v
            FieldReader.Field.DESCRIPTION -> {
                description = v
                descriptionFromMouser = false
            }
        }
        val key = when (f) {
            FieldReader.Field.MPN -> LabelBarcode.F_MPN
            FieldReader.Field.DISTRIBUTOR_PN -> LabelBarcode.F_DIST_PN
            FieldReader.Field.CUSTOMER_PN -> LabelBarcode.F_CUST_PN
            FieldReader.Field.QUANTITY -> LabelBarcode.F_QTY
            FieldReader.Field.DESCRIPTION -> F_DESCRIPTION
        }
        mark(key, v, FieldStatus.REREAD)
        rescanMsg = if (fromPhoto) "Took the $name from the photo: $v" else "Re-read $name: $v"
    }

    // Check whether this part is already in stock; re-checks as the part numbers are edited.
    LaunchedEffect(mpn, customerPn, distributorPn) {
        existing = withContext(Dispatchers.IO) { db.find(mpn.trim(), customerPn.trim(), distributorPn.trim()) }
    }
    // Same bag saved before? Then a second "Add" would double-count it, so default to "Set".
    LaunchedEffect(fingerprint) {
        seenBag = withContext(Dispatchers.IO) { db.findBag(fingerprint) }
        if (seenBag != null) addMode = false
    }
    val hasKey = mpn.isNotBlank() || customerPn.isNotBlank() || distributorPn.isNotBlank()
    val link: String? = apiUrl ?: Links.productUrl(part.distributor, distributorPn, mpn)

    // Keep the price already on file when this part is rescanned, unless a new one is typed or looked up.
    LaunchedEffect(existing) {
        val known = existing?.row?.unitCost
        if (known != null && unitCost.isBlank()) unitCost = InventoryDb.fmtCost(known)
        val savedCat = existing?.row?.category
        val savedRow = existing?.row
        if (savedRow != null && savedRow.categoryPicked && !categoryPicked) {
            // Picked by hand on an earlier save: keep it, and keep lookups from changing it.
            category = savedRow.category
            categoryPicked = true
        } else if (savedCat != null && savedCat != Categories.OTHER && !categoryPicked && !descriptionFromMouser) {
            category = savedCat
        }
    }

    /** [auto]: started by the review screen itself, so stay quiet when there's no part number to look up. */
    fun lookupMouser(auto: Boolean = false) {
        val key = mouserKey ?: return
        val pn = if (part.distributor == ParsedPart.MOUSER && distributorPn.isNotBlank()) distributorPn.trim() else mpn.trim()
        if (pn.isBlank()) {
            if (!auto) lookupMsg = "Enter a part number first."
            return
        }
        val q = qty.trim().toIntOrNull() ?: 1
        scope.launch {
            looking = true
            lookingAt = "Mouser"
            lookupMsg = null
            try {
                // 1. As read, accepting the nearest result if one or two characters were misread.
                var found = MouserApi.lookup(key, pn, q, maxDistance = 2)
                // 2. Mouser may not list near-misses for a wrong number, so search on the start of it
                //    (misreads at the end, like #PBE for #PBF, are common) and pick the nearest.
                if (found == null) {
                    val stem = pn.substringBefore('#').let { if (it.length < pn.length) it else pn.dropLast(3) }
                    if (stem.length >= 6) found = MouserApi.lookup(key, stem, q, wanted = pn, maxDistance = 2)
                }
                // 3. Swap look-alike characters (I/1/L, O/0, E/F...) one at a time.
                if (found == null) {
                    for (v in PartNumberVariants.confusions(pn, 12)) {
                        found = MouserApi.lookup(key, v, q) ?: continue
                        break
                    }
                }
                val r = found
                val fixedFrom = if (r != null && r.mouserPn.isNotBlank() &&
                    PartNumberVariants.distance(r.mouserPn, pn) > 0 &&
                    PartNumberVariants.distance(r.mpn, pn) > 0) pn else null
                if (r == null) {
                    lookupMsg = "Mouser has no match for $pn. Check the number against the label and try again."
                } else {
                    if (r.unitPrice != null) {
                        unitCost = InventoryDb.fmtCost(r.unitPrice)
                        priceSource = "Mouser"
                    }
                    if (r.url.isNotBlank()) apiUrl = r.url
                    if (r.datasheetUrl.isNotBlank()) datasheetUrl = r.datasheetUrl
                    apiSpecs = r.specs
                    if (part.distributor == ParsedPart.MOUSER && r.mouserPn.isNotBlank()) {
                        distributorPn = r.mouserPn
                        mark(LabelBarcode.F_DIST_PN, r.mouserPn, "Mouser")
                    }
                    if (r.description.isNotBlank()) {
                        description = r.description
                        descriptionFromMouser = true
                        mark(F_DESCRIPTION, r.description, "Mouser")
                    }
                    if (!categoryPicked) category = Categories.classify(r.category, r.description, description)
                    if (manufacturer.isBlank() && r.manufacturer.isNotBlank()) {
                        manufacturer = r.manufacturer
                        mark(F_MANUFACTURER, r.manufacturer, "Mouser")
                    }
                    // A part number worked out from the label can be off by an OCR slip; Mouser's is exact.
                    if ((mpn.isBlank() || part.mpnDerived) && r.mpn.isNotBlank()) {
                        mpn = r.mpn
                        mark(LabelBarcode.F_MPN, r.mpn, "Mouser")
                    }
                    val base = if (r.unitPrice != null) {
                        "Mouser: ${r.currency} ${InventoryDb.fmtCost(r.unitPrice)} each at ${r.priceAtQty}+"
                    } else {
                        "Found on Mouser, but no price listed."
                    }
                    lookupMsg = if (fixedFrom != null) "$base. Fixed a likely misread: $fixedFrom → ${r.mouserPn}" else base
                }
            } catch (e: MouserException) {
                lookupMsg = e.message
            } catch (e: Exception) {
                lookupMsg = "Lookup failed: " + (e.message ?: e.javaClass.simpleName).replace(key, "***")
            } finally {
                looking = false
            }
        }
    }

    fun lookupDigiKey() {
        val (id, secret) = digiKey ?: return
        val dpn = distributorPn.trim()
        val m = mpn.trim()
        if (dpn.isBlank() && m.isBlank()) {
            lookupMsg = "Enter a part number first."
            return
        }
        val q = qty.trim().toIntOrNull() ?: 1
        scope.launch {
            looking = true
            lookingAt = "DigiKey"
            lookupMsg = null
            try {
                var found: DigiKeyPart? = null
                var tried = ""
                // DigiKey PN first, then the MPN. Each: exact, then nearest within 2 characters in a keyword search,
                // then the start of the number (misreads near the end are common), then look-alike swaps.
                for (pn in listOf(dpn, m).filter { it.isNotBlank() }.distinct()) {
                    tried = pn
                    found = DigiKeyApi.details(id, secret, pn, q)
                        ?: DigiKeyApi.search(id, secret, pn, q, maxDistance = 2)
                    if (found == null) {
                        val stem = if (pn.uppercase().endsWith("-ND")) pn.dropLast(3).dropLast(3) else pn.dropLast(3)
                        if (stem.length >= 6) found = DigiKeyApi.search(id, secret, stem, q, wanted = pn, maxDistance = 2)
                    }
                    if (found == null) {
                        for (v in PartNumberVariants.confusions(pn, 8)) {
                            found = DigiKeyApi.details(id, secret, v, q) ?: continue
                            break
                        }
                    }
                    if (found != null) break
                }
                val r = found
                if (r == null) {
                    lookupMsg = "DigiKey has no match for $tried. Check the number against the label and try again."
                } else {
                    val fixed = ArrayList<String>()
                    if (r.unitPrice != null) {
                        unitCost = InventoryDb.fmtCost(r.unitPrice)
                        priceSource = "DigiKey"
                    }
                    if (r.url.isNotBlank()) apiUrl = r.url
                    if (r.datasheetUrl.isNotBlank()) datasheetUrl = r.datasheetUrl
                    if (isDigiKeyLabel && r.digiKeyPn.isNotBlank() && dpn.isNotBlank() &&
                        PartNumberVariants.distance(r.digiKeyPn, dpn) in 1..2
                    ) {
                        fixed.add("$dpn → ${r.digiKeyPn}")
                        distributorPn = r.digiKeyPn
                        mark(LabelBarcode.F_DIST_PN, r.digiKeyPn, "DigiKey")
                    } else if (isDigiKeyLabel && dpn.isBlank() && r.digiKeyPn.isNotBlank()) {
                        distributorPn = r.digiKeyPn
                        mark(LabelBarcode.F_DIST_PN, r.digiKeyPn, "DigiKey")
                    }
                    if (r.description.isNotBlank()) {
                        description = r.description
                        descriptionFromMouser = true
                        mark(F_DESCRIPTION, r.description, "DigiKey")
                    }
                    if (!categoryPicked) {
                        category = Categories.classify(*(r.categories + listOf(r.description, description)).toTypedArray())
                    }
                    if (r.manufacturer.isNotBlank()) {
                        manufacturer = r.manufacturer
                        mark(F_MANUFACTURER, r.manufacturer, "DigiKey")
                    }
                    if (r.mpn.isNotBlank() && r.mpn != m) {
                        if (m.isNotBlank()) fixed.add("$m → ${r.mpn}")
                        mpn = r.mpn
                    }
                    if (r.mpn.isNotBlank()) mark(LabelBarcode.F_MPN, r.mpn, "DigiKey")
                    val base = if (r.unitPrice != null) {
                        "DigiKey: ${r.currency} ${InventoryDb.fmtCost(r.unitPrice)} each at ${r.priceAtQty}+"
                    } else {
                        "Found on DigiKey, but no price listed."
                    }
                    lookupMsg = if (fixed.isNotEmpty()) "$base. Corrected: ${fixed.joinToString(", ")}" else base
                }
            } catch (e: DigiKeyException) {
                lookupMsg = e.message
            } catch (e: Exception) {
                lookupMsg = "Lookup failed: " + (e.message ?: e.javaClass.simpleName).replace(secret, "***")
            } finally {
                looking = false
            }
        }
    }

    // Look up the price on Mouser right away (Settings switch, on by default once a key is saved). Runs once per
    // scan: editing the part number or quantity afterwards doesn't re-run it; the button is there for a retry.
    LaunchedEffect(Unit) {
        if (mouserKey != null && prefs.autoMouserLookup) lookupMouser(auto = true)
    }

    fun save() {
        error = null
        val q = qty.trim().toIntOrNull()
        val cost = unitCost.trim().toDoubleOrNull()
        when {
            !hasKey -> error = "Need at least a part number (MPN, customer PN or distributor PN)."
            q == null || q < 0 -> error = "Quantity must be a whole number."
            unitCost.isNotBlank() && (cost == null || cost < 0) -> error = "Unit cost must be a number."
            else -> scope.launch {
                saving = true
                prefs.lastLocation = location
                val entry = ScanEntry(
                    mpn = mpn.trim(), description = description.trim(), manufacturer = manufacturer.trim(),
                    quantity = q, addMode = addMode, location = location.trim(),
                    distributor = part.distributor.orEmpty(), distributorPn = distributorPn.trim(),
                    customerPn = customerPn.trim(), source = part.labelType, notes = notes.trim(),
                    dateCode = part.dateCode.orEmpty(), refDes = part.refDes.orEmpty(),
                    raw = part.rawText.take(1500),
                    unitCost = cost, productUrl = link.orEmpty(),
                    categoryPicked = categoryPicked, priceSource = if (cost != null) priceSource else "",
                    category = category, descriptionFromMouser = descriptionFromMouser,
                    bagFingerprint = fingerprint.orEmpty(),
                    kitName = part.kitName.orEmpty(), kitPerBoard = part.kitPerBoard,
                    kitQuantity = part.kitQuantity, kitItem = part.kitItem,
                    datasheetUrl = datasheetUrl, specs = apiSpecs,
                )
                val name = mpn.ifBlank { customerPn.ifBlank { distributorPn } }
                var photoFile = ""
                try {
                    val total = withContext(Dispatchers.IO) {
                        // The label photo is kept (resized JPEG, app-private) as the part's thumbnail.
                        photoFile = photo?.let {
                            try {
                                PartPhotos.save(context, it)
                            } catch (e: Exception) {
                                null // a photo that can't be written never blocks the save
                            }
                        }.orEmpty()
                        db.save(entry.copy(photoFile = photoFile))
                    }
                    onSaved("Saved $name — $total in stock")
                } catch (e: Exception) {
                    if (photoFile.isNotBlank()) PartPhotos.file(context, photoFile).delete()
                    error = "Save failed: ${e.message}"
                    saving = false
                }
            }
        }
    }

    // Per-field status and, for a part already in stock, what the save does to each stored value.
    val current = mapOf(
        LabelBarcode.F_MPN to mpn, F_DESCRIPTION to description, F_MANUFACTURER to manufacturer,
        LabelBarcode.F_QTY to qty, LabelBarcode.F_DIST_PN to distributorPn, LabelBarcode.F_CUST_PN to customerPn,
    )
    val initial = remember {
        mapOf(
            LabelBarcode.F_MPN to part.mpn.orEmpty(), F_DESCRIPTION to part.description.orEmpty(),
            F_MANUFACTURER to part.manufacturer.orEmpty(), LabelBarcode.F_QTY to part.quantity?.toString().orEmpty(),
            LabelBarcode.F_DIST_PN to part.distributorPn.orEmpty(), LabelBarcode.F_CUST_PN to part.customerPn.orEmpty(),
        )
    }
    val status = STATUS_FIELDS.associateWith { f ->
        val by = setBy[f]
        FieldStatus.of(f, part, current[f], initial[f], by?.first, by?.second)
    }
    val row = existing?.row
    val changes: Map<String, FieldStatus.Change?> = if (row == null) emptyMap() else mapOf(
        LabelBarcode.F_MPN to FieldStatus.change(row.mpn, mpn, false),
        // A looked-up description replaces the stored one; a label's only fills a blank one.
        F_DESCRIPTION to FieldStatus.change(row.description, description, descriptionFromMouser),
        F_MANUFACTURER to FieldStatus.change(row.manufacturer, manufacturer, false),
        // "Set" replaces this bin's count; "Add" adds to it, so there's no old value to show.
        LabelBarcode.F_QTY to if (addMode) null else FieldStatus.change(
            Locations.qtyIn(row.bins(), Locations.normalize(location.ifBlank { row.location })).toString(), qty, true,
        ),
        LabelBarcode.F_DIST_PN to FieldStatus.change(row.distributorPn, distributorPn, false),
        LabelBarcode.F_CUST_PN to FieldStatus.change(row.customerPn, customerPn, false),
    )
    val ordered = STATUS_FIELDS.map { status.getValue(it) }
    val filled = STATUS_FIELDS.count { current[it].orEmpty().isNotBlank() }
    val summary = FieldStatus.summary(ordered, filled)
    val firstCheck = FieldStatus.firstToCheck(ordered)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check & save") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Rescan")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text(if (part.labelType.contains("+")) part.labelType + " labels" else part.labelType + " label") },
                )
                if (part.refDesCount > 0) AssistChip(onClick = {}, label = { Text("${part.refDesCount} ref des") })
            }

            if (thumb != null) {
                // Which line each field's value came from, kept up to date as fields change.
                val sources = remember(photoBoxes, mpn, distributorPn, customerPn, description, qty) {
                    PhotoBoxes.sources(
                        photoBoxes,
                        mapOf(
                            FieldReader.Field.MPN to mpn, FieldReader.Field.DISTRIBUTOR_PN to distributorPn,
                            FieldReader.Field.CUSTOMER_PN to customerPn, FieldReader.Field.DESCRIPTION to description,
                            FieldReader.Field.QUANTITY to qty,
                        ),
                    )
                }
                ReviewPhotoPanel(
                    photo = thumb,
                    boxes = photoBoxes,
                    sources = sources,
                    highlight = focusedField,
                    onUseLine = { i, f -> photoBoxes.getOrNull(i)?.let { applyRescan(f, listOf(it), fromPhoto = true) } },
                    onZoom = { showPhoto = true },
                )
            }
            rescanMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            if (summary.isNotBlank()) {
                StatusSummary(summary, toCheck = firstCheck >= 0) {
                    if (firstCheck >= 0) focus.getValue(STATUS_FIELDS[firstCheck]).requestFocus()
                }
            }

            seenBag?.let { DuplicateBagCard(it) }
            StockBanner(existing, hasKey, qty.trim().toIntOrNull(), addMode, location)

            Field("Manufacturer part number", mpn, { mpn = it }, mono = true,
                capitalization = KeyboardCapitalization.Characters,
                onFocus = { focusChanged(FieldReader.Field.MPN, it) },
                status = status.getValue(LabelBarcode.F_MPN), change = changes[LabelBarcode.F_MPN],
                focus = focus.getValue(LabelBarcode.F_MPN),
                onRescan = { rescan = FieldReader.Field.MPN })
            Field("Description", description, { description = it }, singleLine = false,
                onFocus = { focusChanged(FieldReader.Field.DESCRIPTION, it) },
                status = status.getValue(F_DESCRIPTION), change = changes[F_DESCRIPTION],
                focus = focus.getValue(F_DESCRIPTION),
                onRescan = { rescan = FieldReader.Field.DESCRIPTION })
            val specs = remember(description, apiSpecs) { apiSpecs.orElse(PartSpecs.fromDescription(description)) }
            if (!specs.isEmpty || datasheetUrl.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpecChips(specs.chips(), Modifier.weight(1f), pkg = specs.pkg)
                    if (datasheetUrl.isNotBlank()) {
                        TextButton(onClick = { openLink(context, datasheetUrl) }) { Text("Datasheet") }
                    }
                }
            }
            CategoryPicker(category) {
                category = it
                categoryPicked = true
            }
            Field("Manufacturer", manufacturer, { manufacturer = it },
                status = status.getValue(F_MANUFACTURER), change = changes[F_MANUFACTURER],
                focus = focus.getValue(F_MANUFACTURER))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(
                    value = qty,
                    onValueChange = { v -> qty = v.filter { it.isDigit() } },
                    label = "Quantity",
                    supportingText = statusText(status.getValue(LabelBarcode.F_QTY), changes[LabelBarcode.F_QTY]),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusIcon(status.getValue(LabelBarcode.F_QTY))
                            RescanIcon("quantity") { rescan = FieldReader.Field.QUANTITY }
                        }
                    },
                    modifier = Modifier.weight(1f).focusRequester(focus.getValue(LabelBarcode.F_QTY))
                        .onFocusChanged { focusChanged(FieldReader.Field.QUANTITY, it.isFocused) },
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Bin / location") },
                    singleLine = true,
                    keyboardOptions = codeKeys(),
                    modifier = Modifier.weight(1f).bringIntoViewOnFocus(),
                )
            }

            KitHint(part) { qty = it.toString() }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = addMode, onClick = { addMode = true }, label = { Text("Add to stock") })
                FilterChip(selected = !addMode, onClick = { addMode = false }, label = { Text("Set count to this") })
            }

            NumberField(
                value = unitCost,
                onValueChange = { v ->
                    unitCost = v.filter { it.isDigit() || it == '.' }
                    priceSource = ""
                },
                label = "Unit cost (each)",
                decimal = true,
                prefix = { Text("$") },
                supportingText = {
                    val total = unitCost.toDoubleOrNull()?.let { c -> qty.toIntOrNull()?.let { n -> c * n } }
                    Text(if (total != null) "This entry: \$${InventoryDb.fmtMoney(total)}" else "Labels don't print a price")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (looking) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        if (lookingAt.isNotBlank()) "Checking $lookingAt…" else "Checking…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isDigiKeyLabel && digiKey != null) {
                OutlinedButton(onClick = ::lookupDigiKey, enabled = !looking, modifier = Modifier.fillMaxWidth()) {
                    if (looking) CircularProgressIndicator(Modifier.size(18.dp)) else Text("Look up on DigiKey")
                }
            } else if (isDigiKeyLabel && mouserKey == null) {
                Text(
                    "Add DigiKey API credentials in Settings to fill in the price automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (mouserKey != null) {
                OutlinedButton(onClick = { lookupMouser() }, enabled = !looking, modifier = Modifier.fillMaxWidth()) {
                    if (looking) CircularProgressIndicator(Modifier.size(18.dp)) else Text("Look up price on Mouser")
                }
            } else if (!isDigiKeyLabel) {
                Text(
                    "Add a Mouser API key in Settings to fill in the price automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            lookupMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (link != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        link,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { openLink(context, link) }) { Text("Open on ${Links.siteName(link)}") }
                }
            }

            Field(
                (part.distributor ?: "Distributor") + " part number", distributorPn, { distributorPn = it }, mono = true,
                capitalization = KeyboardCapitalization.Characters,
                onFocus = { focusChanged(FieldReader.Field.DISTRIBUTOR_PN, it) },
                status = status.getValue(LabelBarcode.F_DIST_PN), change = changes[LabelBarcode.F_DIST_PN],
                focus = focus.getValue(LabelBarcode.F_DIST_PN),
                onRescan = { rescan = FieldReader.Field.DISTRIBUTOR_PN },
            )
            Field("Customer / Entrust PN", customerPn, { customerPn = it }, mono = true,
                capitalization = KeyboardCapitalization.Characters,
                onFocus = { focusChanged(FieldReader.Field.CUSTOMER_PN, it) },
                status = status.getValue(LabelBarcode.F_CUST_PN), change = changes[LabelBarcode.F_CUST_PN],
                focus = focus.getValue(LabelBarcode.F_CUST_PN),
                onRescan = { rescan = FieldReader.Field.CUSTOMER_PN })
            Field("Notes", notes, { notes = it }, singleLine = false, imeAction = ImeAction.Done)

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(onClick = ::save, enabled = !saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (saving) CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text(if (addMode) "Add to inventory" else "Set inventory count")
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Rescan") }

            TextButton(onClick = { showRaw = !showRaw }) {
                Text(if (showRaw) "Hide text read from label" else "Show text read from label")
            }
            if (showRaw) {
                Text(part.rawText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    rescan?.let { f ->
        FieldScanDialog(
            fieldName = FieldReader.label(f),
            onDismiss = { rescan = null },
            onRead = { lines ->
                rescan = null
                applyRescan(f, lines)
            },
        )
    }
    if (showPhoto && photo != null) PhotoViewer(photo, onDismiss = { showPhoto = false })
}

/** Small camera button in a field: re-read just this field from a close-up photo. */
@Composable
private fun RescanIcon(name: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Outlined.CameraAlt, contentDescription = "Rescan $name", modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DuplicateBagCard(hit: BagHit) {
    val day = try {
        java.time.LocalDateTime.parse(hit.timestamp.replace(' ', 'T'))
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US))
    } catch (_: Exception) {
        hit.timestamp
    }
    val how = if (hit.mode == "set") "counted" else "added"
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp).fillMaxWidth()) {
            Text(
                "This bag was already $how on $day (qty ${hit.quantity})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "Same order details on the label. Switched to \"Set count to this\" so it isn't counted twice; " +
                    "pick \"Add to stock\" if it really is another bag.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun StockBanner(existing: Match?, hasKey: Boolean, qty: Int?, addMode: Boolean, location: String) {
    if (existing != null) {
        val r = existing.row
        // Add goes into this bin (the part's main bin if blank); Set sets this bin's count, other bins stay.
        val bins = r.bins()
        val bin = Locations.normalize(location.ifBlank { r.location })
        val after = when {
            qty == null -> r.quantity
            addMode -> r.quantity + qty
            else -> r.quantity - Locations.qtyIn(bins, bin) + qty
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(12.dp).fillMaxWidth()) {
                Text("Already in stock: ${r.quantity}", style = MaterialTheme.typography.titleSmall)
                val where = Locations.summary(bins).let { if (it.isNotBlank()) " · bin $it" else "" }
                Text("${r.mpn.ifBlank { r.customerPn }} (matched by ${existing.matchedOn})$where",
                    style = MaterialTheme.typography.bodySmall)
                Text("After save: $after", style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else if (hasKey) {
        Text("New part — not in inventory yet", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Entrust kit labels print a per-board quantity and a kit count. */
@Composable
private fun KitHint(part: ParsedPart, onUse: (Int) -> Unit) {
    val perBoard = part.kitPerBoard ?: return
    val kits = part.kitQuantity
    val needs = if (kits != null && kits > 0) perBoard * kits else null
    Column {
        if (part.distributorPn == null) {
            // Kit label only: its Qty is per board, so offer the whole-kit total.
            if (kits != null && kits > 1 && needs != null) {
                Row {
                    Text(
                        "Label says $perBoard per board × $kits kits = $needs. Count the bag if unsure.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(top = 12.dp),
                    )
                    TextButton(onClick = { onUse(needs) }) { Text("Use $needs") }
                }
            }
            if (part.refDesCount > 0 && part.refDesCount != perBoard) {
                Text(
                    "Ref des list has ${part.refDesCount} entries but Qty reads $perBoard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else if (needs != null) {
            // Distributor label present: its quantity is the bag count, the kit label says how many are used.
            Text(
                "Kit label: $perBoard per board × $kits boards = $needs used. " +
                    "Quantity above is what's in this bag (${part.quantity ?: "?"}).",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private const val F_DESCRIPTION = "description"
private const val F_MANUFACTURER = "manufacturer"

/** Fields that get a status line, in screen order (the summary jumps to the first one to check). */
private val STATUS_FIELDS = listOf(
    LabelBarcode.F_MPN, F_DESCRIPTION, F_MANUFACTURER, LabelBarcode.F_QTY, LabelBarcode.F_DIST_PN,
    LabelBarcode.F_CUST_PN,
)

@Composable
private fun statusColor(kind: FieldStatus.Kind): Color = when (kind) {
    FieldStatus.Kind.CONFIRMED, FieldStatus.Kind.LOOKED_UP -> ChartColors.positive()
    FieldStatus.Kind.CHECK -> ChartColors.warning()
    FieldStatus.Kind.EDITED -> MaterialTheme.colorScheme.primary
    FieldStatus.Kind.PLAIN -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun statusIcon(kind: FieldStatus.Kind): ImageVector? = when (kind) {
    FieldStatus.Kind.CONFIRMED -> Icons.Outlined.CheckCircle
    FieldStatus.Kind.LOOKED_UP -> Icons.Outlined.CloudDone
    FieldStatus.Kind.CHECK -> Icons.Outlined.ErrorOutline
    FieldStatus.Kind.EDITED -> Icons.Outlined.Edit
    FieldStatus.Kind.PLAIN -> null
}

/** Small icon in the field's trailing area; the supporting line says the same in words, so it isn't read twice. */
@Composable
private fun StatusIcon(status: FieldStatus) {
    val icon = statusIcon(status.kind) ?: return
    Icon(icon, contentDescription = null, tint = statusColor(status.kind), modifier = Modifier.size(18.dp))
}

/** Supporting line: the status in words, then "was 40" (struck through) or "saved part keeps …". */
@Composable
private fun statusText(status: FieldStatus, change: FieldStatus.Change?): (@Composable () -> Unit)? {
    if (status.text == null && change == null) return null
    val color = statusColor(status.kind)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return {
        Text(buildAnnotatedString {
            status.text?.let { withStyle(SpanStyle(color = color)) { append(it) } }
            if (change != null) {
                if (status.text != null) append(" · ")
                withStyle(SpanStyle(color = muted)) {
                    if (change.replaces) {
                        append("was ")
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(change.text().removePrefix("was "))
                        }
                    } else {
                        append(change.text())
                    }
                }
            }
        })
    }
}

/** "7 fields · 5 confirmed · 2 to check"; tapping jumps to the first field to check. */
@Composable
private fun StatusSummary(text: String, toCheck: Boolean, onClick: () -> Unit) {
    val kind = if (toCheck) FieldStatus.Kind.CHECK else FieldStatus.Kind.CONFIRMED
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (toCheck) Modifier.clickable(role = Role.Button, onClickLabel = "Go to the first field to check", onClick = onClick) else Modifier),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            statusIcon(kind)?.let { Icon(it, contentDescription = null, tint = statusColor(kind), modifier = Modifier.size(20.dp)) }
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (toCheck) {
                Text("Show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = true,
    mono: Boolean = false,
    status: FieldStatus = FieldStatus.NONE,
    change: FieldStatus.Change? = null,
    focus: FocusRequester? = null,
    onRescan: (() -> Unit)? = null,
    /** Told when the field gains or loses focus (to highlight its line on the photo). */
    onFocus: ((Boolean) -> Unit)? = null,
    /** All-caps hint only for codes (part numbers, bins); prose fields use sentence case. */
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    /** Next moves to the following field; the last field uses Done (hides the keyboard, never saves). */
    imeAction: ImeAction = ImeAction.Next,
) {
    val icon = statusIcon(status.kind)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        textStyle = if (mono) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
        else MaterialTheme.typography.bodyLarge,
        supportingText = statusText(status, change),
        trailingIcon = if (onRescan != null || icon != null) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusIcon(status)
                    if (onRescan != null) RescanIcon(label.lowercase(), onRescan)
                    else Spacer(Modifier.size(12.dp))
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            autoCorrectEnabled = capitalization != KeyboardCapitalization.Characters,
            imeAction = imeAction,
        ),
        modifier = Modifier.fillMaxWidth()
            .bringIntoViewOnFocus()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .then(if (onFocus != null) Modifier.onFocusChanged { onFocus(it.isFocused) } else Modifier),
    )
}
