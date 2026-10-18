package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class LabelCodesTest {

    @Test
    public void partLabelRoundTrips() {
        assertEquals("PS:P:42", LabelCodes.part(42));
        LabelCodes.Code c = LabelCodes.decode("PS:P:42");
        assertTrue(c.isPart());
        assertEquals(42, c.partId);
        assertNull(c.bin);
        assertEquals(7, LabelCodes.decode(" ps:p:7 \n").partId);
    }

    @Test
    public void binLabelRoundTrips() {
        assertEquals("PS:L:A3", LabelCodes.bin("A3"));
        assertEquals("PS:L:Unassigned", LabelCodes.bin(" "));
        assertEquals("PS:L:Shelf 2", LabelCodes.bin("Shelf\n2"));
        LabelCodes.Code c = LabelCodes.decode(LabelCodes.bin("Drawer A3: left"));
        assertFalse(c.isPart());
        assertEquals("Drawer A3: left", c.bin);
        assertEquals(-1, c.partId);
    }

    @Test
    public void otherCodesAreIgnored() {
        assertNull(LabelCodes.decode("https://www.mouser.com/ProductDetail/603-RC0603FR-0710KL"));
        assertNull(LabelCodes.decode("[)>\u001e06\u001dP811755-101"));
        assertNull(LabelCodes.decode(null));
        assertNull(LabelCodes.decode("PS:P:"));
        assertNull(LabelCodes.decode("PS:P:12a"));
        assertNull(LabelCodes.decode("PS:P:0"));
        assertNull(LabelCodes.decode("PS:P:99999999999999999999"));
        assertNull(LabelCodes.decode("PS:L:  "));
        assertNull(LabelCodes.decode("PS:X:1"));
        assertTrue(LabelCodes.isOurs("PS:X:1"));
        assertFalse(LabelCodes.isOurs("PSU-12V"));
        assertFalse(LabelCodes.isOurs("1P2SK3019-TP"));
    }

    @Test
    public void firstPicksTheAppLabelAmongOtherCodes() {
        LabelCodes.Code c = LabelCodes.first(Arrays.asList("798-DF11-12DP-2DS24", "PS:L:R2", "PS:P:5"));
        assertEquals("R2", c.bin);
        assertNull(LabelCodes.first(Arrays.asList("798-DF11-12DP-2DS24")));
        assertNull(LabelCodes.first(null));
    }
}
