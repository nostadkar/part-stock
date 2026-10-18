package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Label barcodes (DigiKey DataMatrix in MH10.8.2 format, Mouser 1D codes) as a cross-check for OCR. */
public class LabelBarcodeTest {
    private static final String RS = "\u001e", GS = "\u001d", EOT = "\u0004";

    /** The DataMatrix on the DigiKey bag in LabelParserTest.digiKey(). */
    static String digiKeyMatrix() {
        return "[)>" + RS + "06" + GS + "P811755-101" + GS + "1P2SK3019-TP" + GS + "30P2SK3019-TPMSCT-ND"
                + GS + "K10459898 533455-002" + GS + "1K84046368" + GS + "10K100994539" + GS + "9D2327"
                + GS + "1T000923070600119" + GS + "11K1" + GS + "4LCN" + GS + "Q500" + GS + "11ZPICK"
                + GS + "12Z6616132" + GS + "13Z999999" + GS + "20Z0000000000000000000000000000" + RS + EOT;
    }

    private static List<OcrLine> digiKeyWith(String from, String to) {
        List<OcrLine> out = new ArrayList<>();
        for (OcrLine l : LabelParserTest.digiKey()) {
            out.add(l.text.equals(from) ? new OcrLine(to, l.left, l.top, l.right, l.bottom) : l);
        }
        return out;
    }

    @Test
    public void decodesHeaderAndSeparators() {
        Map<String, String> f = LabelBarcode.decodeMh10(digiKeyMatrix());
        assertEquals("811755-101", f.get("P"));
        assertEquals("2SK3019-TP", f.get("1P"));
        assertEquals("2SK3019-TPMSCT-ND", f.get("30P"));
        assertEquals("10459898 533455-002", f.get("K"));
        assertEquals("500", f.get("Q"));
        assertEquals("2327", f.get("9D"));
        assertEquals("000923070600119", f.get("1T"));
        assertEquals("PICK", f.get("11Z"));
    }

    @Test
    public void decodesVisibleSeparatorsAndMissingRecordSeparator() {
        String pictures = "[)>␞06␝P811755-101␝1P2SK3019-TP␝Q500␞␄";
        assertEquals("2SK3019-TP", LabelBarcode.decodeMh10(pictures).get("1P"));
        String noRs = "[)>06" + GS + "1PABC-123" + GS + "Q10" + EOT;
        Map<String, String> f = LabelBarcode.decodeMh10(noRs);
        assertEquals("ABC-123", f.get("1P"));
        assertEquals("10", f.get("Q"));
        assertEquals("XYZ", LabelBarcode.decodeMh10("[)>{RS}06{GS}1PXYZ{GS}Q1{RS}{EOT}").get("1P"));
    }

    @Test
    public void rejectsTextThatIsNotMh10() {
        assertNull(LabelBarcode.decodeMh10("798-DF11-12DP-2DS24"));
        assertNull(LabelBarcode.decodeMh10("[)>06 no separators here"));
        assertNull(LabelBarcode.decodeMh10(null));
    }

    @Test
    public void decodeMapsDigiKeyFields() {
        LabelBarcode.Read r = LabelBarcode.decode(Collections.singletonList(digiKeyMatrix()));
        assertEquals(ParsedPart.DIGIKEY, r.distributor);
        assertEquals("2SK3019-TP", r.mpn);
        assertEquals("2SK3019-TPMSCT-ND", r.distributorPn);
        assertEquals("811755-101", r.customerPn);
        assertEquals(Integer.valueOf(500), r.quantity);
        assertEquals("10459898 533455-002", r.purchaseOrder);
        assertEquals("2327", r.dateCode);
        assertEquals("000923070600119", r.lot);
        assertTrue(r.plain.isEmpty());
    }

    @Test
    public void digiKeyPutsItsOwnPartNumberInPWithoutCustomerReference() {
        String m = "[)>" + RS + "06" + GS + "PABC-ND" + GS + "1PABC" + GS + "30PABC-ND" + GS + "Q5" + RS + EOT;
        assertNull(LabelBarcode.decode(Collections.singletonList(m)).customerPn);
    }

    @Test
    public void barcodeAgreesWithOcrSoNothingIsMarked() {
        ParsedPart p = LabelParser.parse(LabelParserTest.digiKey());
        LabelBarcode.apply(p, Arrays.asList(digiKeyMatrix(), digiKeyMatrix()));
        assertTrue(p.fromBarcode.isEmpty());
        assertEquals("2SK3019-TP", p.mpn);
        assertTrue(p.rawText.contains("[barcodes]"));
        assertTrue(p.rawText.contains("<GS>1P2SK3019-TP<GS>"));
    }

    @Test
    public void barcodeFixesOcrLookAlikesAndMarksTheField() {
        ParsedPart p = LabelParser.parse(digiKeyWith("2SK3019-TP", "2SK30I9-TP"));
        p = LabelBarcode.apply(p, Collections.singletonList(digiKeyMatrix()));
        assertEquals("2SK3019-TP", p.mpn);
        assertTrue(p.fromBarcode.contains(LabelBarcode.F_MPN));
        assertFalse(p.fromBarcode.contains(LabelBarcode.F_DIST_PN));
        assertEquals(Integer.valueOf(500), p.quantity);
    }

    @Test
    public void barcodeFillsFieldsOcrMissed() {
        List<OcrLine> lines = new ArrayList<>();
        for (OcrLine l : LabelParserTest.digiKey()) if (!l.text.equals("500")) lines.add(l);
        ParsedPart p = LabelParser.parse(lines);
        LabelBarcode.apply(p, Collections.singletonList(digiKeyMatrix()));
        assertEquals(Integer.valueOf(500), p.quantity);
        assertTrue(p.fromBarcode.contains(LabelBarcode.F_QTY));
    }

    @Test
    public void barcodeOnlyStillGivesAPart() {
        ParsedPart p = LabelBarcode.apply(new ParsedPart(), Collections.singletonList(digiKeyMatrix()));
        assertEquals(ParsedPart.DIGIKEY, p.distributor);
        assertEquals("2SK3019-TPMSCT-ND", p.distributorPn);
        assertEquals("811755-101", p.customerPn);
        assertEquals("PO 10459898 533455-002", p.notes);
        assertFalse(p.rawText.trim().isEmpty());
    }

    @Test
    public void mouserOneDCodesCorrectMatchingFields() {
        // OCR misread the P/N's 0 as O; the 1D barcode has it right. The PO and qty codes match OCR already.
        List<OcrLine> lines = new ArrayList<>();
        for (OcrLine l : LabelParserTest.mouser()) {
            lines.add(l.text.equals("P/N: 798-DF11-12DP-2DS24")
                    ? new OcrLine("P/N: 798-DF11-12DP-2DS2A", l.left, l.top, l.right, l.bottom) : l);
        }
        ParsedPart p = LabelParser.parse(lines);
        assertEquals("798-DF11-12DP-2DS2A", p.distributorPn);
        LabelBarcode.apply(p, Arrays.asList("10482291", "798-DF11-12DP-2DS24", "10", "809056-412"));
        assertEquals("798-DF11-12DP-2DS24", p.distributorPn);
        assertEquals("DF11-12DP-2DS24", p.mpn); // worked out again from the corrected P/N
        assertTrue(p.mpnDerived);
        assertTrue(p.fromBarcode.contains(LabelBarcode.F_DIST_PN));
        assertTrue(p.fromBarcode.contains(LabelBarcode.F_MPN));
        assertEquals("10482291", p.purchaseOrder);
        assertEquals(Integer.valueOf(10), p.quantity);
        assertFalse(p.fromBarcode.contains(LabelBarcode.F_PO));
        assertFalse(p.fromBarcode.contains(LabelBarcode.F_QTY));
    }

    @Test
    public void oneDCodeThatMatchesNothingIsIgnored() {
        ParsedPart p = LabelParser.parse(LabelParserTest.mouser());
        LabelBarcode.apply(p, Arrays.asList("999999", "ZZTOP-12345678"));
        assertTrue(p.fromBarcode.isEmpty());
        assertEquals("798-DF11-12DP-2DS24", p.distributorPn);
        assertEquals(Integer.valueOf(10), p.quantity);
    }

    @Test
    public void qPrefixedOneDCodeIsTheQuantity() {
        ParsedPart p = LabelParser.parse(LabelParserTest.mouser());
        LabelBarcode.apply(p, Collections.singletonList("Q100"));
        assertEquals(Integer.valueOf(100), p.quantity);
        assertTrue(p.fromBarcode.contains(LabelBarcode.F_QTY));
    }

    @Test
    public void mouserMatrixGivesTheRealMpn() {
        // Newer Mouser labels have an ECIA DataMatrix with the real MPN, which beats the one worked out from the P/N.
        ParsedPart p = LabelParser.parse(LabelParserTest.multiLabelBag());
        assertTrue(p.mpnDerived);
        String m = "[)>" + RS + "06" + GS + "K10491550" + GS + "14K028" + GS + "1PLT8609AIMSE16#PBF" + GS + "Q10"
                + GS + "11K086689134" + GS + "4LMY" + RS + EOT;
        LabelBarcode.apply(p, Collections.singletonList(m));
        assertEquals("LT8609AIMSE16#PBF", p.mpn);
        assertFalse(p.mpnDerived);
        assertTrue(p.fromBarcode.contains(LabelBarcode.F_MPN));
        assertEquals("028", p.lineItem);
        assertEquals("MOUSER|10491550|028", p.bagFingerprint());
    }

    @Test
    public void noBarcodesChangesNothing() {
        ParsedPart p = LabelParser.parse(LabelParserTest.digiKey());
        String raw = p.rawText;
        LabelBarcode.apply(p, Collections.<String>emptyList());
        LabelBarcode.apply(p, null);
        assertEquals(raw, p.rawText);
        assertTrue(p.fromBarcode.isEmpty());
    }
}
