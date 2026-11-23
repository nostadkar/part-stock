package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ChartDataTest {
    @Test
    public void rangeIncludesExtraAndWidensFlat() {
        double[] r = ChartData.range(new double[] {5, 12, 8}, null);
        assertEquals(5, r[0], 0);
        assertEquals(12, r[1], 0);
        r = ChartData.range(new double[] {5, 12}, 2.0);
        assertEquals(2, r[0], 0);
        r = ChartData.range(new double[] {10, 10}, null);
        assertTrue(r[0] < 10 && r[1] > 10);
        r = ChartData.range(new double[0], null);
        assertEquals(0, r[0], 0);
        assertEquals(1, r[1], 0);
    }

    @Test
    public void scaleFitsTheBox() {
        float[] p = ChartData.scale(new double[] {0, 5, 10}, 0, 10, 100, 50, 5);
        assertEquals(5, p[0], 1e-4);   // first x at the left pad
        assertEquals(45, p[1], 1e-4);  // lowest value at the bottom
        assertEquals(50, p[2], 1e-4);
        assertEquals(25, p[3], 1e-4);
        assertEquals(95, p[4], 1e-4);  // last x at the right pad
        assertEquals(5, p[5], 1e-4);   // highest at the top
        float[] one = ChartData.scale(new double[] {3}, 0, 6, 100, 50, 0);
        assertEquals(50, one[0], 1e-4);
        assertEquals(45, ChartData.yOf(0, 0, 10, 50, 5), 1e-4);
        assertEquals(5, ChartData.yOf(99, 0, 10, 50, 5), 1e-4); // clamped
    }

    @Test
    public void topNWithOther() {
        List<String> labels = Arrays.asList("A", "B", "C", "D", "E");
        List<ChartData.Slice> s = ChartData.topN(labels, new double[] {10, 40, 0, 30, 20}, 2, "Other");
        assertEquals("[B=40.0, D=30.0, Other=30.0]", s.toString());
        assertEquals(0.4, s.get(0).fraction, 1e-9);
        assertEquals(1, s.get(0).index);
        assertEquals(-1, s.get(2).index);
        // Only one left over: shown itself, no "Other".
        s = ChartData.topN(labels, new double[] {10, 40, 0, 30, 0}, 2, "Other");
        assertEquals("[B=40.0, D=30.0, A=10.0]", s.toString());
        assertTrue(ChartData.topN(labels, new double[] {0, 0, 0, 0, 0}, 2, "Other").isEmpty());
        // Ties keep the input order.
        s = ChartData.topN(Arrays.asList("X", "Y"), new double[] {5, 5}, 6, "Other");
        assertEquals("[X=5.0, Y=5.0]", s.toString());
    }

    @Test
    public void sliceAtClockwiseFromTop() {
        double[] f = {0.5, 0.25, 0.25};
        assertEquals(0, ChartData.sliceAt(f, 10, -50, 20, 60));  // just right of 12 o'clock
        assertEquals(0, ChartData.sliceAt(f, 50, 0, 20, 60));    // 3 o'clock
        assertEquals(1, ChartData.sliceAt(f, -10, 50, 20, 60));  // just left of 6 o'clock
        assertEquals(2, ChartData.sliceAt(f, -50, -10, 20, 60)); // ~10 o'clock
        assertEquals(-1, ChartData.sliceAt(f, 5, 5, 20, 60));    // in the hole
        assertEquals(-1, ChartData.sliceAt(f, 100, 0, 20, 60));  // outside
    }

    @Test
    public void summaries() {
        assertEquals("Stock over the last 90 days, from 40 to 12",
                ChartData.describeSeries("Stock over the last 90 days", new double[] {40, 30, 12}));
        assertEquals("Stock, from 40 to 12, lowest 3",
                ChartData.describeSeries("Stock", new double[] {40, 3, 12}));
        List<ChartData.Slice> s = ChartData.topN(Arrays.asList("Resistors", "Capacitors", "LEDs"),
                new double[] {52, 47.9, 0.1}, 6, "Other");
        assertEquals("Pieces by category: Resistors 52%, Capacitors 48%, LEDs <1%",
                ChartData.describeSlices("Pieces by category", s));
        assertEquals("1.5", ChartData.num(1.5));
        assertEquals("12", ChartData.num(12.0));
    }
}
