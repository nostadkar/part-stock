package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Data prep for the Locations screen (pure Java, unit tested): fill relative to the fullest bin, a bin's top
 * categories, sort orders and the name search. "Unassigned" always stays at the end.
 */
public final class BinStats {
    private BinStats() {}

    /** The Locations sort chips. */
    public enum Sort {
        NAME("Name"),
        MOST_PARTS("Most parts"),
        MOST_PIECES("Most pieces"),
        EMPTY_FIRST("Emptiest first");

        public final String title;

        Sort(String title) {
            this.title = title;
        }
    }

    /** What sorting needs to know about a bin. */
    public interface Bin {
        String name();

        int parts();

        int pieces();
    }

    /** Pieces relative to the fullest bin, 0…1 (0 when every bin is empty). */
    public static float fill(int pieces, int maxPieces) {
        if (maxPieces <= 0 || pieces <= 0) return 0f;
        return (float) Math.min(1.0, pieces / (double) maxPieces);
    }

    /** The most pieces in any one bin, "Unassigned" included. */
    public static int maxPieces(List<? extends Bin> bins) {
        int max = 0;
        for (Bin b : bins) max = Math.max(max, b.pieces());
        return max;
    }

    /**
     * The [n] categories with the most parts, most first; ties keep the order of [counts] (put the usual category
     * order in a LinkedHashMap). Zero counts are left out.
     */
    public static List<String> topCategories(Map<String, Integer> counts, int n) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) list.add(e);
        }
        // Stable: equal counts keep insertion order.
        Collections.sort(list, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size() && i < n; i++) out.add(list.get(i).getKey());
        return out;
    }

    /** True when the bin's name contains the search (case and spacing ignored); a blank search matches all. */
    public static boolean matches(String name, String query) {
        if (query == null || query.trim().isEmpty()) return true;
        String n = squash(name), q = squash(query);
        return n.contains(q);
    }

    private static String squash(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_/.]+", "");
    }

    /** Compares bin names so "A2" comes before "A10". */
    public static int compareNames(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT), y = b.toLowerCase(Locale.ROOT);
        int i = 0, j = 0;
        while (i < x.length() && j < y.length()) {
            char c = x.charAt(i), d = y.charAt(j);
            if (Character.isDigit(c) && Character.isDigit(d)) {
                int si = i, sj = j;
                while (i < x.length() && Character.isDigit(x.charAt(i))) i++;
                while (j < y.length() && Character.isDigit(y.charAt(j))) j++;
                String na = x.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = y.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return Integer.compare(na.length(), nb.length());
                int cmp = na.compareTo(nb);
                if (cmp != 0) return cmp;
            } else {
                if (c != d) return Character.compare(c, d);
                i++;
                j++;
            }
        }
        int rest = Integer.compare(x.length() - i, y.length() - j);
        return rest != 0 ? rest : a.compareTo(b);
    }

    /** [bins] in the chosen order, "Unassigned" last; ties are broken by name. */
    public static <T extends Bin> List<T> sort(List<T> bins, Sort sort) {
        Comparator<T> byName = (a, b) -> compareNames(a.name(), b.name());
        Comparator<T> order;
        switch (sort) {
            case MOST_PARTS:
                order = Comparator.<T>comparingInt(b -> -b.parts()).thenComparing(byName);
                break;
            case MOST_PIECES:
                order = Comparator.<T>comparingInt(b -> -b.pieces()).thenComparing(byName);
                break;
            case EMPTY_FIRST:
                order = Comparator.<T>comparingInt(Bin::pieces).thenComparingInt(Bin::parts).thenComparing(byName);
                break;
            default:
                order = byName;
        }
        Comparator<T> unassignedLast = Comparator.comparing(b -> Locations.same(b.name(), Locations.UNASSIGNED));
        List<T> out = new ArrayList<>(bins);
        Collections.sort(out, unassignedLast.thenComparing(order));
        return out;
    }

    /** "+3" for the parts not shown as thumbnails, or "" when all fit. */
    public static String more(int total, int shown) {
        return total > shown ? "+" + (total - shown) : "";
    }
}
