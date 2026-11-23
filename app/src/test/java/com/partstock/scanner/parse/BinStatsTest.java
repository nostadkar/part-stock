package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class BinStatsTest {

    private static final class B implements BinStats.Bin {
        final String name;
        final int parts, pieces;

        B(String name, int parts, int pieces) {
            this.name = name;
            this.parts = parts;
            this.pieces = pieces;
        }

        public String name() { return name; }

        public int parts() { return parts; }

        public int pieces() { return pieces; }
    }

    private static List<String> names(List<B> bins) {
        List<String> out = new ArrayList<>();
        for (B b : bins) out.add(b.name);
        return out;
    }

    private static final List<B> BINS = Arrays.asList(
        new B("Unassigned", 9, 900), new B("A10", 2, 50), new B("A2", 5, 20), new B("b1", 1, 0), new B("A3", 5, 300));

    @Test
    public void fillIsRelativeToFullest() {
        assertEquals(900, BinStats.maxPieces(BINS));
        assertEquals(1f, BinStats.fill(900, 900), 1e-6);
        assertEquals(0.5f, BinStats.fill(150, 300), 1e-6);
        assertEquals(0f, BinStats.fill(0, 300), 1e-6);
        assertEquals(0f, BinStats.fill(5, 0), 1e-6);
        assertEquals(0, BinStats.maxPieces(new ArrayList<B>()));
    }

    @Test
    public void sortsKeepUnassignedLast() {
        assertEquals(Arrays.asList("A2", "A3", "A10", "b1", "Unassigned"), names(BinStats.sort(BINS, BinStats.Sort.NAME)));
        assertEquals(Arrays.asList("A2", "A3", "A10", "b1", "Unassigned"), names(BinStats.sort(BINS, BinStats.Sort.MOST_PARTS)));
        assertEquals(Arrays.asList("A3", "A10", "A2", "b1", "Unassigned"), names(BinStats.sort(BINS, BinStats.Sort.MOST_PIECES)));
        assertEquals(Arrays.asList("b1", "A2", "A10", "A3", "Unassigned"), names(BinStats.sort(BINS, BinStats.Sort.EMPTY_FIRST)));
    }

    @Test
    public void namesCompareNumbersNaturally() {
        assertTrue(BinStats.compareNames("A2", "A10") < 0);
        assertTrue(BinStats.compareNames("A02", "A10") < 0);
        assertTrue(BinStats.compareNames("a1", "B1") < 0);
        assertTrue(BinStats.compareNames("Shelf 3 box 2", "Shelf 3 box 12") < 0);
        assertTrue(BinStats.compareNames("A", "A1") < 0);
        assertEquals(0, BinStats.compareNames("A1", "A1"));
    }

    @Test
    public void topCategoriesMostFirstTiesInOrder() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("Resistors", 2);
        m.put("Capacitors", 5);
        m.put("Diodes", 2);
        m.put("LEDs", 0);
        m.put("Power", 1);
        assertEquals(Arrays.asList("Capacitors", "Resistors", "Diodes"), BinStats.topCategories(m, 3));
        assertEquals(Arrays.asList("Capacitors"), BinStats.topCategories(m, 1));
        assertEquals(4, BinStats.topCategories(m, 10).size());
        assertTrue(BinStats.topCategories(new LinkedHashMap<String, Integer>(), 3).isEmpty());
    }

    @Test
    public void searchIgnoresCaseAndSpacing() {
        assertTrue(BinStats.matches("Drawer A-3", "a3"));
        assertTrue(BinStats.matches("Drawer A-3", "drawer"));
        assertTrue(BinStats.matches("A3", ""));
        assertTrue(BinStats.matches("A3", null));
        assertFalse(BinStats.matches("A3", "b"));
    }

    @Test
    public void moreLabel() {
        assertEquals("+3", BinStats.more(7, 4));
        assertEquals("", BinStats.more(4, 4));
        assertEquals("", BinStats.more(0, 4));
    }
}
