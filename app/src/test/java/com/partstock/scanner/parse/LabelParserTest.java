package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Fixtures are line boxes laid out like ML Kit output for the three example labels. */
public class LabelParserTest {

    private static final class Lines {
        final List<OcrLine> list = new ArrayList<>();
        Lines add(String t, int l, int top, int r, int b) { list.add(new OcrLine(t, l, top, r, b)); return this; }
    }

    static List<OcrLine> digiKey() {
        return new Lines()
                .add("11-11-2023 12:50", 330, 690, 540, 745)
                .add("701 Brooks Ave South", 660, 670, 930, 715)
                .add("Thief River Falls, MN 56701 USA", 660, 690, 1080, 745)
                .add("DigiKey", 1125, 635, 1390, 700)
                .add("Master Part ID", 328, 760, 432, 790)
                .add("6616132", 330, 783, 400, 808)
                .add("Part Number", 330, 805, 425, 830)
                .add("2SK3019-TPMSCT-ND", 335, 815, 545, 850)
                .add("Manufacturer Part Number", 330, 835, 545, 865)
                .add("2SK3019-TP", 335, 862, 450, 895)
                .add("Part Description", 330, 893, 455, 920)
                .add("MOSFET N-CH 30V 100MA SOT523", 333, 898, 695, 945)
                .add("Customer Reference", 327, 940, 495, 970)
                .add("811755-101", 330, 968, 435, 993)
                .add("Purchase Order", 860, 905, 1005, 930)
                .add("10459898 533455-002", 870, 912, 1100, 945)
                .add("Order", 632, 990, 705, 1018)
                .add("84046368", 645, 1012, 790, 1050)
                .add("IDX: 10", 955, 990, 1068, 1025)
                .add("ROHS3 COMP", 720, 1062, 880, 1092)
                .add("MSL 1", 725, 1112, 795, 1140)
                .add("001874924", 878, 1238, 985, 1258)
                .add("COO", 830, 1270, 885, 1295)
                .add("CHINA", 840, 1292, 938, 1325)
                .add("Lot Code", 835, 1322, 955, 1350)
                .add("000923070600119", 845, 1340, 1115, 1385)
                .add("Date Code", 348, 1345, 470, 1370)
                .add("2327", 350, 1370, 420, 1400)
                .add("Manufacturer", 348, 1398, 510, 1425)
                .add("MICRO COMMERCIAL CO", 357, 1422, 580, 1442)
                .add("Quantity", 1155, 1410, 1272, 1440)
                .add("500", 1170, 1440, 1260, 1495)
                .list;
    }

    static List<OcrLine> entrust() {
        return new Lines()
                .add("Digi-Key", 1215, 660, 1250, 760)
                .add("230977 Entrust SH 533455-002 Rev A Qty 15", 720, 1140, 1060, 1160)
                .add("Item", 660, 1168, 695, 1186)
                .add("12", 765, 1168, 780, 1186)
                .add("PN", 842, 1168, 866, 1186)
                .add("811755-101", 1010, 1168, 1100, 1186)
                .add("Alt PN", 660, 1190, 710, 1210)
                .add("2SK3019-TP", 895, 1190, 975, 1210)
                .add("Desc", 660, 1213, 700, 1233)
                .add("TRANSISTOR, FET, N-CH, SOT523, 30V, 0.1A", 770, 1210, 1100, 1232)
                .add("REF DES", 780, 1238, 840, 1256)
                .add("Qty", 1000, 1236, 1035, 1256)
                .add("29", 1090, 1236, 1105, 1256)
                .add("Q1 Q2 Q3 Q4 Q5 Q6 Q7 Q8 Q9 Q10 Q11 Q12 Q13 Q14 Q15 Q16", 665, 1258, 1115, 1280)
                .add("Q17 Q18 Q19 Q20 Q21 Q22 Q23 Q24 Q25 Q26 Q27 Q28 Q29", 680, 1280, 1105, 1300)
                .add("Kit Label MFG-FRM-017 Rev - 11-20-14", 662, 1305, 870, 1322)
                .list;
    }

    static List<OcrLine> mouser() {
        return new Lines()
                .add("MOUSER", 390, 625, 555, 650)
                .add("ELECTRONICS", 390, 650, 550, 680)
                .add("www.mouser.com", 720, 620, 1000, 650)
                .add("2D BARCODE", 1210, 618, 1390, 645)
                .add("Cust PO: 10482291", 290, 712, 560, 748)
                .add("Line Item: 010", 280, 815, 500, 848)
                .add("P/N: 798-DF11-12DP-2DS24", 275, 922, 690, 955)
                .add("Lust P/N: 809056-412", 275, 1030, 605, 1062)
                .add("Desc: 2MM R/A PCB HEADER 1", 272, 1140, 720, 1172)
                .add("Hirose Headers & Wire Housings", 265, 1165, 822, 1215)
                .add("RoHS: compliant", 265, 1200, 505, 1240)
                .add("QTY: 10", 262, 1245, 375, 1275)
                .add("COO: MY", 545, 1275, 680, 1305)
                .add("Invoice No.: 086689134", 240, 1545, 625, 1620)
                .add("AB3", 940, 1510, 1180, 1670)
                .list;
    }

    /** One bag with a Mouser label, a MyData sticker and an Entrust kit label all stuck on together. */
    static List<OcrLine> multiLabelBag() {
        return new Lines()
                .add("MOUSER", 290, 588, 440, 612)
                .add("ELECTRONICS", 290, 612, 440, 632)
                .add("WWW.MOUS", 565, 590, 680, 610)
                .add("Carrier: 053716", 697, 520, 900, 550)
                .add("9/2/2026 4 11 29 AM", 965, 535, 1155, 555)
                .add("Cust PO: 10491550", 220, 652, 455, 675)
                .add("Line Item: 028", 220, 727, 415, 750)
                .add("Component:", 707, 690, 858, 718)
                .add("811650-101", 708, 715, 920, 765)
                .add("Comment:", 713, 795, 840, 820)
                .add("IC, SMPS, BUCK,SS, 2A,42V,", 713, 812, 1180, 845)
                .add("P/N: 584-8609AIMSE16#PBF", 215, 800, 550, 822)
                .add("Batch:", 720, 872, 798, 895)
                .add("MYDATA", 998, 870, 1195, 905)
                .add("Cust P/N: 811650 101", 215, 876, 495, 896)
                .add("Desc: 42V, 2A/3A Peak Sync", 212, 945, 585, 968)
                .add("Analog Devices Inc.  Switching Voltage Regulators", 210, 972, 915, 998)
                .add("RoHS: compliant", 208, 1005, 420, 1027)
                .add("QTY: 10", 205, 1040, 300, 1062)
                .add("COO: MY", 450, 1038, 550, 1058)
                .add("2H", 705, 1020, 960, 1115)
                .add("260699 Entrust SH 536966-001 Rev 1 Qty 6", 675, 1170, 1195, 1190)
                .add("Item", 575, 1205, 640, 1225)
                .add("33", 760, 1205, 790, 1225)
                .add("PN", 800, 1205, 835, 1225)
                .add("811650-101", 970, 1200, 1080, 1222)
                .add("Alt PN", 575, 1232, 640, 1254)
                .add("Too Many, See Dimation BOM", 795, 1230, 1070, 1250)
                .add("Desc", 575, 1262, 640, 1284)
                .add("IC, SMPS, BUCK,SS, 2A,42V, MSOP", 770, 1258, 1100, 1280)
                .add("REF DES", 720, 1290, 820, 1310)
                .add("Qty", 990, 1285, 1045, 1305)
                .add("1", 1125, 1285, 1140, 1305)
                .add("U1", 850, 1320, 880, 1340)
                .add("Invoice No.: 091886942", 193, 1285, 512, 1307)
                .add("Kit Label MFG-FRM-017 Rev - 11-20-14", 570, 1350, 860, 1368)
                .list;
    }

    @Test
    public void digiKeyLabel() {
        ParsedPart p = LabelParser.parse(digiKey());
        assertEquals(ParsedPart.DIGIKEY, p.labelType);
        assertEquals("2SK3019-TPMSCT-ND", p.distributorPn);
        assertEquals("2SK3019-TP", p.mpn);
        assertEquals("MOSFET N-CH 30V 100MA SOT523", p.description);
        assertEquals("MICRO COMMERCIAL CO", p.manufacturer);
        assertEquals(Integer.valueOf(500), p.quantity);
        assertEquals("811755-101", p.customerPn);
        assertEquals("2327", p.dateCode);
        assertEquals("PO 10459898 533455-002", p.notes);
    }

    @Test
    public void digiKeyMergedLines() {
        List<OcrLine> l = new Lines()
                .add("DigiKey", 1125, 635, 1390, 700)
                .add("Part Number 2SK3019-TPMSCT-ND", 330, 805, 545, 850)
                .add("Manufacturer Part Number 2SK3019-TP", 330, 855, 545, 895)
                .add("Part Description MOSFET N-CH 30V 100MA SOT523", 330, 900, 695, 945)
                .add("Quantity 5OO", 1155, 1410, 1272, 1495)
                .list;
        ParsedPart p = LabelParser.parse(l);
        assertEquals("2SK3019-TPMSCT-ND", p.distributorPn);
        assertEquals("2SK3019-TP", p.mpn);
        assertEquals("MOSFET N-CH 30V 100MA SOT523", p.description);
        assertEquals(Integer.valueOf(500), p.quantity);
    }

    @Test
    public void entrustLabel() {
        ParsedPart p = LabelParser.parse(entrust());
        assertEquals(ParsedPart.ENTRUST, p.labelType);
        assertEquals("811755-101", p.customerPn);
        assertEquals("2SK3019-TP", p.mpn);
        assertEquals("TRANSISTOR, FET, N-CH, SOT523, 30V, 0.1A", p.description);
        assertEquals(Integer.valueOf(29), p.quantity);
        assertEquals(Integer.valueOf(15), p.kitQuantity);
        assertEquals(29, p.refDesCount);
        assertNull(p.distributorPn);
        assertEquals("Kit 230977 Entrust SH 533455-002 Rev A; item 12", p.notes);
    }

    @Test
    public void entrustMergedRows() {
        List<OcrLine> l = new Lines()
                .add("230977 Entrust SH 533455-002 Rev A Qty 15", 720, 1140, 1060, 1160)
                .add("Item 12 PN 811755-101", 660, 1168, 1100, 1186)
                .add("Alt PN 2SK3019-TP", 660, 1190, 975, 1210)
                .add("Desc TRANSISTOR, FET, N-CH, SOT523, 30V, 0.1A", 660, 1210, 1100, 1232)
                .add("REF DES Qty 29", 780, 1236, 1105, 1256)
                .list;
        ParsedPart p = LabelParser.parse(l);
        assertEquals("811755-101", p.customerPn);
        assertEquals("2SK3019-TP", p.mpn);
        assertEquals("TRANSISTOR, FET, N-CH, SOT523, 30V, 0.1A", p.description);
        assertEquals(Integer.valueOf(29), p.quantity);
        assertEquals(Integer.valueOf(15), p.kitQuantity);
    }

    @Test
    public void mouserLabel() {
        ParsedPart p = LabelParser.parse(mouser());
        assertEquals(ParsedPart.MOUSER, p.labelType);
        assertEquals("798-DF11-12DP-2DS24", p.distributorPn);
        assertEquals("DF11-12DP-2DS24", p.mpn);
        assertTrue(p.mpnDerived);
        assertEquals("809056-412", p.customerPn);
        assertEquals("2MM R/A PCB HEADER 1", p.description);
        assertEquals("Hirose", p.manufacturer);
        assertEquals(Integer.valueOf(10), p.quantity);
        assertEquals("Headers & Wire Housings; COO MY; PO 10482291", p.notes);
    }

    @Test
    public void mouserSplitFieldNames() {
        List<OcrLine> l = new Lines()
                .add("MOUSER ELECTRONICS", 390, 625, 555, 680)
                .add("P/N: 798-DF11-12DP-2DS24", 275, 922, 690, 955)
                .add("Desc:", 272, 1140, 340, 1172)
                .add("2MM R/A PCB HEADER 1", 360, 1140, 720, 1172)
                .add("QTY:", 262, 1245, 320, 1275)
                .add("1O", 330, 1245, 375, 1275)
                .list;
        ParsedPart p = LabelParser.parse(l);
        assertEquals("2MM R/A PCB HEADER 1", p.description);
        assertEquals(Integer.valueOf(10), p.quantity);
    }

    @Test
    public void inventoryKeyFallsBack() {
        ParsedPart p = new ParsedPart();
        p.customerPn = "811755-101";
        assertEquals("811755-101", p.inventoryKey());
        p.mpn = "2SK3019-TP";
        assertEquals("2SK3019-TP", p.inventoryKey());
    }

    @Test
    public void multiLabelBagMergesIntoOnePart() {
        ParsedPart p = LabelParser.parse(multiLabelBag());
        assertEquals("Mouser + Entrust kit + MyData", p.labelType);
        assertEquals(ParsedPart.MOUSER, p.distributor);
        assertEquals("584-8609AIMSE16#PBF", p.distributorPn);
        assertEquals("8609AIMSE16#PBF", p.mpn);
        assertTrue(p.mpnDerived);
        assertEquals("811650-101", p.customerPn);
        assertEquals("IC, SMPS, BUCK,SS, 2A,42V, MSOP", p.description);
        assertEquals("Analog Devices Inc.", p.manufacturer);
        // The bag holds 10; the kit table's Qty of 1 is per board and must not replace it.
        assertEquals(Integer.valueOf(10), p.quantity);
        assertEquals(Integer.valueOf(1), p.kitPerBoard);
        assertEquals(Integer.valueOf(6), p.kitQuantity);
        assertEquals("U1", p.refDes);
        assertEquals(1, p.refDesCount);
        assertEquals("Switching Voltage Regulators; COO MY; PO 10491550; "
                + "Kit 260699 Entrust SH 536966-001 Rev 1; item 33", p.notes);
    }

    @Test
    public void myDataStickerAlone() {
        List<OcrLine> l = new Lines()
                .add("Carrier: 053716", 697, 520, 900, 550)
                .add("Component:", 707, 690, 858, 718)
                .add("811650-101", 708, 715, 920, 765)
                .add("Comment:", 713, 795, 840, 820)
                .add("IC, SMPS, BUCK,SS, 2A,42V,", 713, 812, 1180, 845)
                .add("Batch:", 720, 872, 798, 895)
                .add("MYDATA", 998, 870, 1195, 905)
                .list;
        ParsedPart p = LabelParser.parse(l);
        assertEquals(ParsedPart.MYDATA, p.labelType);
        assertEquals("811650-101", p.customerPn);
        assertEquals("IC, SMPS, BUCK,SS, 2A,42V", p.description);
    }

    @Test
    public void productLinks() {
        assertEquals("https://www.mouser.com/ProductDetail/584-8609AIMSE16%23PBF",
                Links.productUrl("Mouser", "584-8609AIMSE16#PBF", "8609AIMSE16#PBF"));
        assertEquals("https://www.digikey.com/en/products/result?keywords=2SK3019-TPMSCT-ND",
                Links.productUrl("DigiKey", "2SK3019-TPMSCT-ND", "2SK3019-TP"));
        assertEquals("https://www.mouser.com/c/?q=2SK3019-TP", Links.productUrl(null, null, "2SK3019-TP"));
        assertNull(Links.productUrl(null, null, null));
        assertEquals("Mouser", Links.siteName("https://www.mouser.com/ProductDetail/x"));
    }

    @Test
    public void mouserPnSplitByOcrGap() {
        // The dot-matrix font leaves a gap that OCR turns into a space, and I can be read as L.
        List<OcrLine> l = new Lines()
                .add("MOUSER ELECTRONICS", 290, 588, 440, 632)
                .add("P/N: 584-8609AIMSE 16#PBF", 215, 800, 600, 822)
                .add("Desc: 42V, 2A/3A Peak Sync", 212, 945, 585, 968)
                .add("QTY: 10", 205, 1040, 300, 1062)
                .list;
        ParsedPart p = LabelParser.parse(l);
        assertEquals("584-8609AIMSE16#PBF", p.distributorPn);
        assertEquals(Integer.valueOf(10), p.quantity);
    }

    @Test
    public void lookAlikeVariantsFindTheRealNumber() {
        List<String> v = PartNumberVariants.confusions("584-8609ALMSE16#PBF", 12);
        assertTrue(v.contains("584-8609AIMSE16#PBF"));
        assertEquals(12, v.size());
        assertTrue(!v.contains("584-8609ALMSE16#PBF"));
    }

    @Test
    public void closestMouserResultFixesOneMisreadLetter() {
        List<String> results = java.util.Arrays.asList("584-8609AIMSE16#TRPBF", "584-8609AIMSE16#PBF", "584-8609BIMSE16#PBF");
        assertEquals(1, PartNumberVariants.closest(results, "584-8609AIMSE16#PBE", 2));
        assertEquals(-1, PartNumberVariants.closest(java.util.Arrays.asList("595-LM358DR"), "584-8609AIMSE16#PBE", 2));
    }

    @Test
    public void custPnDigitsSplitByOcr() {
        assertEquals("811650-101", LabelParser.normalizeCustPn("81 1650 101"));
        assertEquals("811650-101", LabelParser.normalizeCustPn("811650 101"));
    }
}
