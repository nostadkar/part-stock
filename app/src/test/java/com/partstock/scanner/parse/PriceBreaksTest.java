package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PriceBreaksTest {

    @Test
    public void picksBreakForQuantity() {
        int[] q = {1, 10, 100, 1000};
        assertEquals(0, PriceBreaks.pick(q, 1));
        assertEquals(0, PriceBreaks.pick(q, 9));
        assertEquals(1, PriceBreaks.pick(q, 10));
        assertEquals(2, PriceBreaks.pick(q, 500));
        assertEquals(3, PriceBreaks.pick(q, 5000));
    }

    @Test
    public void belowFirstBreakOrUnsorted() {
        assertEquals(1, PriceBreaks.pick(new int[]{100, 10}, 5));
        assertEquals(0, PriceBreaks.pick(new int[]{100, 10}, 150));
        assertEquals(-1, PriceBreaks.pick(new int[0], 5));
    }

    @Test
    public void digikeyCategoryNamesFallInBuckets() {
        assertEquals(Categories.RESISTORS, Categories.classify("Chip Resistor - Surface Mount"));
        assertEquals(Categories.TRANSISTORS, Categories.classify("Single FETs, MOSFETs"));
        assertEquals(Categories.TRANSISTORS, Categories.classify("Single Bipolar Transistors"));
        assertEquals(Categories.CAPACITORS, Categories.classify("Ceramic Capacitors"));
        assertEquals(Categories.POWER, Categories.classify("Voltage Regulators - DC DC Switching Regulators"));
        assertEquals(Categories.CONNECTORS, Categories.classify("Rectangular Connectors - Headers, Male Pins"));
        assertEquals(Categories.PROTECTION, Categories.classify("TVS Diodes"));
        assertEquals(Categories.INDUCTORS, Categories.classify("Fixed Inductors"));
        assertEquals(Categories.MCU, Categories.classify("Microcontrollers"));
    }
}
