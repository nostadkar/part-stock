package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class PhotoBoxesTest {

    private static OcrLine line(String t, int l, int top, int r, int b) {
        return new OcrLine(t, l, top, r, b);
    }

    /** A DigiKey label: the DigiKey PN contains the MPN, so the MPN must come from its own line. */
    private static final List<OcrLine> DIGIKEY = Arrays.asList(
            line("DIGI-KEY PART NUMBER: 2SK3019-TPCT-ND", 10, 10, 500, 40),
            line("MANUFACTURER PART NUMBER: 2SK3019-TP", 10, 50, 480, 80),
            line("DESCRIPTION: MOSFET N-CH 50V 100MA", 10, 90, 500, 120),
            line("SOT-523", 10, 125, 120, 150),
            line("CUSTOMER REFERENCE: 811755-101", 10, 160, 450, 190),
            line("QUANTITY: 1,000", 10, 200, 200, 230),
            line("DATE CODE 2234", 10, 240, 200, 270));

    private static Map<FieldReader.Field, String> values(String mpn, String dist, String cust, String desc, String qty) {
        Map<FieldReader.Field, String> v = new EnumMap<>(FieldReader.Field.class);
        v.put(FieldReader.Field.MPN, mpn);
        v.put(FieldReader.Field.DISTRIBUTOR_PN, dist);
        v.put(FieldReader.Field.CUSTOMER_PN, cust);
        v.put(FieldReader.Field.DESCRIPTION, desc);
        v.put(FieldReader.Field.QUANTITY, qty);
        return v;
    }

    @Test
    public void digiKeyFieldsMapToTheirLines() {
        Map<FieldReader.Field, List<Integer>> s = PhotoBoxes.sources(DIGIKEY,
                values("2SK3019-TP", "2SK3019-TPCT-ND", "811755-101", "MOSFET N-CH 50V 100MA SOT-523", "1000"));
        assertEquals(Collections.singletonList(0), s.get(FieldReader.Field.DISTRIBUTOR_PN));
        assertEquals(Collections.singletonList(1), s.get(FieldReader.Field.MPN));
        assertEquals(Arrays.asList(2, 3), s.get(FieldReader.Field.DESCRIPTION)); // wrapped onto two lines
        assertEquals(Collections.singletonList(4), s.get(FieldReader.Field.CUSTOMER_PN));
        assertEquals(Collections.singletonList(5), s.get(FieldReader.Field.QUANTITY)); // "1,000" read as 1000
    }

    @Test
    public void derivedMouserMpnHasNoBox() {
        // The MPN was worked out from the Mouser PN; it isn't printed on a line of its own.
        List<OcrLine> lines = Arrays.asList(line("P/N: 798-DF11-12DP-2DS24", 0, 0, 400, 30), line("QTY: 5", 0, 40, 100, 70));
        Map<FieldReader.Field, List<Integer>> s = PhotoBoxes.sources(lines,
                values("DF11-12DP-2DS24", "798-DF11-12DP-2DS24", null, null, "5"));
        assertEquals(Collections.singletonList(0), s.get(FieldReader.Field.DISTRIBUTOR_PN));
        assertNull(s.get(FieldReader.Field.MPN));
        assertEquals(Collections.singletonList(1), s.get(FieldReader.Field.QUANTITY));
    }

    @Test
    public void bareNumberOnlyCountsWhenAlone() {
        List<OcrLine> lines = Arrays.asList(line("REV 5 LOT 77", 0, 0, 200, 30), line("5", 0, 40, 20, 70));
        Map<FieldReader.Field, List<Integer>> s = PhotoBoxes.sources(lines, values(null, null, null, null, "5"));
        assertEquals(Collections.singletonList(1), s.get(FieldReader.Field.QUANTITY));
        assertNull(PhotoBoxes.sources(Collections.singletonList(lines.get(0)), values(null, null, null, null, "5"))
                .get(FieldReader.Field.QUANTITY));
    }

    @Test
    public void typedValueNotOnLabelHasNoBox() {
        assertTrue(PhotoBoxes.sources(DIGIKEY, values("XYZ123", null, null, null, null)).isEmpty());
        assertTrue(PhotoBoxes.sources(null, values("XYZ123", null, null, null, null)).isEmpty());
    }

    @Test
    public void normalizeAndFit() {
        List<OcrLine> n = PhotoBoxes.normalize(Collections.singletonList(line("A", 100, 50, 300, 150)), 1000, 500);
        assertEquals(1000, n.get(0).left);
        assertEquals(1000, n.get(0).top);
        assertEquals(3000, n.get(0).right);
        assertEquals(3000, n.get(0).bottom);
        assertTrue(PhotoBoxes.normalize(n, 0, 0).isEmpty());

        // A 2000×1000 photo in a 400×400 area: 400×200, centred vertically.
        double[] r = PhotoBoxes.fit(2000, 1000, 400, 400);
        assertEquals(0, r[0], 1e-9);
        assertEquals(100, r[1], 1e-9);
        assertEquals(400, r[2], 1e-9);
        assertEquals(200, r[3], 1e-9);
        // A portrait photo in a wide area: centred horizontally.
        r = PhotoBoxes.fit(1000, 2000, 400, 100);
        assertEquals(175, r[0], 1e-9);
        assertEquals(50, r[2], 1e-9);
    }

    @Test
    public void orientationCheck() {
        assertTrue(PhotoBoxes.sameOrientation(4000, 3000, 2000, 1500));
        assertTrue(PhotoBoxes.sameOrientation(4000, 3000, 2000, 1501));
        assertFalse(PhotoBoxes.sameOrientation(4000, 3000, 1500, 2000)); // came out sideways
        assertFalse(PhotoBoxes.sameOrientation(0, 0, 1500, 2000));
    }

    @Test
    public void hitPicksContainingThenNearest() {
        List<OcrLine> boxes = Arrays.asList(
                line("big", 0, 0, 1000, 1000),
                line("small", 100, 100, 200, 200),
                line("far", 5000, 5000, 6000, 5200));
        assertEquals(1, PhotoBoxes.hit(boxes, 150, 150, 50)); // inside both: the smaller one
        assertEquals(0, PhotoBoxes.hit(boxes, 500, 500, 50));
        assertEquals(2, PhotoBoxes.hit(boxes, 5500, 5230, 50)); // just below "far"
        assertEquals(-1, PhotoBoxes.hit(boxes, 3000, 3000, 50));
        assertEquals(-1, PhotoBoxes.hit(null, 0, 0, 50));
    }

    @Test
    public void tappedLineReadsLikeFieldRescan() {
        // Using a tapped line goes through FieldReader, the same as the per-field rescan.
        assertEquals("2SK3019-TP", FieldReader.read(FieldReader.Field.MPN, Collections.singletonList(DIGIKEY.get(1))));
        assertEquals("1000", FieldReader.read(FieldReader.Field.QUANTITY, Collections.singletonList(DIGIKEY.get(5))));
        assertEquals("Qty", PhotoBoxes.tag(FieldReader.Field.QUANTITY));
    }
}
