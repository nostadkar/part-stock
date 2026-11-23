package com.partstock.scanner.parse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Helpers for the inventory-wide price refresh (pure Java, unit tested). */
public final class PriceRefresh {
    private PriceRefresh() {}

    /** Mouser allows about 30 searches a minute; one every 2.2 s stays under it with some margin. */
    public static final long MOUSER_PACE_MS = 2200;

    /** True when the part has a Mouser part number to look up. */
    public static boolean hasMouserPn(String distributor, String distributorPn, String link) {
        if (distributorPn == null || distributorPn.trim().isEmpty()) return false;
        if (ParsedPart.MOUSER.equalsIgnoreCase(distributor == null ? "" : distributor.trim())) return true;
        return link != null && link.contains("mouser.") && link.contains("/ProductDetail/");
    }

    /** Message after a refresh, e.g. "Updated 38, not found 2" or "... Stopped: rate limit. 5 not checked." */
    public static String summary(int updated, int notFound, int failed, String stoppedReason, int notChecked) {
        StringBuilder sb = new StringBuilder("Updated ").append(updated).append(", not found ").append(notFound);
        if (failed > 0) sb.append(", failed ").append(failed);
        if (stoppedReason != null && !stoppedReason.trim().isEmpty()) {
            sb.append(". Stopped: ").append(stoppedReason.trim());
            if (notChecked > 0) sb.append(". ").append(notChecked).append(" not checked");
        }
        return sb.toString();
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * "Price from Mouser, Oct 18" for a stamp like "2026-10-18 09:12:00"; the year is added when it isn't
     * {@code today}'s. Blank when there is no source or stamp.
     */
    public static String label(String source, String checkedAt, LocalDate today) {
        if (source == null || source.trim().isEmpty() || checkedAt == null || checkedAt.trim().isEmpty()) return "";
        LocalDate d;
        try {
            d = LocalDateTime.parse(checkedAt.trim(), STAMP).toLocalDate();
        } catch (DateTimeParseException e) {
            return "Price from " + source.trim();
        }
        String pattern = d.getYear() == today.getYear() ? "MMM d" : "MMM d, yyyy";
        return "Price from " + source.trim() + ", " + d.format(DateTimeFormatter.ofPattern(pattern, Locale.US));
    }

    /** How old a looked-up price is, for the part page's freshness chip. */
    public enum Freshness { FRESH, AGING, OLD, NEVER }

    /** Days after which a price is "30+ days old" and "90+ days old". */
    public static final int AGING_DAYS = 30, OLD_DAYS = 90;

    /**
     * NEVER when the price wasn't looked up (no source or stamp, e.g. typed in or none), otherwise by age:
     * under 30 days FRESH, under 90 AGING, else OLD. An unreadable stamp counts as OLD.
     */
    public static Freshness freshness(String source, String checkedAt, LocalDate today) {
        if (source == null || source.trim().isEmpty() || checkedAt == null || checkedAt.trim().isEmpty()) {
            return Freshness.NEVER;
        }
        LocalDate d;
        try {
            d = LocalDateTime.parse(checkedAt.trim(), STAMP).toLocalDate();
        } catch (DateTimeParseException e) {
            return Freshness.OLD;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(d, today);
        if (days < AGING_DAYS) return Freshness.FRESH;
        if (days < OLD_DAYS) return Freshness.AGING;
        return Freshness.OLD;
    }

    /** Chip text: "Fresh", "30+ days old", "90+ days old", "Never checked". */
    public static String freshnessText(Freshness f) {
        switch (f) {
            case FRESH: return "Fresh";
            case AGING: return AGING_DAYS + "+ days old";
            case OLD: return OLD_DAYS + "+ days old";
            default: return "Never checked";
        }
    }

    /** True when a Mouser error message means "too many requests". */
    public static boolean isRateLimit(int httpCode, String message) {
        if (httpCode == 429) return true;
        if (message == null) return false;
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("rate limit") || m.contains("too many") || (m.contains("exceed") && m.contains("call"))
                || m.contains("maximum calls");
    }

    // ---- What a refresh changed (the result card's "biggest movers") ----

    /** One looked-up part: its unit cost before and after the refresh (null = no price). */
    public static final class Change {
        public final long id;
        public final String key;
        public final Double oldCost;
        public final Double newCost;

        public Change(long id, String key, Double oldCost, Double newCost) {
            this.id = id;
            this.key = key == null ? "" : key;
            this.oldCost = oldCost;
            this.newCost = newCost;
        }

        public Kind kind() {
            return PriceRefresh.kind(oldCost, newCost);
        }
    }

    /** UP / DOWN: the price moved; NEW_PRICE: had none, has one now; SAME: no change (or no price came back). */
    public enum Kind { UP, DOWN, SAME, NEW_PRICE, NO_PRICE }

    /** Unit costs are kept to 4 decimals, so smaller differences are the same price. */
    private static final double SAME_EPS = 0.00005;

    public static Kind kind(Double oldCost, Double newCost) {
        if (newCost == null) return oldCost == null ? Kind.NO_PRICE : Kind.SAME; // the lookup keeps the old price
        if (oldCost == null) return Kind.NEW_PRICE;
        double d = newCost - oldCost;
        if (Math.abs(d) < SAME_EPS) return Kind.SAME;
        return d > 0 ? Kind.UP : Kind.DOWN;
    }

    /** Change in percent (+12.5 for 0.08 → 0.09); null without both prices or from a zero price. */
    public static Double percent(Double oldCost, Double newCost) {
        if (oldCost == null || newCost == null || Math.abs(oldCost) < SAME_EPS) return null;
        return (newCost - oldCost) / oldCost * 100.0;
    }

    /**
     * The up to {@code max} biggest price moves (up or down), largest percentage first; ties by the larger
     * absolute change, then part number. Parts that only got their first price aren't movers.
     */
    public static List<Change> movers(List<Change> all, int max) {
        List<Change> out = new ArrayList<>();
        for (Change c : all) {
            Kind k = c.kind();
            if ((k == Kind.UP || k == Kind.DOWN) && percent(c.oldCost, c.newCost) != null) out.add(c);
        }
        Collections.sort(out, (a, b) -> {
            int r = Double.compare(Math.abs(percent(b.oldCost, b.newCost)), Math.abs(percent(a.oldCost, a.newCost)));
            if (r != 0) return r;
            r = Double.compare(Math.abs(b.newCost - b.oldCost), Math.abs(a.newCost - a.oldCost));
            if (r != 0) return r;
            return a.key.compareToIgnoreCase(b.key);
        });
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** Ids of the parts whose price moved or that got a first price (for "Show parts with a price change"). */
    public static List<Long> changedIds(List<Change> all) {
        List<Long> out = new ArrayList<>();
        for (Change c : all) {
            Kind k = c.kind();
            if (k == Kind.UP || k == Kind.DOWN || k == Kind.NEW_PRICE) out.add(c.id);
        }
        return out;
    }

    /** "12%" or "2.5%" (one decimal under 10%), without a sign; "<0.1%" for tiny moves. */
    public static String percentText(double pct) {
        double a = Math.abs(pct);
        if (a < 0.05) return "<0.1%";
        if (a < 9.95) return String.format(Locale.US, "%.1f%%", a);
        return String.format(Locale.US, "%.0f%%", a);
    }

    /** A unit cost as the app shows it: up to 4 decimals, at least 2 (0.1 → "0.10", 0.10456 → "0.1046"). */
    public static String cost(double d) {
        java.math.BigDecimal s = java.math.BigDecimal.valueOf(d).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        return (s.scale() < 2 ? s.setScale(2) : s).toPlainString();
    }

    /** "▲ 12% · 0.105 → 0.118" (▲ up, ▼ down). */
    public static String moverText(Change c) {
        Double pct = percent(c.oldCost, c.newCost);
        if (pct == null || c.oldCost == null || c.newCost == null) return "";
        return (pct > 0 ? "▲ " : "▼ ") + percentText(pct) + " · " + cost(c.oldCost) + " → " + cost(c.newCost);
    }

    /** Counts for the result card, e.g. "3 up, 1 down · 2 newly priced · 30 unchanged · 2 not found · 1 failed". */
    public static String resultCounts(List<Change> all, int notFound, int failed) {
        int up = 0, down = 0, fresh = 0, same = 0;
        for (Change c : all) {
            switch (c.kind()) {
                case UP: up++; break;
                case DOWN: down++; break;
                case NEW_PRICE: fresh++; break;
                default: same++;
            }
        }
        List<String> parts = new ArrayList<>();
        if (up + down == 0) parts.add("No price changes");
        else parts.add(up + " up, " + down + " down");
        if (fresh > 0) parts.add(fresh + " newly priced");
        if (same > 0) parts.add(same + " unchanged");
        if (notFound > 0) parts.add(notFound + " not found");
        if (failed > 0) parts.add(failed + " failed");
        return String.join(" · ", parts);
    }
}
