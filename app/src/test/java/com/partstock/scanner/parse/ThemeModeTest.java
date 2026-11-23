package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ThemeModeTest {

    @Test
    public void darkFollowsTheChoice() {
        assertTrue(ThemeMode.SYSTEM.isDark(true));
        assertFalse(ThemeMode.SYSTEM.isDark(false));
        assertFalse(ThemeMode.LIGHT.isDark(true));
        assertTrue(ThemeMode.DARK.isDark(false));
        assertTrue(ThemeMode.BLACK.isDark(false));
        assertTrue(ThemeMode.BLACK.isBlack());
        assertFalse(ThemeMode.DARK.isBlack());
    }

    @Test
    public void parseFallsBackToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse(null));
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse("purple"));
        assertEquals(ThemeMode.BLACK, ThemeMode.parse("BLACK"));
    }

    /**
     * Black theme: the dark-theme low-stock red (Material dark error), amber (ChartColors.warning) and green
     * (ChartColors.positive), the text colors and the brighter outline stay readable on #000000.
     */
    @Test
    public void accentsReadableOnBlack() {
        int black = 0xFF000000;
        int[] text = {0xFFF2B8B5 /* error */, 0xFFF0B640 /* warning */, 0xFF81C995 /* positive */,
                0xFFE1E2E8 /* onSurface */, 0xFFC3C6CF /* onSurfaceVariant */, 0xFFA2C9FF /* primary */};
        for (int c : text) assertTrue(Integer.toHexString(c), CategoryTints.contrast(c, black) >= 4.5);
        // Outline (field borders, dividers) needs 3:1 against black and against the card containers.
        assertTrue(CategoryTints.contrast(0xFFA0A4AC, black) >= 3);
        assertTrue(CategoryTints.contrast(0xFFA0A4AC, 0xFF1E2024) >= 3);
        // Cards (surfaceContainerLow) are distinguishable from the black background.
        assertTrue(CategoryTints.contrast(0xFF131519, black) > 1.05);
    }
}
