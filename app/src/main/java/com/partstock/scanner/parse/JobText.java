package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Text for the progress card and the update dialog (pure Java, unit tested). */
public final class JobText {
    private JobText() {}

    /** "820 KB", "4.2 MB" (1 MB = 1024 × 1024 bytes); blank for an unknown size (≤ 0). */
    public static String bytes(long n) {
        if (n <= 0) return "";
        if (n < 1024) return n + " B";
        double kb = n / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        return mb < 10 ? String.format(Locale.US, "%.1f MB", mb) : String.format(Locale.US, "%.0f MB", mb);
    }

    /** Download speed from bytes and elapsed milliseconds: "1.3 MB/s", "420 KB/s"; blank until there is some. */
    public static String speed(long bytes, long elapsedMs) {
        if (bytes <= 0 || elapsedMs < 250) return "";
        long perSec = (long) (bytes * 1000.0 / elapsedMs);
        String b = bytes(perSec);
        return b.isEmpty() ? "" : b + "/s";
    }

    /** "12 of 40"; just "12" when the total isn't known. */
    public static String count(int done, int total) {
        return total > 0 ? done + " of " + total : String.valueOf(done);
    }

    /** "3.1 MB of 7.8 MB · 1.2 MB/s" for a download; parts that aren't known yet are left out. */
    public static String download(long done, long total, long elapsedMs) {
        String sp = speed(done, elapsedMs);
        String head = bytes(done);
        if (total > 0) head = (head.isEmpty() ? "0 B" : head) + " of " + bytes(total);
        if (head.isEmpty()) return sp;
        return sp.isEmpty() ? head : head + " · " + sp;
    }

    /**
     * The first {@code max} meaningful lines of release notes: blank lines, Markdown heading marks and bullets,
     * and commit trailers (Co-Authored-By:, Signed-off-by:, Claude-Session:) are dropped; long lines are cut.
     */
    public static List<String> firstLines(String notes, int max) {
        List<String> out = new ArrayList<>();
        if (notes == null) return out;
        for (String raw : notes.replace("\r", "").split("\n")) {
            if (out.size() >= max) break;
            String l = raw.trim();
            if (l.isEmpty()) continue;
            String lower = l.toLowerCase(Locale.ROOT);
            if (lower.startsWith("co-authored-by:") || lower.startsWith("signed-off-by:")
                    || lower.startsWith("claude-session:")) continue;
            l = l.replaceFirst("^#+\\s*", "").replaceFirst("^[-*•]\\s+", "").trim();
            if (l.isEmpty()) continue;
            if (l.length() > 140) l = l.substring(0, 139).trim() + "…";
            out.add(l);
        }
        return out;
    }
}
