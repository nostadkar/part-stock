package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class GettingStartedTest {

    private static GettingStarted.State st(boolean parts, boolean mouser, boolean backup, boolean dk, String... skipped) {
        return new GettingStarted.State(parts, mouser, backup, dk, new HashSet<>(Arrays.asList(skipped)));
    }

    @Test
    public void freshInstallStartsAtZero() {
        GettingStarted.State s = st(false, false, false, false);
        assertEquals("0 of 4", GettingStarted.progress(s));
        assertFalse(GettingStarted.complete(s));
        assertEquals(4, GettingStarted.open(s).size());
        assertEquals("Getting started, 0 of 4 done", GettingStarted.describe(s));
    }

    @Test
    public void doneOnlyFromChecks() {
        GettingStarted.State s = st(true, true, false, false);
        assertTrue(GettingStarted.done(GettingStarted.Item.SCAN, s));
        assertTrue(GettingStarted.done(GettingStarted.Item.MOUSER, s));
        assertFalse(GettingStarted.done(GettingStarted.Item.BACKUP, s));
        assertEquals("2 of 4", GettingStarted.progress(s));
        assertEquals(0.5f, GettingStarted.fraction(s), 1e-6);
    }

    @Test
    public void skipping() {
        Set<String> sk = GettingStarted.skip(Collections.<String>emptySet(), GettingStarted.Item.MOUSER);
        sk = GettingStarted.skip(sk, GettingStarted.Item.SCAN); // can't skip the first scan
        assertEquals(Collections.singleton("MOUSER"), sk);
        GettingStarted.State s = new GettingStarted.State(true, false, true, false, GettingStarted.skip(sk, GettingStarted.Item.EXTRAS));
        assertTrue(GettingStarted.skipped(GettingStarted.Item.MOUSER, s));
        assertTrue(GettingStarted.complete(s));
        // Done wins over skipped.
        GettingStarted.State d = st(true, true, true, true, "MOUSER");
        assertFalse(GettingStarted.skipped(GettingStarted.Item.MOUSER, d));
        assertTrue(GettingStarted.complete(d));
    }

    @Test
    public void decideOnce() {
        assertEquals(GettingStarted.ACTIVE, GettingStarted.decide("", false, true, true));   // empty inventory
        assertEquals(GettingStarted.ACTIVE, GettingStarted.decide(null, true, false, false)); // nothing set up
        assertEquals(GettingStarted.OFF, GettingStarted.decide("", true, true, false));       // existing user
        assertEquals(GettingStarted.OFF, GettingStarted.decide("", true, false, true));
        // Stored answers stick.
        assertEquals(GettingStarted.ACTIVE, GettingStarted.decide(GettingStarted.ACTIVE, true, true, true));
        assertEquals(GettingStarted.OFF, GettingStarted.decide(GettingStarted.OFF, false, false, false));
    }

    @Test
    public void visibility() {
        assertTrue(GettingStarted.visible(GettingStarted.ACTIVE, st(false, false, false, false)));
        assertFalse(GettingStarted.visible(GettingStarted.OFF, st(false, false, false, false)));
        assertFalse(GettingStarted.visible(GettingStarted.ACTIVE, st(true, true, true, true)));
        assertEquals("mouser", GettingStarted.Item.MOUSER.settingsCard);
        assertEquals("backup", GettingStarted.Item.BACKUP.settingsCard);
        assertEquals("digikey", GettingStarted.Item.EXTRAS.settingsCard);
    }
}
