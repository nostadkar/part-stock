package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ReorderListTest {

    private static ReorderList.Item item(String dist, String dpn, String mpn, int onHand, Integer min, Double cost) {
        ReorderList.Item it = new ReorderList.Item();
        it.distributor = dist;
        it.distributorPn = dpn;
        it.mpn = mpn;
        it.manufacturer = "Maker";
        it.description = "Desc, with comma";
        it.onHand = onHand;
        it.minimum = min;
        it.unitCost = cost;
        it.link = dist.equals("Mouser") ? "https://www.mouser.com/ProductDetail/" + dpn : "";
        return it;
    }

    @Test
    public void lowStockIsAtOrBelowMinimum() {
        assertTrue(ReorderList.isLow(5, 10));
        assertTrue(ReorderList.isLow(10, 10));
        assertFalse(ReorderList.isLow(11, 10));
        assertFalse(ReorderList.isLow(0, null));
        assertFalse(ReorderList.isLow(0, 0));
    }

    @Test
    public void orderQuantityRoundsUp() {
        assertEquals(0, ReorderList.orderQuantity(10, 10));
        assertEquals(0, ReorderList.orderQuantity(3, null));
        assertEquals(7, ReorderList.orderQuantity(3, 10));
        assertEquals(40, ReorderList.orderQuantity(63, 100));
        assertEquals(5, ReorderList.orderQuantity(-5, 5)); // negative stock counts as 0
        assertEquals(380, ReorderList.roundUp(373));
        assertEquals(900, ReorderList.roundUp(873));
        assertEquals(4000, ReorderList.roundUp(3950));
        assertEquals(10000, ReorderList.roundUp(9600));
    }

    @Test
    public void buildsMouserBomColumns() {
        List<ReorderList.Item> items = Arrays.asList(
                item("Mouser", "603-RC0603FR-0710KL", "RC0603FR-0710KL", 120, 500, 0.0012),
                item("DigiKey", "2SK3019-TPMSCT-ND", "2SK3019-TP", 2, 10, null),
                item("Mouser", "798-DF11-12DP-2DS24", "DF11-12DP-2DS24", 50, 20, 0.5), // not low
                item("", "", "811755-301", 0, null, null)); // no minimum
        String csv = ReorderList.csv(items);
        List<List<String>> recs = InventoryCsv.parse(csv);
        assertEquals(3, recs.size());
        assertEquals("Mouser Part Number", recs.get(0).get(0));
        assertEquals("Quantity", recs.get(0).get(1));

        List<String> a = recs.get(1);
        assertEquals("603-RC0603FR-0710KL", a.get(0));
        assertEquals("380", a.get(1));
        assertEquals("", a.get(2));
        assertEquals("RC0603FR-0710KL", a.get(3));
        assertEquals("Desc, with comma", a.get(5));
        assertEquals("120", a.get(6));
        assertEquals("500", a.get(7));
        assertEquals("0.0012", a.get(8));
        assertEquals("https://www.mouser.com/ProductDetail/603-RC0603FR-0710KL", a.get(9));

        List<String> b = recs.get(2);
        assertEquals("", b.get(0));
        assertEquals("8", b.get(1));
        assertEquals("2SK3019-TPMSCT-ND", b.get(2));
        assertEquals("", b.get(8));
    }

    @Test
    public void emptyListHasHeaderOnly() {
        assertEquals(1, InventoryCsv.parse(ReorderList.csv(Arrays.<ReorderList.Item>asList())).size());
    }
}
