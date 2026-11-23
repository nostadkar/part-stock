package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CategoryTintsTest {
    private static final int[] PALETTE = {0xFF0072B2, 0xFFE69F00, 0xFF009E73, 0xFFCC79A7};

    @Test
    public void sameCategoryAlwaysSameColor() {
        assertEquals(CategoryTints.pick(PALETTE, Categories.RESISTORS), CategoryTints.pick(PALETTE, Categories.RESISTORS));
        assertEquals(CategoryTints.index("Custom bucket"), CategoryTints.index("Custom bucket"));
        assertNotEquals(CategoryTints.pick(PALETTE, Categories.RESISTORS), CategoryTints.pick(PALETTE, Categories.CAPACITORS));
        assertEquals(0, CategoryTints.index(Categories.RESISTORS));
        assertTrue(CategoryTints.index(null) >= 0);
    }

    @Test
    public void derivedTintsDifferAndKeepAlpha() {
        int primary = 0xFF2E5B8A;
        int a = CategoryTints.derived(primary, Categories.RESISTORS);
        int b = CategoryTints.derived(primary, Categories.CAPACITORS);
        assertEquals(primary, a); // slot 0: no rotation
        assertNotEquals(a, b);
        assertEquals(0xFF, b >>> 24);
        assertEquals(0xFF808080 & 0xFF000000, CategoryTints.rotateHue(0xFF808080, 90) & 0xFF000000);
    }

    @Test
    public void contrastMath() {
        assertEquals(21.0, CategoryTints.contrast(0xFF000000, 0xFFFFFFFF), 0.01);
        assertEquals(1.0, CategoryTints.contrast(0xFF777777, 0xFF777777), 0.001);
        assertEquals(0xFF808080, CategoryTints.mix(0xFF000000, 0xFFFFFFFF, 0.5));
    }

    @Test
    public void tileTextIsReadableInLightAndDark() {
        int[] lightSurface = {0xFFF8F9FF, 0xFF191C20};
        int[] darkSurface = {0xFF111418, 0xFFE1E2E8};
        int[] fixed = {0xFF0072B2, 0xFFE69F00, 0xFF009E73, 0xFFCC79A7, 0xFF56B4E9, 0xFFD55E00, 0xFF8C7A00,
                0xFFF0B640, 0xFF3DD6A3, 0xFFE8D85A, 0xFFFFFF00, 0xFF000000};
        for (int[] s : new int[][] {lightSurface, darkSurface}) {
            for (int accent : fixed) {
                int[] t = CategoryTints.tile(accent, s[0], s[1]);
                assertTrue(Integer.toHexString(accent), CategoryTints.contrast(t[1], t[0]) >= 4.5);
            }
            for (String c : Categories.ALL) {
                int[] t = CategoryTints.tile(CategoryTints.derived(0xFFA2C9FF, c), s[0], s[1]);
                assertTrue(c, CategoryTints.contrast(t[1], t[0]) >= 4.5);
                int header = CategoryTints.readable(CategoryTints.derived(0xFF2E5B8A, c), s[0], s[1], 4.5);
                assertTrue(c, CategoryTints.contrast(header, s[0]) >= 4.5);
            }
        }
    }
}
