package com.partstock.scanner.parse;

/** Fields pulled off a parts label. Any field may be null when the label didn't show it. */
public final class ParsedPart {
    public static final String DIGIKEY = "DigiKey";
    public static final String MOUSER = "Mouser";
    public static final String ENTRUST = "Entrust kit";
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
    public String dateCode;
    public String refDes;
    public int refDesCount;
    public String notes;
    public String rawText = "";

    /** Key the sheet matches on: MPN, else customer PN, else distributor PN. */
    public String inventoryKey() {
        if (notBlank(mpn)) return mpn;
        if (notBlank(customerPn)) return customerPn;
        return notBlank(distributorPn) ? distributorPn : null;
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
