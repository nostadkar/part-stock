# Part Stock

Android app that photographs a parts-bag label, reads it with on-device OCR (ML Kit, works offline),
pulls out the part fields, and keeps an inventory on the phone. Export it as CSV any time.

Label types it understands:

| Label | Fields read |
|---|---|
| **DigiKey** | Part Number (‑ND), Manufacturer Part Number, Part Description, Manufacturer, Quantity, Customer Reference, Date Code, Purchase Order |
| **Mouser** | P/N, Cust P/N, Desc, manufacturer + category line, QTY, COO, Cust PO. The MPN is worked out by dropping Mouser's numeric prefix (`798-DF11-12DP-2DS24` → `DF11-12DP-2DS24`) and flagged for you to check. |
| **Entrust kit** | PN (customer PN), Alt PN (MPN), Desc, Qty (per board), kit Qty from the header, REF DES list, item number |

Anything else falls back to a best-effort read; you can always correct fields before saving.

Parts are matched on **MPN**, then **Customer PN**, then **Distributor PN**, so the DigiKey bag
(Customer Reference `811755-101`, MPN `2SK3019-TP`) and the Entrust kit bag (PN `811755-101`,
Alt PN `2SK3019-TP`) land on the same row.

## Getting the APK

Every push to `main` runs the GitHub Actions workflow in `.github/workflows/build.yml`, which runs the
parser tests, builds a release APK, and publishes it as a GitHub Release (`part-stock.apk`). On the phone:
open the repo → **Releases** → latest → tap `part-stock.apk` → allow installing from your browser.

The APK is signed with a test key kept in the repo (`app/partstock-test.keystore`), so each new build
installs over the previous one and your inventory is kept. Don't reuse that key for anything published.

To build locally instead: open the folder in Android Studio and press Run. Min Android 8.0.

## Using it

- Fit the label in the box and tap the shutter, or pick an existing photo.
  The foil bags glare: tilt the bag a little rather than using the light.
- The review screen shows what it read and whether the part is already in stock
  ("Already in stock: 500 · after save: 529").
- **Add to stock** adds the quantity; **Set count to this** overwrites it (use after a recount).
- **Bin / location** is remembered for the next scan, handy when filling one drawer at a time.
- Entrust kit labels print a per-board quantity (Qty 29) and a kit count (Qty 15). The app pre-fills the
  per-board number and offers the product; count the bag if you're not sure which applies.
- **Inventory** (top right on the scan screen) lists everything with search. Tap a part to change the
  quantity, bin or description, or delete it.
- **Export** (share icon on the Inventory screen): share or save the inventory CSV, or the scan log CSV
  (every save with the raw OCR text). The CSV opens directly in Google Sheets or Excel.
- Scanning both the DigiKey and Entrust label of the **same bag** with "Add" double-counts it. Use "Set" or
  scan one label per bag.

## Project layout

```
.github/workflows/build.yml  CI: tests + APK + GitHub Release
app/src/main/java/com/partstock/scanner/
  parse/LabelParser.java     Label → fields (pure Java, unit tested)
  OcrEngine.kt               ML Kit text recognition
  ScanScreen.kt              CameraX preview, capture, photo picker
  ReviewScreen.kt            Edit fields, stock check, save
  InventoryScreen.kt         List, search, edit, delete, export
  InventoryDb.kt             SQLite storage + CSV
  Export.kt                  Share sheet / save-to-file
app/src/test/.../LabelParserTest.java
```

Adding a new label layout: add a `parseXxx` method in `LabelParser`, a detection check in `parse()`,
and a fixture in `LabelParserTest`.
