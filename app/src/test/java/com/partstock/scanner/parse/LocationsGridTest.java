package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

/** Locations grid view (#59): bin name → row and column, ragged rows, gaps and the bins left out. */
public class LocationsGridTest {

    @Test
    public void cellFromDrawerNames() {
        assertEquals(new Locations.Cell("A", 3), Locations.cell("A3"));
        assertEquals(new Locations.Cell("A", 3), Locations.cell(" a3 "));
        assertEquals(new Locations.Cell("R", 2), Locations.cell("R2"));
        assertEquals(new Locations.Cell("AB", 12), Locations.cell("AB12"));
        assertEquals(new Locations.Cell("A", 3), Locations.cell("A03"));
        assertEquals(new Locations.Cell("A", 3), Locations.cell("A-3"));
        assertEquals(new Locations.Cell("B", 0), Locations.cell("B0"));
    }

    @Test
    public void otherNamesHaveNoCell() {
        assertNull(Locations.cell("A3-top"));
        assertNull(Locations.cell("Unassigned"));
        assertNull(Locations.cell("unassigned"));
        assertNull(Locations.cell("Shelf"));
        assertNull(Locations.cell("3A"));
        assertNull(Locations.cell("ABC1"));
        assertNull(Locations.cell("A100"));
        assertNull(Locations.cell(""));
        assertNull(Locations.cell(null));
    }

    @Test
    public void raggedRowsWithGaps() {
        Locations.Grid g = Locations.grid(Arrays.asList("B2", "A1", "A3", "b1", "R2"));
        assertTrue(g.useful);
        assertEquals(Arrays.asList("A", "B", "R"), g.rows);
        assertEquals(1, g.firstColumn);
        assertEquals(3, g.lastColumn);
        assertEquals(Arrays.asList("A1", null, "A3"), g.cells.get(0));
        assertEquals(Arrays.asList("b1", "B2", null), g.cells.get(1));
        assertEquals(Arrays.asList(null, "R2", null), g.cells.get(2));
        assertEquals("A3", g.at("A", 3));
        assertNull(g.at("A", 2));
        assertNull(g.at("C", 1));
        assertTrue(g.others.isEmpty());
    }

    @Test
    public void columnsStartAtOneSoMissingDrawersShow() {
        Locations.Grid g = Locations.grid(Arrays.asList("C4", "C5"));
        assertEquals(1, g.firstColumn);
        assertEquals(5, g.columns());
        assertEquals(Arrays.asList(null, null, null, "C4", "C5"), g.cells.get(0));
    }

    @Test
    public void suffixedDuplicateAndUnassignedGoToOthers() {
        Locations.Grid g = Locations.grid(Arrays.asList("Unassigned", "A3-top", "A3", "A03", "Desk", "A1"));
        assertEquals(Arrays.asList("A03", "A3-top", "Desk", "Unassigned"), g.others);
        assertEquals(Arrays.asList("A1", null, "A3"), g.cells.get(0));
        assertFalse(g.useful); // only 2 of the 5 named bins fit
    }

    @Test
    public void gridOnlyWhenMostBinsFit() {
        assertFalse(Locations.grid(Arrays.asList("Desk", "Shelf", "A1")).useful);
        assertFalse(Locations.grid(Arrays.asList("Desk", "A1")).useful); // half is not most
        assertTrue(Locations.grid(Arrays.asList("Desk", "A1", "A2")).useful);
        assertTrue(Locations.grid(Arrays.asList("A1", "Unassigned")).useful); // Unassigned doesn't count
        assertFalse(Locations.grid(Arrays.asList("Unassigned")).useful);
        assertFalse(Locations.grid(Arrays.<String>asList()).useful);
    }

    @Test
    public void rowsSortSingleLettersFirst() {
        Locations.Grid g = Locations.grid(Arrays.asList("AA1", "Z1", "B1", "A1"));
        assertEquals(Arrays.asList("A", "B", "Z", "AA"), g.rows);
    }

    @Test
    public void cellDescription() {
        assertEquals("A3, 12 parts, 340 pieces, 2 low", BinStats.cellDescription("A3", 12, 340, 2));
        assertEquals("A3, 1 part, 1 piece", BinStats.cellDescription("A3", 1, 1, 0));
        assertEquals("B2, empty", BinStats.cellDescription("B2", 0, 0, 0));
    }

    @Test
    public void shadeParses() {
        assertEquals(BinStats.Shade.PARTS, BinStats.Shade.parse("PARTS"));
        assertEquals(BinStats.Shade.PIECES, BinStats.Shade.parse("bogus"));
        assertEquals(BinStats.Shade.PIECES, BinStats.Shade.parse(null));
    }
}
