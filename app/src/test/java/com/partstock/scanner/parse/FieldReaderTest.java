package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class FieldReaderTest {

    private static OcrLine line(String t, int l, int top, int r, int b) {
        return new OcrLine(t, l, top, r, b);
    }

    @Test
    public void mouserPnSplitAcrossWordsIsJoined() {
        List<OcrLine> lines = Arrays.asList(line("P/N: 584-8609AIMSE", 10, 10, 300, 40), line("16#PBF", 310, 12, 400, 40));
        assertEquals("584-8609AIMSE16#PBF", FieldReader.read(FieldReader.Field.DISTRIBUTOR_PN, lines));
    }

    @Test
    public void mpnWithoutLabel() {
        List<OcrLine> lines = Arrays.asList(line("LT8609AIMSE#PBF", 10, 10, 300, 40));
        assertEquals("LT8609AIMSE#PBF", FieldReader.read(FieldReader.Field.MPN, lines));
        // A part number that starts like a field name stays whole.
        assertEquals("PN2222A", FieldReader.read(FieldReader.Field.MPN, Arrays.asList(line("PN2222A", 0, 0, 100, 20))));
        assertEquals("2SK3019-TP",
                FieldReader.read(FieldReader.Field.MPN, Arrays.asList(line("Manufacturer Part Number 2SK3019-TP", 0, 0, 400, 20))));
    }

    @Test
    public void customerPnDashRestored() {
        assertEquals("811755-101", FieldReader.read(FieldReader.Field.CUSTOMER_PN,
                Arrays.asList(line("Cust P/N: 811755 101", 0, 0, 300, 30))));
        assertEquals("811755-101", FieldReader.read(FieldReader.Field.CUSTOMER_PN,
                Arrays.asList(line("PN", 0, 0, 40, 30), line("811755-101", 60, 2, 300, 30))));
    }

    @Test
    public void quantityFixesLetterSlips() {
        assertEquals("100", FieldReader.read(FieldReader.Field.QUANTITY, Arrays.asList(line("QTY: 1O0", 0, 0, 200, 30))));
        assertEquals("2500", FieldReader.read(FieldReader.Field.QUANTITY, Arrays.asList(line("Quantity 2,500", 0, 0, 200, 30))));
        assertEquals("29", FieldReader.read(FieldReader.Field.QUANTITY, Arrays.asList(line("29", 0, 0, 50, 30))));
        assertNull(FieldReader.read(FieldReader.Field.QUANTITY, Arrays.asList(line("QTY", 0, 0, 50, 30))));
    }

    @Test
    public void descriptionOverTwoLines() {
        List<OcrLine> lines = Arrays.asList(
                line("CONN HEADER R/A 12POS", 0, 50, 300, 80),
                line("Desc: 2MM", 0, 10, 200, 40));
        assertEquals("2MM CONN HEADER R/A 12POS", FieldReader.read(FieldReader.Field.DESCRIPTION, lines));
    }

    @Test
    public void inBoxKeepsLinesCentredInside() {
        List<OcrLine> lines = Arrays.asList(
                line("QTY: 10", 100, 100, 300, 140),
                line("COO: CN", 100, 300, 300, 340));
        List<OcrLine> in = FieldReader.inBox(lines, 0, 50, 1000, 200);
        assertEquals(1, in.size());
        assertEquals("10", FieldReader.read(FieldReader.Field.QUANTITY, in));
        assertNull(FieldReader.read(FieldReader.Field.MPN, FieldReader.inBox(lines, 0, 500, 10, 600)));
    }
}
