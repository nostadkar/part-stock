# Part Stock

Android app that photographs a parts-bag label, reads it with on-device OCR (ML Kit, works offline),
pulls out the part fields, and keeps an inventory on the phone. Export it as CSV any time.

Label types it understands:

| Label | Fields read |
|---|---|
| **DigiKey** | Part Number (‑ND), Manufacturer Part Number, Part Description, Manufacturer, Quantity, Customer Reference, Date Code, Purchase Order |
| **Mouser** | P/N, Cust P/N, Desc, manufacturer + category line, QTY, COO, Cust PO. The MPN is worked out by dropping Mouser's numeric prefix (`798-DF11-12DP-2DS24` → `DF11-12DP-2DS24`) and flagged for you to check. It can be wrong (Mouser drops some makers' prefixes, e.g. `584-8609AIMSE16#PBF` is really `LT8609AIMSE16#PBF`), so use "Look up price on Mouser" to get the real MPN. |
| **Entrust kit** | PN (customer PN), Alt PN (MPN), Desc, Qty (per board), kit Qty from the header, REF DES list, item number |

| **MyData sticker** | Component (customer PN) and Comment (description) |

Anything else falls back to a best-effort read; you can always correct fields before saving.

**Several labels on one bag** (e.g. a Mouser label with an Entrust kit label and a MyData sticker stuck on it)
are read from one photo and merged into one part. The Mouser/DigiKey label gives the bag quantity; the kit
label's Qty is per board, so it is shown as a hint ("1 per board × 6 boards = 6 used") and never added to the count.

**Batch mode** (chip at the top of the scan screen) is for a pile of bags. Set the bin once (*Bin* chip, or scan a bin
QR label), then just keep scanning: each bag is queued in a tray at the bottom, read, and looked up on DigiKey (DigiKey
labels, when set up) or Mouser in the background, one request at a time and paced under Mouser's limit (a rate limit
waits a minute and carries on). Each card shows the part number, quantity and a status: *Looking up*, *Looked up*
(confident), *Needs check* or *Duplicate bag*. **Save confident** adds every bag that passed all the checks in one go
(one Undo): part number and bag quantity read, the lookup found exactly that part number, and the bag isn't a duplicate
(saved before, or scanned twice in this batch; rechecked at save time). Without a Mouser or DigiKey key nothing counts as
confident. **Review** opens the other bags one by one in the normal review screen; saving one moves on to the next.
Tap any card to review it, or × to drop it. The queue (OCR text and photo) is kept in the app's files, so it survives
leaving the app.

Parts are matched on **MPN**, then **Customer PN**, then **Distributor PN**, so the DigiKey bag
(Customer Reference `811755-101`, MPN `2SK3019-TP`) and the Entrust kit bag (PN `811755-101`,
Alt PN `2SK3019-TP`) land on the same row.

## Getting the APK

Every push to `main` runs the GitHub Actions workflow in `.github/workflows/build.yml`, which runs the
parser tests, builds a release APK, and publishes it as a GitHub Release (`part-stock.apk`). On the phone:
open the repo → **Releases** → latest → tap `part-stock.apk` → allow installing from your browser.

The APK is signed with a test key kept in the repo (`app/partstock-test.keystore`), so each new build
installs over the previous one and your inventory is kept. Don't reuse that key for anything published.

**In-app updates**: from this build on, the app checks the latest release when it starts (at most every 6 hours)
and offers "Update available". Tap Update: it downloads `part-stock.apk` from this repo's releases, checks it is a
newer Part Stock build, and opens Android's installer (the first time, Android asks you to allow installs from
Part Stock). Settings → Check for updates does the same on demand. The build number is the workflow run number.

To build locally instead: open the folder in Android Studio and press Run. Min Android 8.0.

## Links, cost and the Mouser API key

- Every part gets a link: Mouser labels open the Mouser product page, DigiKey labels open a DigiKey search for the
  part number. It is saved with the part and included in the CSV.
- When the Mouser lookup finds the part, **Mouser's description replaces the label's** (also on the saved part).
- **Categories**: every part goes in a bucket (Resistors, Capacitors, Transistors, Power ICs, Connectors…), taken from
  Mouser's category when looked up, otherwise from the description. Change it with the Category button. The Inventory
  screen groups by category with filter chips, and the CSV has a Category column.
- **Value, package and datasheet**: the value (10kΩ, 100nF, 4.7µH), tolerance, voltage and package (0603, SOT-23,
  MSOP-16) are read from the description (or Mouser's attributes) and shown as small chips on each inventory row. A
  lookup also saves the datasheet link. Each inventory row has a datasheet icon (also a *Datasheet* button on the part
  page) that opens it; parts without a saved link get a web search for "<MPN> datasheet" instead. Search understands values and packages:
  `10k 0603` finds a 10kΩ 0603 resistor (10k = 10K = 10kΩ = 10000 = 4k7-style codes), `0.1uF` = `100nF`, `sot23` =
  `SOT-23`, `1%` matches the tolerance. Existing parts get these filled in from their description when you update.
  The CSV has Value, Tolerance, Voltage, Package and Datasheet columns.
- **Unit cost** is an optional field (labels don't print prices). The Inventory screen totals the value.
- **Settings → Mouser Search API key** turns on "Look up price on Mouser" in the review screen, which fills in the
  price (at your bag quantity), the official product link, and corrects an OCR slip in the part number.

- **Automatic lookup**: with a Mouser key saved, the review screen starts the Mouser lookup as soon as it opens
  ("Checking Mouser…" under the unit cost; the form stays usable and you can save before it finishes). It uses the
  Mouser P/N on Mouser labels, otherwise the MPN. It runs once per scan; the button is there for a retry after you edit
  the part number. Turn it off in Settings → *Look up on Mouser after each scan*.
- **Refresh prices** (⋮ menu on the Inventory screen) looks up every part with a Mouser part number and
  updates its unit cost, description, link and category, then shows e.g. "Updated 38, not found 2". It sends one request
  every 2.2 s to stay under Mouser's limit of about 30 a minute (so 100 parts take about 4 minutes; keep the screen
  open) and stops cleanly on a rate-limit or key error, keeping what was already updated. A category you picked by hand
  (in the review screen or on the part page) is remembered and never overwritten by a lookup or refresh.
  The part page shows where and when the price came from ("Price from Mouser, Oct 18").
- **Settings → DigiKey API** (Client ID and Client Secret of an app you create at developer.digikey.com with the
  Product Information API v4) turns on "Look up on DigiKey" for DigiKey labels: price at your bag quantity (from the
  packaging on the label, e.g. Cut Tape), DigiKey's description, manufacturer, MPN, product page and category. It
  tries the DigiKey part number, then the MPN, accepting the nearest result within 2 characters and look-alike swaps,
  like the Mouser lookup. The sign-in token is kept in memory only, until it expires.

**The API keys are never in this repository or in the APK.** You paste it into the app once. It is encrypted
with an Android Keystore key that can't be exported, only the encrypted text is stored, that file is excluded
from backups, and the key never appears in exports or logs. Lookups go directly from the phone to Mouser over HTTPS.
Don't put keys in source files, GitHub secrets baked into the build, or `BuildConfig`: the repo is public and an
APK is easy to unpack. If a key is ever pasted somewhere public, regenerate it in your Mouser account.

## Using it

- Fit the label in the box and tap the shutter, or pick an existing photo. The camera focuses on the box first
  (tap the preview to focus somewhere else), then reads 3 photos in a row and takes, for each field, the value
  most photos agree on, so a single misread character doesn't stick. Hold still for about two seconds.
  The foil bags glare: tilt the bag a little rather than using the light.
- **Barcodes as a cross-check**: the same photos are also scanned for barcodes (ML Kit, offline). DigiKey's DataMatrix
  (ECIA / MH10.8.2: `P` customer PN, `1P` MPN, `30P` DigiKey PN, `Q` quantity, `K` PO, `9D`/`10D` date code, `1T` lot)
  and the DataMatrix on newer Mouser labels are decoded field by field; Mouser's 1D codes (one value each) are matched
  to the OCR field they look like (same apart from I/1/L, O/0, E/F… or one character). Where a barcode and OCR
  disagree, or OCR missed a field, the barcode value is used and the field shows "From barcode". A Mouser DataMatrix
  gives the real MPN, so it isn't "worked out" from the P/N. OCR still reads everything else (Entrust and MyData
  labels have no barcodes). The decoded values are listed under "Show text read from label". Turn it off in
  Settings → *Read label barcodes*.
- The review screen shows what it read and whether the part is already in stock
  ("Already in stock: 500 · after save: 529").
- **Fix one field without rescanning**: the review screen shows the photo that was read (tap to zoom, pinch, double-tap)
  so you can compare. Each of MPN, description, quantity, distributor PN and customer PN has a small camera icon: frame
  just that part of the label in the box and tap the shutter. Only the text inside the box is used, cleaned like a
  full scan (field names dropped, split part numbers joined, O/0 slips in quantities fixed), and only that field changes.
- **Add to stock** adds the quantity; **Set count to this** overwrites it (use after a recount). Both apply to the bin
  on the review screen (the part's main bin if it's blank), so Set on a part kept in two bins only resets that bin.
- **Bin / location** is remembered for the next scan, handy when filling one drawer at a time.
- **Stock in several bins**: a part can be in more than one place (200 in drawer A3, a reel on shelf R2); its quantity
  is the total. Scanning into another bin adds the pieces there. On the part page, *Move* moves N
  pieces from one bin to another (new or existing); a part in several bins shows a count per bin to correct. *Use*
  takes from the main bin first, then the others. **Locations** (box icon on the Inventory screen) lists every bin with
  how many parts and pieces it holds; tap a bin to see its parts, or the pencil to rename it on every part at once
  (renaming onto an existing bin merges them). Parts saved without a bin are under *Unassigned*. Updating to this build
  moved each part's quantity into its bin without changing anything else.
- Entrust kit labels print a per-board quantity (Qty 29) and a kit count (Qty 15). The app pre-fills the
  per-board number and offers the product; count the bag if you're not sure which applies.
- **Inventory** (top right on the scan screen): a collapsing header with stat tiles (parts, pieces, value, low stock:
  tap it to filter), category chips with counts, sticky category headers and the search icon in the top bar (matches
  are highlighted). **Swipe a row right to use 1, left to add 1** (Undo in the message). Tap a part for its full-screen
  page: photos, a big count with − / +, *Datasheet*, *Open on Mouser/DigiKey*, *Use…*, *Move*, *Label*, *History*, and
  sections for stock by bin, price, specs, identifiers and notes. The pencil edits them in place; ⋮ → *Delete part*.
  Transitions follow the system *Remove animations* setting.
- **Using parts**: − on a part's page uses one, *Use…* asks how many; the count drops and the scan log gets a
  negative "use" entry. **Build kit** (⋮ menu on the Inventory screen): pick a kit, enter the number of boards,
  check the list (parts that would go below 0 are flagged), then *Use parts* subtracts per board × boards from every
  part. Kits are learned when you save a bag with its Entrust kit label (kit, per-board Qty and REF DES are stored).
  Counts never go below 0; you're asked to confirm first.
- **Projects / BOMs** (board icon on the Inventory screen): import a BOM CSV — KiCad's *Export BOM* (Reference, Value,
  Footprint, Qty, DNP; the older "grouped by value" plugin output works too) or any CSV with an MPN or value column, a
  quantity per board and reference designators (`,` `;` or tab separated; `R1-R4` ranges are expanded; DNP lines are
  left out) — or make a project from a scanned Entrust kit. Lines are matched to stock by MPN, then customer PN, then
  distributor PN; tap a line marked *No match* to pick the part (the search is pre-filled from its value and
  footprint, e.g. `10k 0603`). The project shows "You can build 4 boards" and the parts that limit it; enter a number
  of boards to see what's short and how many to order (rounded up like the reorder list), and share or save that as
  the reorder CSV. *Build N* subtracts per board × N from every matched part (never below 0, confirm first). Deleting
  a project leaves stock alone.
- **Undo and history**: after every save (scan, edit, use, move, delete, kit or project build, order import) a bar
  at the bottom offers *Undo* for about 10 seconds; it puts the part(s) back exactly as they were (bins and kit
  membership included). A deleted part comes back with Undo too. *History* on a part's page lists every change with
  its date, old → new quantity and what else changed (bin, price, description…); saves from before this feature are
  shown from the scan log. Undo works for changes up to 30 days old (older "before" copies are dropped to keep the
  database small; the history lines stay). Undone scans stay in the scan log marked "undone", so the duplicate-bag
  check ignores them. A CSV restore isn't recorded in the history (use a backup file to go back).
- **QR labels**: *Label* on a part's page, *Make QR labels for the shown parts…* in the Inventory ⋮ menu (filter
  by category or bin first), or the QR icon next to a bin on the Locations screen (a bin label, or labels for all its
  parts). Pick 62 × 29 mm (Brother DK), 2 × 1 in thermal, or a Letter sheet of 30 (Avery 5160), then *Share* the PDF to
  your label printer's app or *Print* with Android printing. Each label has a QR code with a short app-only text
  (`PS:P:<part id>` or `PS:L:<bin>`) plus part number, description and bin. Point the scan screen at a label and tap the
  shutter: a part label opens that part (to use or correct stock), a bin label sets the bin for the next saves. Other QR
  codes are ignored. The PDF is drawn on the phone; nothing is sent anywhere.
- **Photos**: the label photo from a scan is kept as the part's thumbnail (shown on its inventory row) when the part has
  no photo yet; later scans of the same part don't add more. On a part's page, *Take photo* or *Add from gallery*
  adds more (of the bag or the part itself); tap a thumbnail to view it full screen (pinch to zoom) or delete it. Photos
  are resized to 1600 px JPEGs (a few hundred KB each) in the app's private storage. Deleting a part removes its photos
  (Undo brings them back; the files are removed for good after 30 days). Export → *Share / Save inventory + photos
  (zip)* gives the inventory CSV, the photos named by part number, and a `photos.csv` saying which is which.
  **Photos are not in Android's cloud backup** (that backup stops working past 25 MB, which a few hundred photos would
  pass, and it would take the database backup down with it); a phone-to-phone transfer does include them. Use the zip
  export to keep a copy of the photos.
- **Low stock**: set an optional *Minimum* on a part's page (pencil). Parts at or below it show their count in red,
  the *Low stock* chip lists them and the summary line counts them. Export → *Reorder list CSV* lists the parts below
  their minimum with the quantity to order (minimum − on hand, rounded up: exact below 10, then to 5s, 10s, 50s, 100s),
  MPN, last unit price and link. Its first two columns, *Mouser Part Number* and *Quantity*, are what Mouser's BOM tool
  asks for; DigiKey parts have their part number in a separate column.
- **Export** (⋮ menu on the Inventory screen): share or save the inventory CSV, or the scan log CSV
  (every save with the raw OCR text). *Quantity* is the total and *Location* the main bin; the last column,
  *Locations*, lists stock per bin like `A3: 200; R2: 1000` and is read back by *Restore from CSV*. The CSV opens directly in Google Sheets or Excel.
- **Import an order** (⋮ menu on the Inventory screen → *Import Mouser / DigiKey order CSV…*): pick a CSV exported
  from Mouser (order history or cart) or DigiKey (order or cart). Mouser vs DigiKey is told from the part-number column
  (or DigiKey's `-ND` numbers). Columns are found by keywords in the header (*Mouser No/#*, *Mfr. No*, *Manufacturer
  Part/Product Number*, *Digi-Key Part Number*, *Customer No/Reference*, *Quantity / Order Qty. / Qty Shipped*,
  *Unit Price / Price (USD)*), so small wording changes still work; an Excel export needs saving as CSV first. Tick the
  lines and *Add*: each is added to stock and matched to existing parts like a scan, with the file's unit price, a
  link and a category. The order number comes from the file (Sales Order #) or its name and can be edited; each
  order line is remembered, so importing the same order again shows "Already imported" and leaves those lines unticked.
- **Backup & restore** (Settings): *Restore from CSV* loads an inventory CSV exported by the app (older exports
  without Category / Unit Cost / Link work too). Choose *Merge* (rows are matched like a scan, quantities set from the
  file, other parts kept) or *Replace everything* (the scan log is kept); a summary like "42 parts: 30 new, 12 updated"
  is shown before anything changes. *Auto backup* writes the inventory CSV to a folder you pick after every save and
  keeps the newest 5 files; pick a folder your cloud app syncs to keep a copy off the phone.
- **Sync to a file** (Settings → Backup & restore): *New file…* or *Existing file…* opens Android's file picker; choose
  Google Drive there (or any other place) and that one CSV is rewritten with the inventory after every change, in the
  background. Open it in Google Sheets and it always shows current stock. Settings shows "Last synced 2 min ago" or why
  the last write failed; a failed write (offline, lost access) is retried on the next change and at the next app start,
  and *Sync now* retries straight away. It uses the Storage Access Framework with a persisted permission for that one
  file: no Google login, account or API key is in the app or this repo. Drive uploads the file itself (it queues the
  upload while offline). *Turn off* stops syncing and leaves the file where it is.
- **Duplicate-bag guard**: a bag is recognised by Mouser's Cust PO + Line Item, or DigiKey's part number + PO +
  quantity (+ date code). Scanning a bag that was already saved shows "This bag was already added on Oct 17
  (qty 10)" and switches to "Set count to this". Labels without those fields (e.g. a kit label alone) behave as before.
- Scanning both the DigiKey and Entrust label of the **same bag** with "Add" double-counts it (the kit label alone
  can't be recognised). Use "Set" or scan one label per bag.

## Project layout

```
.github/workflows/build.yml  CI: tests + APK + GitHub Release
app/src/main/java/com/partstock/scanner/
  parse/LabelParser.java     Label → fields (pure Java, unit tested)
  OcrEngine.kt               ML Kit text recognition
  BarcodeEngine.kt           ML Kit barcode reading (raw values)
  parse/LabelBarcode.java    MH10.8.2 DataMatrix / 1D barcode decoding and OCR cross-check (pure Java, unit tested)
  ScanScreen.kt              CameraX preview, capture, photo picker
  parse/ScanMerger.java      Votes field by field across a burst of reads
  Updater.kt                 Checks GitHub releases, downloads and installs a newer APK
  ReviewScreen.kt            Edit fields, stock check, save
  InventoryScreen.kt         Inventory screen: top bar, search, menu, list ↔ part page transition
  InventoryList.kt           Stat tiles, category chips, sticky headers, swipeable rows, empty states
  PartDetails.kt             Full-screen part page, edited in place
  Motion.kt                  Shared-element helpers, Remove-animations check, category icons
  InventoryViewModel.kt      Loads parts/photos/bins on IO, reloads on DB changes, debounced search + filters
  PartDialogs.kt             Move between bins, history
  InventoryTools.kt          Refresh prices, build kit dialogs
  LocationsScreen.kt         Bins with part / piece counts, rename everywhere
  Labels.kt                  QR label PDFs (PdfDocument + ZXing encoder), share / print
  UndoOffer.kt               Undo snackbar after saves
  parse/ChangeSummary.java   "What changed" line for the part history (pure Java, unit tested)
  parse/LabelCodes.java      QR label payloads PS:P:<id> / PS:L:<bin> (pure Java, unit tested)
  parse/Locations.java       Stock per bin: add, set, use, move, rename, CSV column (pure Java, unit tested)
  InventoryDb.kt             SQLite storage + CSV
  OrderImport.kt             Import a Mouser / DigiKey order CSV
  ProjectsScreen.kt          Projects / BOMs: buildable count, shortages, Build N
  parse/BomCsv.java          BOM CSV reading, KiCad and generic (pure Java, unit tested)
  parse/BuildPlan.java       BOM matching, buildable count and shortage maths (pure Java, unit tested)
  parse/OrderCsv.java        Order CSV column mapping (pure Java, unit tested)
  parse/InventoryCsv.java    CSV read/write for export and restore (pure Java, unit tested)
  AutoBackup.kt              Writes the inventory CSV to a picked folder after each save
  FileSync.kt                Rewrites one picked file (e.g. on Google Drive) with the inventory CSV after each change
  parse/SyncStatus.java      "Last synced 2 min ago" wording (pure Java, unit tested)
  Export.kt                  Share sheet / save-to-file, inventory + photos zip
  PartPhotos.kt              Part photos: resize/store, thumbnails, camera/gallery pickers, photo strip
app/src/test/.../LabelParserTest.java
```

Adding a new label layout: add a `parseXxx` method in `LabelParser`, a detection check in `parse()`,
and a fixture in `LabelParserTest`.
