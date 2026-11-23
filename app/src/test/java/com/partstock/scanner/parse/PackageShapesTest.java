package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PackageShapesTest {
    @Test
    public void commonPackages() {
        assertEquals(PackageShapes.CHIP_0402, PackageShapes.kind("0402"));
        assertEquals(PackageShapes.CHIP_0603, PackageShapes.kind("0603 (1608 Metric)"));
        assertEquals(PackageShapes.CHIP_0805, PackageShapes.kind("0805"));
        assertEquals(PackageShapes.SOT23, PackageShapes.kind("SOT-23-3"));
        assertEquals(PackageShapes.SOT23, PackageShapes.kind("sot23"));
        assertEquals(PackageShapes.SOIC, PackageShapes.kind("SOIC-8"));
        assertEquals(PackageShapes.SOIC, PackageShapes.kind("SO-8"));
        assertEquals(PackageShapes.QFN, PackageShapes.kind("VQFN-20"));
        assertEquals(PackageShapes.QFN, PackageShapes.kind("QFN-32"));
        assertEquals(PackageShapes.DIP, PackageShapes.kind("PDIP-14"));
        assertEquals(PackageShapes.DIP, PackageShapes.kind("DIP-8"));
        assertEquals(PackageShapes.TO220, PackageShapes.kind("TO-220AB"));
    }

    @Test
    public void unknownGetsNothing() {
        assertEquals("", PackageShapes.kind("MSOP-16"));
        assertEquals("", PackageShapes.kind("SOD-123"));
        assertEquals("", PackageShapes.kind("1206"));
        assertEquals("", PackageShapes.kind(""));
        assertEquals("", PackageShapes.kind(null));
    }
}
