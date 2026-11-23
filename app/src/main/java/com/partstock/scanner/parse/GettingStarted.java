package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The "Getting started" checklist (pure Java, unit tested). Each item is done only from the existing checks (parts in
 * the inventory, a Mouser key, a backup folder, DigiKey keys), never guessed; items other than the first scan can be
 * skipped. Whether the card is shown at all is decided once ([decide]) so existing users never see it, and it is
 * hidden for good when dismissed.
 */
public final class GettingStarted {
    private GettingStarted() {}

    /** Prefs value: show the card (until it's complete). */
    public static final String ACTIVE = "active";
    /** Prefs value: never show it (dismissed, or an existing install). */
    public static final String OFF = "off";

    public enum Item {
        SCAN("Scan your first label", "Point the camera at a DigiKey, Mouser or kit label and tap the shutter.", null),
        MOUSER("Add a Mouser API key", "For prices and part lookups.", "mouser"),
        BACKUP("Choose a backup folder", "A copy of the inventory is saved there after changes.", "backup"),
        EXTRAS("Optional: DigiKey keys, labels, order import",
            "DigiKey keys in Settings; bin labels and order CSV import are in the Inventory ⋮ menu.", "digikey");

        public final String title;
        public final String detail;
        /** The Settings card this item opens, or null (the first scan opens the camera). */
        public final String settingsCard;

        Item(String title, String detail, String settingsCard) {
            this.title = title;
            this.detail = detail;
            this.settingsCard = settingsCard;
        }

        public boolean skippable() {
            return this != SCAN;
        }
    }

    /** What the checks say right now, plus the items the user skipped (by name). */
    public static final class State {
        public final boolean hasParts;
        public final boolean hasMouser;
        public final boolean hasBackup;
        public final boolean hasDigiKey;
        public final Set<String> skipped;

        public State(boolean hasParts, boolean hasMouser, boolean hasBackup, boolean hasDigiKey, Set<String> skipped) {
            this.hasParts = hasParts;
            this.hasMouser = hasMouser;
            this.hasBackup = hasBackup;
            this.hasDigiKey = hasDigiKey;
            this.skipped = skipped == null ? Collections.<String>emptySet() : skipped;
        }
    }

    public static boolean done(Item item, State s) {
        switch (item) {
            case SCAN:
                return s.hasParts;
            case MOUSER:
                return s.hasMouser;
            case BACKUP:
                return s.hasBackup;
            default:
                return s.hasDigiKey;
        }
    }

    public static boolean skipped(Item item, State s) {
        return item.skippable() && !done(item, s) && s.skipped.contains(item.name());
    }

    /** Done or skipped. */
    public static boolean settled(Item item, State s) {
        return done(item, s) || skipped(item, s);
    }

    public static int settledCount(State s) {
        int n = 0;
        for (Item i : Item.values()) if (settled(i, s)) n++;
        return n;
    }

    public static boolean complete(State s) {
        return settledCount(s) == Item.values().length;
    }

    /** "2 of 4". */
    public static String progress(State s) {
        return settledCount(s) + " of " + Item.values().length;
    }

    /** 0..1 for the progress ring. */
    public static float fraction(State s) {
        return settledCount(s) / (float) Item.values().length;
    }

    /**
     * The stored mode, or (first time) whether this install gets the card: a new or empty inventory, or one with
     * neither a backup folder nor a Mouser key. Store the result so the answer doesn't change as items get done.
     */
    public static String decide(String stored, boolean hasParts, boolean hasMouser, boolean hasBackup) {
        if (ACTIVE.equals(stored) || OFF.equals(stored)) return stored;
        return !hasParts || (!hasBackup && !hasMouser) ? ACTIVE : OFF;
    }

    public static boolean visible(String mode, State s) {
        return ACTIVE.equals(mode) && !complete(s);
    }

    /** The skipped set with [item] added. */
    public static Set<String> skip(Set<String> skipped, Item item) {
        Set<String> out = new LinkedHashSet<>(skipped == null ? Collections.<String>emptySet() : skipped);
        if (item.skippable()) out.add(item.name());
        return out;
    }

    /** Items still to do (not done, not skipped), in order. */
    public static List<Item> open(State s) {
        List<Item> out = new ArrayList<>();
        for (Item i : Item.values()) if (!settled(i, s)) out.add(i);
        return out;
    }

    /** TalkBack text for the progress ring. */
    public static String describe(State s) {
        return "Getting started, " + progress(s) + " done";
    }
}
