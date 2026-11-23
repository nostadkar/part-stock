package com.partstock.scanner.parse;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A part's count per day for the "Stock over time" sparkline (pure Java, unit tested), built from its history rows:
 * the last count of each day, carried forward over days without changes, ending at today's count.
 */
public final class StockSeries {
    private StockSeries() {}

    /** Longest series drawn for "All" (about 10 years of days). */
    static final int MAX_DAYS = 3660;

    /** One history row; [ts] is "yyyy-MM-dd HH:mm:ss" (only the date is used). */
    public static final class Entry {
        final String ts;
        final Integer before;
        final Integer after;
        final boolean undone;

        public Entry(String ts, Integer before, Integer after, boolean undone) {
            this.ts = ts == null ? "" : ts.trim();
            this.before = before;
            this.after = after;
            this.undone = undone;
        }
    }

    /**
     * Daily counts, oldest first, ending today at [current]. [entries] may be in any order (sorted by time here);
     * undone rows and rows without a count after are skipped. [days] = 30 or 90 for that many days back, 0 for all.
     * The day before the first change starts at its count before (e.g. 0 before the first scan) when that is known.
     * Empty when there is no usable history; fewer than 2 values means not enough to draw.
     */
    public static double[] daily(List<Entry> entries, LocalDate today, int days, int current) {
        List<Entry> ev = new ArrayList<>();
        List<LocalDate> dates = new ArrayList<>();
        for (Entry e : entries) {
            if (e.undone || e.after == null) continue;
            LocalDate d = date(e.ts);
            if (d == null) continue;
            if (d.isAfter(today)) d = today;
            // Insertion sort by timestamp, stable for equal times (keeps the given order).
            int at = ev.size();
            while (at > 0 && ev.get(at - 1).ts.compareTo(e.ts) > 0) at--;
            ev.add(at, e);
            dates.add(at, d);
        }
        if (ev.isEmpty()) return new double[0];

        LocalDate first = dates.get(0);
        Integer seed = ev.get(0).before;
        LocalDate start = seed != null ? first.minusDays(1) : first;
        LocalDate earliest = today.minusDays((days > 0 ? days : MAX_DAYS) - 1);
        if (start.isBefore(earliest)) start = earliest;
        if (start.isAfter(today)) start = today;

        int n = (int) (today.toEpochDay() - start.toEpochDay()) + 1;
        double[] out = new double[n];
        int k = 0;
        double value = seed != null ? seed : ev.get(0).after;
        for (int i = 0; i < n; i++) {
            LocalDate d = start.plusDays(i);
            while (k < ev.size() && !dates.get(k).isAfter(d)) {
                value = ev.get(k).after;
                k++;
            }
            out[i] = value;
        }
        out[n - 1] = current;
        return out;
    }

    /** "2026-03-01 14:02:11" → 2026-03-01; null when it isn't a date. */
    static LocalDate date(String ts) {
        if (ts == null || ts.length() < 10) return null;
        try {
            return LocalDate.parse(ts.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
