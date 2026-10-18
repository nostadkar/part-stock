package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** A burst of photos of one label: each frame has a different OCR slip, the merge should fix them all. */
public class ScanMergerTest {

    /** The DigiKey fixture with one line's text swapped, the way a blurry frame misreads it. */
    private static List<OcrLine> digiKeyWith(String from, String to) {
        List<OcrLine> out = new ArrayList<>();
        for (OcrLine l : LabelParserTest.digiKey()) {
            out.add(l.text.equals(from) ? new OcrLine(to, l.left, l.top, l.right, l.bottom) : l);
        }
        return out;
    }

    private static List<OcrLine> digiKeyWithout(String text) {
        List<OcrLine> out = new ArrayList<>();
        for (OcrLine l : LabelParserTest.digiKey()) if (!l.text.equals(text)) out.add(l);
        return out;
    }

    @Test
    public void eachFieldTakesTheValueMostFramesAgreeOn() {
        ParsedPart a = LabelParser.parse(digiKeyWith("2SK3019-TP", "2SK30I9-TP"));
        ParsedPart b = LabelParser.parse(digiKeyWith("500", "5OO"));
        ParsedPart c = LabelParser.parse(digiKeyWith("811755-101", "811765-101"));
        ParsedPart m = ScanMerger.merge(Arrays.asList(a, b, c));
        assertEquals(ParsedPart.DIGIKEY, m.labelType);
        assertEquals("2SK3019-TP", m.mpn);
        assertEquals("2SK3019-TPMSCT-ND", m.distributorPn);
        assertEquals("811755-101", m.customerPn);
        assertEquals(Integer.valueOf(500), m.quantity);
        assertEquals("MOSFET N-CH 30V 100MA SOT523", m.description);
        assertEquals("2327", m.dateCode);
    }

    @Test
    public void fieldMissedInOneFrameIsFilledFromAnother() {
        ParsedPart a = LabelParser.parse(digiKeyWithout("500"));
        ParsedPart b = LabelParser.parse(digiKeyWithout("MICRO COMMERCIAL CO"));
        ParsedPart m = ScanMerger.merge(Arrays.asList(a, b));
        assertEquals(Integer.valueOf(500), m.quantity);
        assertEquals("MICRO COMMERCIAL CO", m.manufacturer);
        assertEquals("2SK3019-TP", m.mpn);
    }

    @Test
    public void framesWithNoTextAreIgnored() {
        ParsedPart empty = LabelParser.parse(Collections.emptyList());
        ParsedPart good = LabelParser.parse(LabelParserTest.digiKey());
        ParsedPart m = ScanMerger.merge(Arrays.asList(empty, good, empty));
        assertEquals("2SK3019-TP", m.mpn);
        assertEquals(Integer.valueOf(500), m.quantity);
    }

    @Test
    public void allEmptyStaysEmpty() {
        ParsedPart empty = LabelParser.parse(Collections.emptyList());
        ParsedPart m = ScanMerger.merge(Arrays.asList(empty, empty));
        assertEquals("", m.rawText);
        assertNull(m.mpn);
    }

    @Test
    public void singleFrameIsUnchanged() {
        ParsedPart one = LabelParser.parse(LabelParserTest.mouser());
        assertEquals(one, ScanMerger.merge(Collections.singletonList(one)));
    }

    @Test
    public void multiLabelFramesWinOverPartialOnes() {
        ParsedPart full = LabelParser.parse(LabelParserTest.multiLabelBag());
        ParsedPart full2 = LabelParser.parse(LabelParserTest.multiLabelBag());
        ParsedPart partial = LabelParser.parse(LabelParserTest.mouser());
        ParsedPart m = ScanMerger.merge(Arrays.asList(partial, full, full2));
        assertEquals(full.labelType, m.labelType);
        assertEquals(full.kitPerBoard, m.kitPerBoard);
        assertEquals(full.customerPn, m.customerPn);
    }
}
