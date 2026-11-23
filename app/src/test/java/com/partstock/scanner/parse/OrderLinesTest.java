package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class OrderLinesTest {

    @Test
    public void statusPrefersImportedBefore() {
        assertEquals(OrderLines.Status.NEW, OrderLines.status(false, false));
        assertEquals(OrderLines.Status.RESTOCK, OrderLines.status(true, false));
        assertEquals(OrderLines.Status.IMPORTED, OrderLines.status(true, true));
        assertEquals(OrderLines.Status.IMPORTED, OrderLines.status(false, true));
        List<OrderLines.Status> s = Arrays.asList(OrderLines.Status.NEW, OrderLines.Status.RESTOCK, OrderLines.Status.NEW);
        assertEquals(2, OrderLines.count(s, OrderLines.Status.NEW));
        assertEquals(0, OrderLines.count(s, OrderLines.Status.IMPORTED));
    }

    @Test
    public void totalsFromTheMouserExport() {
        OrderCsv.Order o = OrderCsv.parse(OrderCsvTest.MOUSER_HISTORY, "order.csv");
        assertEquals(2, o.lines.size());
        assertEquals(10 * 0.966 + 1000 * 4.12, OrderLines.total(o.lines, null), 1e-6);
        assertEquals(9.66, OrderLines.total(o.lines, new boolean[] {true, false}), 1e-6);
        assertEquals(0.0, OrderLines.total(o.lines, new boolean[] {false, false}), 1e-9);
        assertEquals("$4,129.66", OrderLines.money(OrderLines.total(o.lines, null)));
        assertEquals("1 line", OrderLines.lines(1));
        assertEquals("28 lines", OrderLines.lines(28));
    }
}
