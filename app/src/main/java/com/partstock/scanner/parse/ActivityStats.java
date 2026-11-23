package com.partstock.scanner.parse;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Activity page's numbers (pure Java, unit tested), worked out from part_history rows: pieces added vs used per
 * week, the most used parts, the biggest single changes, and parts nobody has touched in 12+ months.
 *
 * Rules: rows marked undone are ignored; undo / CSV restore rows, edits, lookups, moves and bin renames are neutral
 * (neither added nor used). Added = scans, imports and "add by hand" that raised the count; used = Use and builds.
 */
public final class ActivityStats {
    private ActivityStats() {}

    public enum Kind { ADDED, USED, NEUTRAL }

    /** Actions that mean someone handled the part's stock (for "last touched"). "use · …" (builds) counts too. */
    public static final String[] STOCK_ACTIONS = {"scan add", "scan set", "add", "set", "adjust", "import", "use", "move"};

    /** One part_history row. [before] null = the part didn't exist yet; [after] null = it was deleted. */
    public static final class Change {
        public final long partId;
        public final String name;
        public final String ts;
        public final String action;
        public final Integer before;
        public final Integer after;
        public final boolean undone;

        public Change(long partId, String name, String ts, String action, Integer before, Integer after, boolean undone) {
            this.partId = partId;
            this.name = name == null ? "" : name;
            this.ts = ts == null ? "" : ts;
            this.action = action == null ? "" : action;
            this.before = before;
            this.after = after;
            this.undone = undone;
        }
    }

    /** One week's bar pair; [start] is its Monday. */
    public static final class Week {
        public final LocalDate start;
        public int added;
        public int used;

        Week(LocalDate start) {
            this.start = start;
        }

        @Override
        public String toString() {
            return start + " +" + added + " -" + used;
        }
    }

    /** A part and the pieces used in the period. */
    public static final class Used {
        public final long partId;
        public final String name;
        public int pieces;

        Used(long partId, String name) {
            this.partId = partId;
            this.name = name;
        }

        @Override
        public String toString() {
            return name + "=" + pieces;
        }
    }

    /** One of the biggest single changes: the line to show and the part to open. */
    public static final class Big {
        public final long partId;
        public final Kind kind;
        public final int pieces;
        public final String text;

        Big(long partId, Kind kind, int pieces, String text) {
            this.partId = partId;
            this.kind = kind;
            this.pieces = pieces;
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /**
     * What "last touched" needs to know about a part: [lastStock] = newest stock change (see [STOCK_ACTIONS]),
     * [created] = newest row where the part came into being, [updatedAt] = parts.updated_at (for parts with no
     * recorded stock change, e.g. saved before history existed). Any of them may be null or blank.
     */
    public static final class Touch {
        public final long partId;
        public final String name;
        public final int quantity;
        public final String lastStock;
        public final String created;
        public final String updatedAt;

        public Touch(long partId, String name, int quantity, String lastStock, String created, String updatedAt) {
            this.partId = partId;
            this.name = name == null ? "" : name;
            this.quantity = quantity;
            this.lastStock = lastStock;
            this.created = created;
            this.updatedAt = updatedAt;
        }
    }

    public static Kind kind(String action) {
        String a = action == null ? "" : action.trim();
        if (a.equals("use") || a.startsWith("use ·") || a.startsWith("use ")) return Kind.USED;
        switch (a) {
            case "scan add":
            case "scan set":
            case "add":
            case "set":
            case "adjust":
            case "import":
                return Kind.ADDED;
            default:
                return Kind.NEUTRAL;
        }
    }

    /** Pieces this row added (ADDED) or used (USED); 0 for neutral rows, undone rows and count corrections. */
    public static int pieces(Change c) {
        if (c.undone) return 0;
        Kind k = kind(c.action);
        if (k == Kind.NEUTRAL) return 0;
        int delta = (c.after == null ? 0 : c.after) - (c.before == null ? 0 : c.before);
        if (k == Kind.ADDED) return Math.max(0, delta);
        return Math.max(0, -delta);
    }

    /** The Monday of [d]'s week. */
    public static LocalDate weekStart(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** First day counted for a period of [weeks] weeks ending with [today]'s week. */
    public static LocalDate periodStart(LocalDate today, int weeks) {
        return weekStart(today).minusWeeks(Math.max(1, weeks) - 1L);
    }

    /** "2026-11-14 10:22:03" → 2026-11-14, or null. */
    public static LocalDate date(String ts) {
        if (ts == null || ts.length() < 10) return null;
        try {
            return LocalDate.parse(ts.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** [weeks] week buckets, oldest first, the last one being [today]'s week. */
    public static List<Week> weekly(List<Change> changes, LocalDate today, int weeks) {
        int n = Math.max(1, weeks);
        LocalDate first = periodStart(today, n);
        List<Week> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new Week(first.plusWeeks(i)));
        for (Change c : changes) {
            int p = pieces(c);
            if (p == 0) continue;
            LocalDate d = date(c.ts);
            if (d == null || d.isBefore(first) || d.isAfter(today)) continue;
            int i = (int) (ChronoUnit.DAYS.between(first, d) / 7);
            if (i < 0 || i >= n) continue;
            if (kind(c.action) == Kind.ADDED) out.get(i).added += p;
            else out.get(i).used += p;
        }
        return out;
    }

    /** Total {added, used} over the weeks. */
    public static int[] totals(List<Week> weeks) {
        int a = 0, u = 0;
        for (Week w : weeks) {
            a += w.added;
            u += w.used;
        }
        return new int[] {a, u};
    }

    /** "Last 12 weeks: 340 added, 180 used" (what TalkBack reads for the chart). */
    public static String summary(List<Week> weeks) {
        int[] t = totals(weeks);
        int n = weeks.size();
        return "Last " + (n == 1 ? "week" : n + " weeks") + ": " + t[0] + " added, " + t[1] + " used";
    }

    private static boolean inPeriod(Change c, LocalDate today, int weeks) {
        LocalDate d = date(c.ts);
        return d != null && !d.isBefore(periodStart(today, weeks)) && !d.isAfter(today);
    }

    /** The [limit] parts with the most pieces used in the period, most first (ties by name). */
    public static List<Used> topUsed(List<Change> changes, LocalDate today, int weeks, int limit) {
        Map<Long, Used> by = new LinkedHashMap<>();
        for (Change c : changes) {
            if (kind(c.action) != Kind.USED || !inPeriod(c, today, weeks)) continue;
            int p = pieces(c);
            if (p == 0) continue;
            Used u = by.get(c.partId);
            if (u == null) by.put(c.partId, u = new Used(c.partId, c.name));
            u.pieces += p;
        }
        List<Used> out = new ArrayList<>(by.values());
        Collections.sort(out, (x, y) -> x.pieces != y.pieces ? Integer.compare(y.pieces, x.pieces) : x.name.compareToIgnoreCase(y.name));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /** "14 Nov". */
    public static String day(LocalDate d) {
        return d == null ? "" : d.format(DAY);
    }

    private static String verb(String action) {
        String a = action.trim();
        if (a.equals("import")) return "Imported";
        if (a.equals("adjust")) return "Added";
        if (kind(a) == Kind.USED) return "Used";
        return "Scanned";
    }

    /** "Imported 120 × 100nF · 14 Nov", "Used 10 × LM358 · 3 Nov · Build Amp". */
    public static String describe(Change c) {
        String a = c.action.trim();
        String note = a.startsWith("use · ") ? a.substring("use · ".length()).trim() : "";
        String name = c.name.isEmpty() ? "deleted part" : c.name;
        return verb(a) + " " + pieces(c) + " × " + name + " · " + day(date(c.ts)) + (note.isEmpty() ? "" : " · " + note);
    }

    /** The [limit] biggest single adds or uses in the period, biggest first (ties: newest first). */
    public static List<Big> biggest(List<Change> changes, LocalDate today, int weeks, int limit) {
        List<Change> hits = new ArrayList<>();
        for (Change c : changes) if (pieces(c) > 0 && inPeriod(c, today, weeks)) hits.add(c);
        Collections.sort(hits, (x, y) -> {
            int px = pieces(x), py = pieces(y);
            return px != py ? Integer.compare(py, px) : y.ts.compareTo(x.ts);
        });
        List<Big> out = new ArrayList<>();
        for (Change c : hits) {
            if (out.size() >= limit) break;
            out.add(new Big(c.partId, kind(c.action), pieces(c), describe(c)));
        }
        return out;
    }

    /**
     * Parts in stock whose stock nobody has touched for [months] months: the newest stock change (or, with none
     * recorded, the last update) is older than that, and the part wasn't added in that time. Oldest first.
     */
    public static List<Touch> untouched(List<Touch> parts, LocalDate today, int months) {
        LocalDate cutoff = today.minusMonths(months);
        List<Touch> out = new ArrayList<>();
        for (Touch t : parts) {
            if (t.quantity <= 0) continue;
            LocalDate last = date(blank(t.lastStock) ? t.updatedAt : t.lastStock);
            if (last == null || !last.isBefore(cutoff)) continue;
            LocalDate created = date(t.created);
            if (created != null && !created.isBefore(cutoff)) continue;
            out.add(t);
        }
        Collections.sort(out, (x, y) -> lastOf(x).compareTo(lastOf(y)));
        return out;
    }

    /** The date [untouched] went by, for "last touched 3 Mar 2025". */
    public static LocalDate lastTouched(Touch t) {
        return date(blank(t.lastStock) ? t.updatedAt : t.lastStock);
    }

    private static String lastOf(Touch t) {
        String s = blank(t.lastStock) ? t.updatedAt : t.lastStock;
        return s == null ? "" : s;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
