package com.partstock.scanner.parse;

/** Wording for the "Sync to a file" status line in Settings (pure Java, unit tested). */
public final class SyncStatus {
    private SyncStatus() {}

    /** "just now", "2 min ago", "3 h ago", "yesterday", "5 days ago"; "never" for 0 or less. */
    public static String ago(long thenMs, long nowMs) {
        if (thenMs <= 0) return "never";
        long s = Math.max(0, (nowMs - thenMs) / 1000);
        if (s < 60) return "just now";
        long m = s / 60;
        if (m < 60) return m + " min ago";
        long h = m / 60;
        if (h < 24) return h + " h ago";
        long d = h / 24;
        return d == 1 ? "yesterday" : d + " days ago";
    }

    /**
     * The status line: "Last synced 2 min ago", or the error with when the last good write was, e.g.
     * "Not synced: no connection (last synced 3 h ago). Retries on the next change."
     */
    public static String line(long lastOkMs, String error, long nowMs) {
        if (error == null || error.trim().isEmpty()) {
            return lastOkMs > 0 ? "Last synced " + ago(lastOkMs, nowMs) : "Not synced yet";
        }
        String e = error.trim();
        String last = lastOkMs > 0 ? " (last synced " + ago(lastOkMs, nowMs) + ")" : "";
        return "Not synced: " + e + last + ". Retries on the next change.";
    }
}
