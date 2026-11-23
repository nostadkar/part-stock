package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Review-screen field status from the label fixtures: barcode cross-checks, lookups, edits, "was" values. */
public class FieldStatusTest {

    private static List<OcrLine> digiKeyWith(String from, String to) {
        List<OcrLine> out = new ArrayList<>();
        for (OcrLine l : LabelParserTest.digiKey()) {
            out.add(l.text.equals(from) ? new OcrLine(to, l.left, l.top, l.right, l.bottom) : l);
        }
        return out;
    }

    private static FieldStatus scanned(String field, ParsedPart p, String value) {
        return FieldStatus.of(field, p, value, value, null, null);
    }

    @Test
    public void barcodeAgreeingWithTextIsConfirmed() {
        ParsedPart p = LabelParser.parse(LabelParserTest.digiKey());
        LabelBarcode.apply(p, Collections.singletonList(LabelBarcodeTest.digiKeyMatrix()));
        assertTrue(p.barcodeAgrees.contains(LabelBarcode.F_MPN));
        assertTrue(p.barcodeAgrees.contains(LabelBarcode.F_QTY));
        assertTrue(p.ocrBeforeBarcode.isEmpty());
        FieldStatus s = scanned(LabelBarcode.F_MPN, p, p.mpn);
        assertEquals(FieldStatus.Kind.CONFIRMED, s.kind);
        assertEquals("Read + barcode agree", s.text);
        assertEquals(FieldStatus.Kind.CONFIRMED, scanned(LabelBarcode.F_QTY, p, "500").kind);
    }

    @Test
    public void barcodeDisagreeingWithTextNeedsCheck() {
        ParsedPart p = LabelParser.parse(digiKeyWith("2SK3019-TP", "2SK30I9-TP"));
        LabelBarcode.apply(p, Collections.singletonList(LabelBarcodeTest.digiKeyMatrix()));
        assertEquals("2SK3019-TP", p.mpn);
        assertEquals("2SK30I9-TP", p.ocrBeforeBarcode.get(LabelBarcode.F_MPN));
        assertFalse(p.barcodeAgrees.contains(LabelBarcode.F_MPN));
        FieldStatus s = scanned(LabelBarcode.F_MPN, p, p.mpn);
        assertEquals(FieldStatus.Kind.CHECK, s.kind);
        assertTrue(s.text, s.text.contains("2SK30I9-TP"));
    }

    @Test
    public void barcodeFillingAMissedFieldIsConfirmed() {
        List<OcrLine> lines = new ArrayList<>();
        for (OcrLine l : LabelParserTest.digiKey()) if (!l.text.equals("500")) lines.add(l);
        ParsedPart p = LabelParser.parse(lines);
        LabelBarcode.apply(p, Collections.singletonList(LabelBarcodeTest.digiKeyMatrix()));
        assertNull(p.ocrBeforeBarcode.get(LabelBarcode.F_QTY));
        FieldStatus s = scanned(LabelBarcode.F_QTY, p, "500");
        assertEquals(FieldStatus.Kind.CONFIRMED, s.kind);
        assertEquals("From barcode", s.text);
    }

    @Test
    public void mouserDerivedMpnNeedsCheck() {
        ParsedPart p = LabelParser.parse(LabelParserTest.mouser());
        assertTrue(p.mpnDerived);
        FieldStatus s = scanned(LabelBarcode.F_MPN, p, p.mpn);
        assertEquals(FieldStatus.Kind.CHECK, s.kind);
        assertTrue(s.text, s.text.contains("Mouser"));
    }

    @Test
    public void textOnlyFieldHasNoStatus() {
        ParsedPart p = LabelParser.parse(LabelParserTest.digiKey());
        FieldStatus s = scanned(LabelBarcode.F_MPN, p, p.mpn);
        assertEquals(FieldStatus.Kind.PLAIN, s.kind);
        assertNull(s.text);
        assertEquals(FieldStatus.NONE, scanned(LabelBarcode.F_MPN, p, " "));
    }

    @Test
    public void lookupEditAndReread() {
        ParsedPart p = LabelParser.parse(LabelParserTest.mouser());
        FieldStatus looked = FieldStatus.of(LabelBarcode.F_MPN, p, "ABC-1", p.mpn, "ABC-1", "Mouser");
        assertEquals(FieldStatus.Kind.LOOKED_UP, looked.kind);
        assertEquals("Looked up on Mouser", looked.text);
        // Typed over the looked-up value.
        assertEquals(FieldStatus.Kind.EDITED, FieldStatus.of(LabelBarcode.F_MPN, p, "ABC-2", p.mpn, "ABC-1", "Mouser").kind);
        // Typed over the scanned value.
        assertEquals(FieldStatus.Kind.EDITED, FieldStatus.of("notes", p, "my note", "", null, null).kind);
        FieldStatus re = FieldStatus.of(LabelBarcode.F_QTY, p, "25", "20", "25", FieldStatus.REREAD);
        assertEquals(FieldStatus.Kind.PLAIN, re.kind);
        assertTrue(re.text.contains("close-up"));
        // Case and spacing don't count as an edit.
        assertEquals(FieldStatus.Kind.CHECK, FieldStatus.of(LabelBarcode.F_MPN, p, " " + p.mpn.toLowerCase() + " ", p.mpn, null, null).kind);
    }

    @Test
    public void summaryCountsConfirmedAndToCheck() {
        ParsedPart p = LabelParser.parse(LabelParserTest.digiKey());
        LabelBarcode.apply(p, Collections.singletonList(LabelBarcodeTest.digiKeyMatrix()));
        FieldStatus ok = scanned(LabelBarcode.F_MPN, p, p.mpn);
        FieldStatus looked = FieldStatus.of("description", p, "X", "", "X", "DigiKey");
        ParsedPart m = LabelParser.parse(LabelParserTest.mouser());
        FieldStatus check = scanned(LabelBarcode.F_MPN, m, m.mpn);
        List<FieldStatus> all = Arrays.asList(ok, FieldStatus.NONE, check, looked, check);
        assertEquals("7 fields · 2 confirmed · 2 to check", FieldStatus.summary(all, 7));
        assertEquals(2, FieldStatus.firstToCheck(all));
        assertEquals("1 field · 1 confirmed", FieldStatus.summary(Collections.singletonList(ok), 1));
        assertEquals("", FieldStatus.summary(Collections.<FieldStatus>emptyList(), 0));
        assertEquals(-1, FieldStatus.firstToCheck(Collections.singletonList(ok)));
    }

    @Test
    public void changeOnAnExistingPart() {
        assertNull(FieldStatus.change("40", "40", true));
        assertNull(FieldStatus.change("", "40", true));
        assertNull(FieldStatus.change("40", "", true));
        assertNull(FieldStatus.change("abc ", "ABC", false));
        assertEquals("was 40", FieldStatus.change("40", "25", true).text());
        FieldStatus.Change keep = FieldStatus.change("TI", "Texas Instruments", false);
        assertFalse(keep.replaces);
        assertEquals("saved part keeps TI", keep.text());
        String longOld = "RES 10K OHM 1% 1/10W 0603 THICK FILM SMD RESISTOR";
        assertTrue(FieldStatus.change(longOld, "X", true).text().endsWith("…"));
    }
}
