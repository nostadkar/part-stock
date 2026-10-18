package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class InventoryCsvTest {

    @Test
    public void exportRoundTripsEveryColumn() {
        List<String> a = Arrays.asList(
                "Resistors", "RC0603FR-0710KL", "RES 10K OHM 1% 1/10W 0603, \"thick film\"", "YAGEO", "4990",
                "0.0012", "5.988", "A3", "Mouser", "603-RC0603FR-0710KL", "811755-205",
                "https://www.mouser.com/ProductDetail/603-RC0603FR-0710KL", "Mouser label; Entrust kit",
                "line one\r\nline two", "2324", "R1, R2, R7", "3", "2026-10-17 14:05:09");
        List<String> b = Arrays.asList(
                "Transistors", "2SK3019-TP", "MOSFET N-CH 30V 100MA SOT523", "MICRO COMMERCIAL CO", "500",
                "", "", "", "DigiKey", "2SK3019-TPMSCT-ND", "811755-101", "", "DigiKey label", "", "2327", "",
                "1", "2026-10-16 09:00:00");
        String csv = InventoryCsv.write(InventoryCsv.HEADER, Arrays.asList(a, b));

        InventoryCsv.Result res = InventoryCsv.read(csv);
        assertEquals(0, res.skipped);
        assertEquals(2, res.rows.size());
        InventoryCsv.Row r = res.rows.get(0);
        assertEquals("Resistors", r.category);
        assertEquals("RC0603FR-0710KL", r.mpn);
        assertEquals("RES 10K OHM 1% 1/10W 0603, \"thick film\"", r.description);
        assertEquals("YAGEO", r.manufacturer);
        assertEquals(4990, r.quantity);
        assertEquals(0.0012, r.unitCost, 1e-9);
        assertEquals("A3", r.location);
        assertEquals("Mouser", r.distributor);
        assertEquals("603-RC0603FR-0710KL", r.distributorPn);
        assertEquals("811755-205", r.customerPn);
        assertEquals("https://www.mouser.com/ProductDetail/603-RC0603FR-0710KL", r.link);
        assertEquals("Mouser label; Entrust kit", r.source);
        assertEquals("line one\r\nline two", r.notes);
        assertEquals("2324", r.dateCode);
        assertEquals("R1, R2, R7", r.refDes);
        assertEquals(Integer.valueOf(3), r.scans);
        assertEquals("2026-10-17 14:05:09", r.updatedAt);

        InventoryCsv.Row r2 = res.rows.get(1);
        assertNull(r2.unitCost);
        assertEquals(500, r2.quantity);
        assertEquals("", r2.location);
        assertEquals("2SK3019-TPMSCT-ND", r2.distributorPn);
    }

    @Test
    public void quotedCommasQuotesAndCrlf() {
        List<List<String>> recs = InventoryCsv.parse("a,\"b,c\",\"say \"\"hi\"\"\"\r\n1,,\"x\ny\"\r\n\r\n");
        assertEquals(2, recs.size());
        assertEquals(Arrays.asList("a", "b,c", "say \"hi\""), recs.get(0));
        assertEquals(Arrays.asList("1", "", "x\ny"), recs.get(1));
    }

    @Test
    public void lfOnlyNoTrailingNewlineAndBom() {
        List<List<String>> recs = InventoryCsv.parse("﻿MPN,Quantity\nABC,5");
        assertEquals(2, recs.size());
        assertEquals("MPN", recs.get(0).get(0));
        assertEquals(Arrays.asList("ABC", "5"), recs.get(1));
    }

    @Test
    public void olderExportWithoutCategoryCostOrLink() {
        // Columns from the first builds of the app, before Category / Unit Cost / Link existed.
        String csv = "MPN,Description,Manufacturer,Quantity,Location,Distributor,Distributor PN,Customer PN,"
                + "Source,Notes,Date Code,Ref Des,Scans,Last Updated\r\n"
                + "DF11-12DP-2DS24,\"CONN HEADER, 12POS\",Hirose,29,B1,Mouser,798-DF11-12DP-2DS24,,"
                + "Mouser label,,,,2,2026-09-01 10:00:00\r\n";
        InventoryCsv.Row r = InventoryCsv.read(csv).rows.get(0);
        assertEquals("DF11-12DP-2DS24", r.mpn);
        assertEquals("CONN HEADER, 12POS", r.description);
        assertEquals(29, r.quantity);
        assertEquals("", r.category);
        assertEquals("", r.link);
        assertNull(r.unitCost);
        assertEquals("798-DF11-12DP-2DS24", r.distributorPn);
        assertEquals(Integer.valueOf(2), r.scans);
    }

    @Test
    public void columnsInAnyOrderAndShortRows() {
        String csv = "Qty,Customer PN,Unit Cost\n\"1,000\",811755-101,$0.25\n7,811755-102\n";
        InventoryCsv.Result res = InventoryCsv.read(csv);
        assertEquals(2, res.rows.size());
        assertEquals(1000, res.rows.get(0).quantity);
        assertEquals("811755-101", res.rows.get(0).customerPn);
        assertEquals(0.25, res.rows.get(0).unitCost, 1e-9);
        assertEquals(7, res.rows.get(1).quantity);
        assertNull(res.rows.get(1).unitCost);
        assertNull(res.rows.get(1).scans);
    }

    @Test
    public void rowsWithoutAnyPartNumberAreSkipped() {
        String csv = "MPN,Customer PN,Distributor PN,Quantity\nABC,,,1\n,,,5\n";
        InventoryCsv.Result res = InventoryCsv.read(csv);
        assertEquals(1, res.rows.size());
        assertEquals(1, res.skipped);
    }

    @Test
    public void rejectsFilesThatAreNotInventory() {
        try {
            InventoryCsv.read("Timestamp,Raw Text\n2026,hello\n");
            fail("expected an error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("not an inventory"));
        }
        try {
            InventoryCsv.read("");
            fail("expected an error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("empty"));
        }
    }

    @Test
    public void badNumbersFallBack() {
        String csv = "MPN,Quantity,Unit Cost,Scans\nA,lots,n/a,x\nB,-4,-1,\nC,29.0,1e-3,4\n";
        List<InventoryCsv.Row> rows = new ArrayList<>(InventoryCsv.read(csv).rows);
        assertEquals(0, rows.get(0).quantity);
        assertNull(rows.get(0).unitCost);
        assertEquals(Integer.valueOf(1), rows.get(0).scans);
        assertEquals(0, rows.get(1).quantity);
        assertNull(rows.get(1).unitCost);
        assertEquals(29, rows.get(2).quantity);
        assertEquals(0.001, rows.get(2).unitCost, 1e-12);
    }
}
