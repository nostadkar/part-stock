package com.partstock.scanner.parse;

/** Status chips for the Settings cards (pure Java, unit tested): a level plus a short text, never color alone. */
public final class SettingsStatus {
    private SettingsStatus() {}

    /** OK = set up and healthy; WORKING = in progress / waiting; WARN = needs a look; ERROR = failed; OFF = not set. */
    public enum Level { OK, WORKING, WARN, ERROR, OFF }

    public static final class Status {
        public final Level level;
        public final String text;

        public Status(Level level, String text) {
            this.level = level;
            this.text = text;
        }

        /** WARN and ERROR open their card automatically. */
        public boolean isProblem() {
            return level == Level.WARN || level == Level.ERROR;
        }

        @Override
        public String toString() {
            return level + ": " + text;
        }
    }

    /** A backup older than this turns amber. */
    public static final long BACKUP_STALE_MS = 7L * 24 * 60 * 60 * 1000;

    /** Mouser key / DigiKey credentials: "Key saved" / "Not set" (the key itself is never shown). */
    public static Status key(boolean saved, String savedText) {
        return saved ? new Status(Level.OK, savedText) : new Status(Level.OFF, "Not set");
    }

    /**
     * Auto backup: "Off"; "Backup failed" (red); "Last backup 2 h ago" (amber after 7 days); "Waiting for the next
     * save" when on but no backup time is recorded yet.
     */
    public static Status backup(boolean on, long lastOkMs, boolean failed, long nowMs) {
        if (!on) return new Status(Level.OFF, "Off");
        if (failed) return new Status(Level.ERROR, "Backup failed");
        if (lastOkMs <= 0) return new Status(Level.WORKING, "Waiting for the next save");
        String text = "Last backup " + SyncStatus.ago(lastOkMs, nowMs);
        return new Status(nowMs - lastOkMs > BACKUP_STALE_MS ? Level.WARN : Level.OK, text);
    }

    /** True for the failure line AutoBackup records ("Last backup failed: …"). */
    public static boolean backupFailed(String statusLine) {
        return statusLine != null && statusLine.startsWith("Last backup failed");
    }

    /** File sync: "Off"; "Not synced" (red) on an error; "Not synced yet"; "Synced 2 min ago". */
    public static Status sync(boolean on, long lastOkMs, String error, long nowMs) {
        if (!on) return new Status(Level.OFF, "Off");
        if (error != null && !error.trim().isEmpty()) return new Status(Level.ERROR, "Not synced");
        if (lastOkMs <= 0) return new Status(Level.WORKING, "Not synced yet");
        return new Status(Level.OK, "Synced " + SyncStatus.ago(lastOkMs, nowMs));
    }

    /** App: "Update available" when a newer build was seen on GitHub, else "Up to date" (or the version name). */
    public static Status app(long currentBuild, long latestKnownBuild, String versionName) {
        if (latestKnownBuild > currentBuild) return new Status(Level.WARN, "Update available");
        return new Status(Level.OK, latestKnownBuild > 0 ? "Up to date" : versionName);
    }
}
