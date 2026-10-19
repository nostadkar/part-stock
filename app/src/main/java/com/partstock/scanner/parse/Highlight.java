package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Where the search words appear in a piece of text, for highlighting search results. */
public final class Highlight {
    private Highlight() {}

    /**
     * Start/end pairs ([start, end) as int[2]) of every place a word of [query] appears in [text], ignoring case,
     * sorted and merged so ranges never overlap. Empty when nothing matches.
     */
    public static List<int[]> ranges(String text, String query) {
        List<int[]> found = new ArrayList<>();
        if (text == null || text.isEmpty() || query == null) return found;
        String hay = text.toLowerCase(Locale.ROOT);
        for (String w : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (w.isEmpty() || hay.length() != text.length()) continue; // lowercase changed the length: skip
            int from = 0;
            while (true) {
                int i = hay.indexOf(w, from);
                if (i < 0) break;
                found.add(new int[] {i, i + w.length()});
                from = i + 1;
            }
        }
        found.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        List<int[]> merged = new ArrayList<>();
        for (int[] r : found) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && r[0] <= last[1]) last[1] = Math.max(last[1], r[1]);
            else merged.add(new int[] {r[0], r[1]});
        }
        return merged;
    }
}
