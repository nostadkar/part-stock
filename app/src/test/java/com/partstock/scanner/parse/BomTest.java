package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** BOM CSV reading (KiCad and generic) and the buildable-count / shortage maths. */
public class BomTest {

    /** KiCad 8 "Export BOM" with the default fields. */
    static final String KICAD =
            "\"Reference\",\"Value\",\"Datasheet\",\"Footprint\",\"Qty\",\"DNP\"\n"
                    + "\"C1,C2,C3\",\"100nF\",\"~\",\"Capacitor_SMD:C_0603_1608Metric\",\"3\",\"\"\n"
                    + "\"R1,R2\",\"10k\",\"~\",\"Resistor_SMD:R_0603_1608Metric\",\"2\",\"\"\n"
                    + "\"R3\",\"4k7\",\"~\",\"Resistor_SMD:R_0603_1608Metric\",\"1\",\"DNP\"\n"
                    + "\"Q1\",\"2SK3019\",\"~\",\"Package_TO_SOT_SMD:SOT-523\",\"1\",\"\"\n";

    /** The older "bom_csv_grouped_by_value" plugin: preamble lines, then the table. */
    static final String KICAD_LEGACY =
            "\"Source:\",\"/home/me/board.sch\"\n\"Date:\",\"Sun 18 Oct 2026\"\n\"Tool:\",\"Eeschema 6.0\"\n"
                    + "\"Generator:\",\"bom_csv_grouped_by_value.py\"\n\"Component Count:\",\"5\"\n\n"
                    + "\"Ref\",\"Qnty\",\"Value\",\"Cmp name\",\"Footprint\",\"Description\",\"Vendor\"\n"
                    + "\"C1 C2 C3 \",\"3\",\"100nF\",\"C\",\"Capacitor_SMD:C_0603_1608Metric\",\"Unpolarized capacitor\",\"\"\n"
                    + "\"U1 \",\"1\",\"LT8609\",\"LT8609\",\"Package_SO:MSOP-16\",\"Buck regulator\",\"\"\n";

    static final String GENERIC =
            "Item;MPN;Manufacturer;Qty per board;Ref Des;Customer PN\n"
                    + "1;2SK3019-TP;Micro Commercial Co;29;Q1-Q29;811755-101\n"
                    + "2;DF11-12DP-2DS24;Hirose;2;J1, J2;809056-412\n"
                    + "3;LT8609AIMSE16#PBF;Analog Devices;1;U1;\n";

    @Test
    public void kicadExport() {
        BomCsv.Bom b = BomCsv.parse(KICAD);
        assertNull(b.error);
        assertEquals(3, b.lines.size());
        assertEquals(1, b.dnp);
        BomCsv.Line c = b.lines.get(0);
        assertEquals("100nF", c.value);
        assertEquals("C1 C2 C3", c.refDes);
        assertEquals(3, c.perBoard);
        assertEquals("C_0603_1608Metric", c.shortFootprint());
        assertEquals("100nF C_0603_1608Metric", c.label());
        assertEquals("2SK3019", b.lines.get(2).value);
    }

    @Test
    public void kicadLegacyWithPreamble() {
        BomCsv.Bom b = BomCsv.parse(KICAD_LEGACY);
        assertNull(b.error);
        assertEquals(2, b.lines.size());
        assertEquals("C1 C2 C3", b.lines.get(0).refDes);
        assertEquals(3, b.lines.get(0).perBoard);
        assertEquals("Buck regulator", b.lines.get(1).description);
    }

    @Test
    public void genericSemicolonCsvWithRanges() {
        BomCsv.Bom b = BomCsv.parse(GENERIC);
        assertNull(b.error);
        assertEquals(3, b.lines.size());
        BomCsv.Line q = b.lines.get(0);
        assertEquals("2SK3019-TP", q.mpn);
        assertEquals("811755-101", q.customerPn);
        assertEquals(29, q.perBoard);
        assertTrue(q.refDes.startsWith("Q1 Q2 Q3"));
        assertTrue(q.refDes.endsWith("Q29"));
        assertEquals("J1 J2", b.lines.get(1).refDes);
        assertEquals("Analog Devices", b.lines.get(2).manufacturer);
    }

    @Test
    public void quantityFromRefDesWhenThereIsNoQtyColumn() {
        BomCsv.Bom b = BomCsv.parse("Designator,Comment\n\"R1, R2, R5\",10k\nC1,1uF\n");
        assertEquals(3, b.lines.get(0).perBoard);
        assertEquals(1, b.lines.get(1).perBoard);
    }

    @Test
    public void refDesForms() {
        assertEquals(Arrays.asList("R1", "R2", "R3", "R4"), BomCsv.refDes("R1-R4"));
        assertEquals(Arrays.asList("R1", "R2", "R3"), BomCsv.refDes("R1 - 3"));
        assertEquals(Arrays.asList("C1", "C2", "C7"), BomCsv.refDes("C1,C2, C7 C2"));
        assertEquals(Arrays.asList("U1-A"), BomCsv.refDes("U1-A"));
    }

    @Test
    public void notABom() {
        assertNotNull(BomCsv.parse("Name,Phone\nBob,1\n").error);
        assertNotNull(BomCsv.parse("").error);
    }

    // ---- build maths ----

    private static BuildPlan.Stock stock(long id, String mpn, String cpn, int onHand) {
        BuildPlan.Stock s = new BuildPlan.Stock();
        s.id = id;
        s.mpn = mpn;
        s.customerPn = cpn;
        s.onHand = onHand;
        s.distributor = "Mouser";
        s.distributorPn = "000-" + mpn;
        return s;
    }

    private static List<BuildPlan.Req> genericReqs() {
        List<BuildPlan.Req> reqs = new ArrayList<>();
        for (BomCsv.Line l : BomCsv.parse(GENERIC).lines) reqs.add(BuildPlan.Req.of(l));
        return reqs;
    }

    private static List<BuildPlan.Stock> genericStock() {
        return Arrays.asList(
                stock(1, "2SK3019-TP", "811755-101", 125), // 29 per board → 4 boards
                stock(2, "", "809056-412", 20), // matched on customer PN, 2 per board → 10
                stock(3, "LT8609AIMSE16#PBF", "", 6)); // 1 per board → 6
    }

    @Test
    public void howManyBoards() {
        BuildPlan.Result r = BuildPlan.plan(genericReqs(), genericStock(), 1);
        assertEquals(4, r.buildable);
        assertEquals(Arrays.asList("2SK3019-TP"), r.limiting);
        assertEquals("MPN", r.lines.get(0).matchedOn);
        assertEquals("customer PN", r.lines.get(1).matchedOn);
        assertTrue(r.shortages.isEmpty());
        assertEquals(Integer.valueOf(29), r.use.get(1L));
    }

    @Test
    public void shortagesForNBoards() {
        BuildPlan.Result r = BuildPlan.plan(genericReqs(), genericStock(), 8);
        assertEquals(4, r.buildable);
        assertEquals(2, r.shortages.size());
        BuildPlan.Shortage q = r.shortages.get(0);
        assertEquals("2SK3019-TP", q.label);
        assertEquals(232, q.need);
        assertEquals(107, q.missing);
        assertEquals(110, q.orderQty); // rounded up like the reorder list
        BuildPlan.Shortage u = r.shortages.get(1);
        assertEquals(2, u.missing);
        assertEquals(2, u.orderQty);
        String csv = r.reorderCsv();
        assertTrue(csv.startsWith("Mouser Part Number,Quantity,"));
        assertTrue(csv.contains("000-2SK3019-TP,110,,2SK3019-TP"));
        assertTrue(csv.contains("000-LT8609AIMSE16#PBF,2,"));
    }

    @Test
    public void unmatchedLineMeansNoBoardsAndIsFullyShort() {
        List<BuildPlan.Stock> st = new ArrayList<>(genericStock());
        st.remove(2);
        BuildPlan.Result r = BuildPlan.plan(genericReqs(), st, 3);
        assertEquals(0, r.buildable);
        assertEquals(1, r.unmatched);
        assertTrue(r.limiting.contains("LT8609AIMSE16#PBF (no match)"));
        BuildPlan.Shortage s = r.shortages.get(r.shortages.size() - 1);
        assertNull(s.part);
        assertEquals(3, s.missing);
        assertTrue(r.reorderCsv().contains("LT8609AIMSE16#PBF"));
        assertNull(r.use.get(3L));
    }

    @Test
    public void pickedMatchWinsAndSharedPartsAddUp() {
        // KiCad BOM has only values: pick the stock parts by hand. Both capacitor lines use part 7.
        List<BuildPlan.Req> reqs = new ArrayList<>();
        for (BomCsv.Line l : BomCsv.parse(KICAD).lines) reqs.add(BuildPlan.Req.of(l));
        BuildPlan.Req extra = BuildPlan.Req.of(BomCsv.parse(KICAD).lines.get(0));
        reqs.add(extra);
        List<BuildPlan.Stock> st = Arrays.asList(stock(7, "GRM188R71H104KA93D", "", 30),
                stock(8, "RC0603FR-0710KL", "", 100), stock(9, "2SK3019-TP", "", 50));
        reqs.get(0).pickedId = 7L;
        extra.pickedId = 7L;
        reqs.get(1).pickedId = 8L;
        reqs.get(2).pickedId = 9L;
        BuildPlan.Result r = BuildPlan.plan(reqs, st, 1);
        assertEquals("picked", r.lines.get(0).matchedOn);
        assertEquals(5, r.buildable); // 30 caps / (3 + 3) per board
        assertEquals(Arrays.asList("GRM188R71H104KA93D"), r.limiting);
        assertEquals(Integer.valueOf(6), r.use.get(7L));
    }

    @Test
    public void emptyBomBuildsNothing() {
        BuildPlan.Result r = BuildPlan.plan(new ArrayList<>(), genericStock(), 2);
        assertEquals(0, r.buildable);
        assertTrue(r.shortages.isEmpty());
    }

    @Test
    public void searchHintUsesValueAndPackage() {
        BomCsv.Line l = BomCsv.parse(KICAD).lines.get(1);
        assertTrue(l.searchHint(), l.searchHint().startsWith("10k"));
        assertEquals("2SK3019-TP", BomCsv.parse(GENERIC).lines.get(0).searchHint());
    }
}
