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
  lookup also saves the datasheet link (*Datasheet* button in the edit dialog). Search understands values and packages:
  `10k 0603` finds a 10kΩ 0603 resistor (10k = 10K = 10kΩ = 10000 = 4k7-style codes), `0.1uF` = `100nF`, `sot23` =
  `SOT-23`, `1%` matches the tolerance. Existing parts get these filled in from their description when you update.
  The CSV has Value, Tolerance, Voltage, Package and Datasheet columns.
- **Unit cost** is an optional field (labels don't print prices). The Inventory screen totals the value.
- **Settings → Mouser Search API key** turns on "Look up price on Mouser" in the review screen, which fills in the
  price (at your bag quantity), the official product link, and corrects an OCR slip in the part number.

- **Refresh prices** (circular-arrow icon on the Inventory screen) looks up every part with a Mouser part number and
  updates its unit cost, description, link and category, then shows e.g. "Updated 38, not found 2". It sends one request
  every 2.2 s to stay under Mouser's limit of about 30 a minute (so 100 parts take about 4 minutes; keep the screen
  open) and stops cleanly on a rate-limit or key error, keeping what was already updated. A category you picked by hand
  (in the review screen or the edit dialog) is remembered and never overwritten by a lookup or refresh.
  The edit dialog shows where and when the price came from ("Price from Mouser, Oct 18").
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
- The review screen shows what it read and whether the part is already in stock
  ("Already in stock: 500 · after save: 529").
- **Add to stock** adds the quantity; **Set count to this** overwrites it (use after a recount).
- **Bin / location** is remembered for the next scan, handy when filling one drawer at a time.
- Entrust kit labels print a per-board quantity (Qty 29) and a kit count (Qty 15). The app pre-fills the
  per-board number and offers the product; count the bag if you're not sure which applies.
- **Inventory** (top right on the scan screen) lists everything with search. Tap a part to change the
  quantity, bin or description, or delete it.
- **Using parts**: in a part's edit dialog, set *Use* with − / + and Save; the count drops and the scan log gets a
  negative "use" entry. **Build kit** (wrench icon on the Inventory screen): pick a kit, enter the number of boards,
  check the list (parts that would go below 0 are flagged), then *Use parts* subtracts per board × boards from every
  part. Kits are learned when you save a bag with its Entrust kit label (kit, per-board Qty and REF DES are stored).
  Counts never go below 0; you're asked to confirm first.
- **Low stock**: set an optional *Minimum* in a part's edit dialog. Parts at or below it show their count in red,
  the *Low stock* chip lists them and the summary line counts them. Export → *Reorder list CSV* lists the parts below
  their minimum with the quantity to order (minimum − on hand, rounded up: exact below 10, then to 5s, 10s, 50s, 100s),
  MPN, last unit price and link. Its first two columns, *Mouser Part Number* and *Quantity*, are what Mouser's BOM tool
  asks for; DigiKey parts have their part number in a separate column.
- **Export** (share icon on the Inventory screen): share or save the inventory CSV, or the scan log CSV
  (every save with the raw OCR text). The CSV opens directly in Google Sheets or Excel.
- **Backup & restore** (Settings): *Restore from CSV* loads an inventory CSV exported by the app (older exports
  without Category / Unit Cost / Link work too). Choose *Merge* (rows are matched like a scan, quantities set from the
  file, other parts kept) or *Replace everything* (the scan log is kept); a summary like "42 parts: 30 new, 12 updated"
  is shown before anything changes. *Auto backup* writes the inventory CSV to a folder you pick after every save and
  keeps the newest 5 files; pick a folder your cloud app syncs to keep a copy off the phone.
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
  ScanScreen.kt              CameraX preview, capture, photo picker
  parse/ScanMerger.java      Votes field by field across a burst of reads
  Updater.kt                 Checks GitHub releases, downloads and installs a newer APK
  ReviewScreen.kt            Edit fields, stock check, save
  InventoryScreen.kt         List, search, edit, delete, export
  InventoryDb.kt             SQLite storage + CSV
  parse/InventoryCsv.java    CSV read/write for export and restore (pure Java, unit tested)
  AutoBackup.kt              Writes the inventory CSV to a picked folder after each save
  Export.kt                  Share sheet / save-to-file
app/src/test/.../LabelParserTest.java
```

Adding a new label layout: add a `parseXxx` method in `LabelParser`, a detection check in `parse()`,
and a fixture in `LabelParserTest`.
