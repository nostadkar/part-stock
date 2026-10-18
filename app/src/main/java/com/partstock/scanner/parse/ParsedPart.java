package com.partstock.scanner.parse;

/** Fields pulled off a parts label. Any field may be null when the label didn't show it. */
public final class ParsedPart {
    public static final String DIGIKEY = "DigiKey";
    public static final String MOUSER = "Mouser";
    public static final String ENTRUST = "Entrust kit";
    public static final String MYDATA = "MyData";
    public static final String UNKNOWN = "Unknown";

    public String labelType = UNKNOWN;
    /** Distributor whose part number is in {@link #distributorPn} (DigiKey or Mouser). */
    public String distributor;
    public String mpn;
    /** True when the MPN was worked out from a distributor part number rather than printed. */
    public boolean mpnDerived;
    public String distributorPn;
    /** Customer / internal part number (Entrust PN, DigiKey "Customer Reference", Mouser "Cust P/N"). */
    public String customerPn;
    public String description;
    public String manufacturer;
    /** Pieces in this bag as printed (Entrust: per-board qty from the REF DES row). */
    public Integer quantity;
    /** Entrust only: number of kits/boards in the header ("Qty 15"). */
    public Integer kitQuantity;
    /** Entrust only: pieces of this part per board (the table's Qty cell). */
    public Integer kitPerBoard;
    /** Entrust only: the kit header without its Qty ("260699 Entrust SH 536966-001 Rev 1"). */
    public String kitName;
    /** Entrust only: the line's item number in the kit. */
    public Integer kitItem;
    public String dateCode;
    public String refDes;
    public int refDesCount;
    public String notes;
    /** Purchase order printed on a distributor label (Mouser "Cust PO", DigiKey "Purchase Order"). */
    public String purchaseOrder;
    /** Mouser only: order line item ("Line Item: 028"). */
    public String lineItem;
    public String rawText = "";
    /** Fields taken from a label barcode because OCR missed them or read them differently (see LabelBarcode). */
    public final java.util.Set<String> fromBarcode = new java.util.LinkedHashSet<>();

    /** Key the sheet matches on: MPN, else customer PN, else distributor PN. */
    public String inventoryKey() {
        if (notBlank(mpn)) return mpn;
        if (notBlank(customerPn)) return customerPn;
        return notBlank(distributorPn) ? distributorPn : null;
    }

    /**
     * Identifies one physical bag, so scanning it twice can be caught: Mouser PO + line item, or DigiKey part number +
     * PO + quantity (+ date code when printed). Null when the label doesn't show enough to tell bags apart.
     */
    public String bagFingerprint() {
        if (MOUSER.equals(distributor) && notBlank(purchaseOrder) && notBlank(lineItem)) {
            return "MOUSER|" + squash(purchaseOrder) + "|" + squash(lineItem);
        }
        if (DIGIKEY.equals(distributor) && notBlank(distributorPn) && notBlank(purchaseOrder) && quantity != null) {
            return "DIGIKEY|" + squash(distributorPn) + "|" + squash(purchaseOrder) + "|" + quantity
                    + (notBlank(dateCode) ? "|" + squash(dateCode) : "");
        }
        return null;
    }

    private static String squash(String s) {
        return s.toUpperCase(java.util.Locale.US).replaceAll("\\s+", "");
    }

    static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }

    @Override
    public String toString() {
        return "ParsedPart{type=" + labelType + ", dist=" + distributor + ", mpn=" + mpn
                + (mpnDerived ? "(derived)" : "") + ", distPn=" + distributorPn
                + ", custPn=" + customerPn + ", desc=" + description + ", mfr=" + manufacturer
                + ", qty=" + quantity + ", kitQty=" + kitQuantity + ", dateCode=" + dateCode
                + ", refDes=" + refDesCount + ", notes=" + notes + "}";
    }
}
