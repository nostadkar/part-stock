package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class LocationsTest {

    private static LinkedHashMap<String, Integer> m(Object... kv) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], (Integer) kv[i + 1]);
        return out;
    }

    @Test
    public void blankBinIsUnassigned() {
        assertEquals("Unassigned", Locations.normalize(""));
        assertEquals("Unassigned", Locations.normalize("  "));
        assertEquals("A3", Locations.normalize(" A3 "));
        assertEquals("", Locations.plain("Unassigned"));
        assertEquals("A3", Locations.plain("A3"));
    }

    @Test
    public void addAndSetMatchBinIgnoringCase() {
        Map<String, Integer> s = Locations.add(m("A3", 200), "a3", 50);
        assertEquals(m("A3", 250), s);
        s = Locations.add(s, "R2", 1000);
        assertEquals(m("A3", 250, "R2", 1000), s);
        assertEquals(1250, Locations.total(s));
        assertEquals(m("A3", 10, "R2", 1000), Locations.set(s, "A3", 10));
    }

    @Test
    public void takeStartsWithPreferredBin() {
        Map<String, Integer> s = m("A3", 200, "R2", 1000);
        assertEquals(m("A3", 200, "R2", 990), Locations.take(s, "R2", 10));
        assertEquals(m("A3", 0, "R2", 950), Locations.take(s, "A3", 250));
        assertEquals(m("A3", 0, "R2", 0), Locations.take(s, "A3", 5000));
        assertEquals(m("A3", 150, "R2", 1000), Locations.take(s, "nowhere", 50));
    }

    @Test
    public void moveNeverTakesMoreThanTheBinHolds() {
        Map<String, Integer> s = m("A3", 200, "R2", 1000);
        assertEquals(m("A3", 150, "R2", 1050), Locations.move(s, "A3", "R2", 50));
        assertEquals(m("A3", 0, "R2", 1000, "B1", 200), Locations.move(s, "A3", "B1", 999));
        assertEquals(s, Locations.move(s, "A3", "a3", 10));
        assertEquals(s, Locations.move(s, "Z9", "A3", 10));
    }

    @Test
    public void renameMergesIntoExistingBin() {
        assertEquals(m("Drawer 1", 200, "R2", 1000), Locations.rename(m("A3", 200, "R2", 1000), "A3", "Drawer 1"));
        assertEquals(m("R2", 1200), Locations.rename(m("A3", 200, "R2", 1000), "A3", "r2"));
        assertEquals(m("R2", 1200), Locations.rename(m("R2", 1000, "A3", 200), "a3", "R2"));
        assertEquals(m("R2", 5), Locations.rename(m("R2", 5), "A3", "B1"));
    }

    @Test
    public void compactDropsEmptyBinsButKeepsOne() {
        assertEquals(m("R2", 1000), Locations.compact(m("A3", 0, "R2", 1000), "A3"));
        assertEquals(m("A3", 0), Locations.compact(m("A3", 0, "R2", 0), "A3"));
        assertEquals(m("Unassigned", 0), Locations.compact(m(), ""));
        assertEquals(m("A3", 3), Locations.compact(m("A3", 1, "a3", 2), ""));
    }

    @Test
    public void csvColumnRoundTrips() {
        Map<String, Integer> s = m("A3", 200, "R2", 1000);
        assertEquals("A3: 200; R2: 1000", Locations.format(s));
        assertEquals(s, Locations.parse("A3: 200; R2: 1000"));
        assertEquals(s, Locations.parse("A3:200;R2:1,000;"));
        assertEquals(m("Shelf 2: left", 4), Locations.parse("Shelf 2: left: 4"));
        assertEquals(m("Unassigned", 7), Locations.parse(": 7"));
        assertTrue(Locations.parse("A3").isEmpty());
        assertTrue(Locations.parse("").isEmpty());
        assertEquals(m("A3", 3), Locations.parse("A3: 1; a3: 2"));
    }

    @Test
    public void rowSummary() {
        assertEquals("A3", Locations.summary(m("A3", 200)));
        assertEquals("", Locations.summary(m("Unassigned", 200)));
        assertEquals("A3: 200 · R2: 1000", Locations.summary(m("A3", 200, "R2", 1000)));
    }
}
