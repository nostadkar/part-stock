package com.partstock.scanner.parse;

/** Settings → Appearance → Theme (#45): follow the phone, or force light, dark or true black (OLED). */
public enum ThemeMode {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
    BLACK("Black");

    public final String label;

    ThemeMode(String label) {
        this.label = label;
    }

    /** Whether the app draws dark, given the phone's dark-mode setting. */
    public boolean isDark(boolean systemDark) {
        switch (this) {
            case LIGHT: return false;
            case DARK:
            case BLACK: return true;
            default: return systemDark;
        }
    }

    /** True-black surfaces (only in Black). */
    public boolean isBlack() {
        return this == BLACK;
    }

    public static ThemeMode parse(String name) {
        if (name != null) for (ThemeMode m : values()) if (m.name().equals(name)) return m;
        return SYSTEM;
    }
}
