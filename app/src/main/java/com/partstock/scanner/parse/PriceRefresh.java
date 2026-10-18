package com.partstock.scanner.parse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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

    /** True when a Mouser error message means "too many requests". */
    public static boolean isRateLimit(int httpCode, String message) {
        if (httpCode == 429) return true;
        if (message == null) return false;
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("rate limit") || m.contains("too many") || (m.contains("exceed") && m.contains("call"))
                || m.contains("maximum calls");
    }
}
