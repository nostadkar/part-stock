package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class PartSpecsTest {

    @Test
    public void digiKeyResistor() {
        PartSpecs s = PartSpecs.fromDescription("RES 10K OHM 1% 1/10W 0603");
        assertEquals("10kΩ", s.value);
        assertEquals("1%", s.tolerance);
        assertEquals("", s.voltage);
        assertEquals("0603", s.pkg);
    }

    @Test
    public void digiKeyCapacitor() {
        PartSpecs s = PartSpecs.fromDescription("CAP CER 0.1UF 50V X7R 0603");
        assertEquals("100nF", s.value);
        assertEquals("50V", s.voltage);
        assertEquals("0603", s.pkg);
    }

    @Test
    public void mouserDescriptions() {
        PartSpecs c = PartSpecs.fromDescription("Multilayer Ceramic Capacitors MLCC - SMD/SMT 50V 0.1uF X7R 0603 10%");
        assertEquals("100nF", c.value);
        assertEquals("50V", c.voltage);
        assertEquals("10%", c.tolerance);
        assertEquals("0603", c.pkg);
        PartSpecs r = PartSpecs.fromDescription("Thick Film Resistors - SMD 1/10W 4.7K ohm 1% 0603 AEC-Q200");
        assertEquals("4.7kΩ", r.value);
        assertEquals("1%", r.tolerance);
        PartSpecs ic = PartSpecs.fromDescription("Switching Voltage Regulators 42V, 2A Synchronous Step-Down in MSOP-16");
        assertEquals("", ic.value);
        assertEquals("42V", ic.voltage);
        assertEquals("MSOP-16", ic.pkg);
    }

    @Test
    public void inductorsAndOtherUnits() {
        PartSpecs l = PartSpecs.fromDescription("FIXED IND 4.7UH 1.5A 100 MOHM SMD");
        assertEquals("4.7µH", l.value);
        assertEquals("1MΩ", PartSpecs.fromDescription("RES 1M OHM 5% 1/10W 0603").value);
        assertEquals("10mΩ", PartSpecs.fromDescription("RES 10 MOHM 1% 1W 2512").value);
        assertEquals("2512", PartSpecs.fromDescription("RES 10 MOHM 1% 1W 2512").pkg);
        assertEquals("10mΩ", PartSpecs.fromDescription("Current Sense Resistors - SMD 10 mOhms 1% 2512").value);
        assertEquals("1MΩ", PartSpecs.fromDescription("Thick Film Resistors 1 MOhms 1% 0402").value);
        assertEquals("10pF", PartSpecs.fromDescription("CAP CER 10PF 50V C0G/NP0 0402").value);
        assertEquals("4.7nF", PartSpecs.fromDescription("CAP CER 4700PF 50V X7R 0603").value);
        assertEquals("10µF", PartSpecs.fromDescription("CAP CER 10UF 25V X5R 0805").value);
        assertEquals("16MHz", PartSpecs.fromDescription("CRYSTAL 16.0000MHZ 18PF SMD").value);
        assertEquals("600Ω", PartSpecs.fromDescription("FERRITE BEAD 600 OHM 0603 1LN").value);
    }

    @Test
    public void packages() {
        assertEquals("SOT-23", PartSpecs.fromDescription("MOSFET N-CH 60V 115MA SOT-23").pkg);
        assertEquals("", PartSpecs.fromDescription("MOSFET N-CH 60V 115MA SOT-23").value);
        assertEquals("SOT-23-5", PartSpecs.fromDescription("IC REG LINEAR 3.3V 500MA SOT23-5").pkg);
        assertEquals("3.3V", PartSpecs.fromDescription("IC REG LINEAR 3.3V 500MA SOT23-5").voltage);
        assertEquals("SOT-523", PartSpecs.fromDescription("MOSFET N-CH 30V 100MA SOT523").pkg);
        assertEquals("TSSOP-20", PartSpecs.fromDescription("IC MCU 32BIT 64KB FLASH 20TSSOP TSSOP20").pkg);
        assertEquals("D2PAK", PartSpecs.fromDescription("MOSFET N-CH 100V 120A D2PAK").pkg);
        assertEquals("", PartSpecs.fromDescription("CONN HEADER 12POS 2MM R/A").pkg);
        assertEquals("", PartSpecs.fromDescription("2MM R/A PCB HEADER 1").value);
    }

    @Test
    public void connectorsGetNothingOdd() {
        PartSpecs s = PartSpecs.fromDescription("Headers & Wire Housings 12P DOUBLE ROW HEADER");
        assertTrue(s.value.isEmpty());
        assertTrue(s.pkg.isEmpty());
    }

    @Test
    public void attributesWinOverNothingAndFillBlanks() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("Resistance", "10 kOhms");
        a.put("Tolerance", "1 %");
        a.put("Package / Case", "0603 (1608 metric)");
        PartSpecs s = PartSpecs.fromAttributes(a).orElse(PartSpecs.fromDescription("RES SMD 50V 1/10W"));
        assertEquals("10kΩ", s.value);
        assertEquals("1%", s.tolerance);
        assertEquals("50V", s.voltage);
        assertEquals("0603", s.pkg);
        Map<String, String> b = new LinkedHashMap<>();
        b.put("Capacitance", "100 nF");
        b.put("Voltage Rating DC", "50 VDC");
        PartSpecs c = PartSpecs.fromAttributes(b);
        assertEquals("100nF", c.value);
        assertEquals("50V", c.voltage);
    }

    @Test
    public void valuesNormalise() {
        PartSpecs.Value tenK = PartSpecs.parseValue("10kΩ");
        for (String s : new String[] {"10k", "10K", "10kohm", "10K OHM", "10000", "10kΩ", "10k0"}) {
            assertTrue(s, PartSpecs.sameValue(PartSpecs.parseValue(s), tenK));
        }
        assertTrue(PartSpecs.sameValue(PartSpecs.parseValue("0.1uF"), PartSpecs.parseValue("100nF")));
        assertTrue(PartSpecs.sameValue(PartSpecs.parseValue("0.1µF"), PartSpecs.parseValue("100n")));
        assertTrue(PartSpecs.sameValue(PartSpecs.parseValue("4k7"), PartSpecs.parseValue("4.7kΩ")));
        assertTrue(PartSpecs.sameValue(PartSpecs.parseValue("4R7"), PartSpecs.parseValue("4.7Ω")));
        assertFalse(PartSpecs.sameValue(PartSpecs.parseValue("10kΩ"), PartSpecs.parseValue("10nF")));
        assertFalse(PartSpecs.sameValue(PartSpecs.parseValue("10k"), PartSpecs.parseValue("110k")));
        assertNull(PartSpecs.parseValue("0603"));
        assertNull(PartSpecs.parseValue("SOT-23"));
        assertNull(PartSpecs.parseValue("50V"));
        assertEquals("100nF", PartSpecs.format(PartSpecs.parseValue("0.1uF").number, 'F'));
        assertEquals("1µF", PartSpecs.format(1e-6, 'F'));
        assertEquals("470Ω", PartSpecs.format(470, 'R'));
        assertEquals("2.2MΩ", PartSpecs.format(2.2e6, 'R'));
    }

    private static boolean find(String q, String desc) {
        List<String> fields = Arrays.asList("RC0603FR-0710KL", desc, "YAGEO", "A3");
        return PartSpecs.matches(q, fields, PartSpecs.fromDescription(desc));
    }

    @Test
    public void searchUsesValuesAndPackages() {
        String r10k = "RES 10K OHM 1% 1/10W 0603";
        String r110k = "RES 110K OHM 1% 1/10W 0805";
        String cap = "CAP CER 0.1UF 50V X7R 0603";
        assertTrue(find("10k 0603", r10k));
        assertTrue(find("10kΩ", r10k));
        assertTrue(find("10000", r10k));
        assertFalse(find("10k", r110k.replace("10K", "110K")));
        assertFalse(find("10k 0603", r110k));
        assertTrue(find("100nF", cap));
        assertTrue(find("0.1uf 0603", cap));
        assertFalse(find("10nF", cap));
        assertTrue(find("1%", r10k));
        assertFalse(find("5%", r10k));
        assertTrue(find("yageo", r10k));
        assertTrue(find("A3", r10k));
        assertTrue(find("RC0603 YAGEO", r10k));
        assertTrue(find("", r10k));
        List<String> mos = Arrays.asList("BSS138", "MOSFET N-CH 50V 200MA SOT-23");
        assertTrue(PartSpecs.matches("sot23", mos, PartSpecs.fromDescription(mos.get(1))));
        assertTrue(PartSpecs.matches("BSS138", mos, PartSpecs.fromDescription(mos.get(1))));
        assertFalse(PartSpecs.matches("0603", mos, PartSpecs.fromDescription(mos.get(1))));
    }
}
