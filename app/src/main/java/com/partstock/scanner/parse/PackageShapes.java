package com.partstock.scanner.parse;

import java.util.Locale;

/** Which small package silhouette to draw for a package name (0603, SOT-23-3, SOIC-8, VQFN-20, PDIP-14, TO-220AB). */
public final class PackageShapes {
    private PackageShapes() {}

    public static final String CHIP_0402 = "0402";
    public static final String CHIP_0603 = "0603";
    public static final String CHIP_0805 = "0805";
    public static final String SOT23 = "SOT23";
    public static final String SOIC = "SOIC";
    public static final String QFN = "QFN";
    public static final String DIP = "DIP";
    public static final String TO220 = "TO220";

    /** One of the constants above, or "" when there is no drawing for [pkg]. */
    public static String kind(String pkg) {
        if (pkg == null) return "";
        String p = pkg.toUpperCase(Locale.ROOT).replaceAll("[\\s\\-_]", "");
        if (p.isEmpty()) return "";
        if (p.startsWith("0402")) return CHIP_0402;
        if (p.startsWith("0603")) return CHIP_0603;
        if (p.startsWith("0805")) return CHIP_0805;
        if (p.startsWith("SOT23") || p.startsWith("TO236")) return SOT23;
        if (p.startsWith("SOIC") || p.matches("SO\\d+.*")) return SOIC;
        if (p.matches("[A-Z]{0,2}QFN.*")) return QFN;
        if (p.matches("P?DIP\\d*.*")) return DIP;
        if (p.startsWith("TO220")) return TO220;
        return "";
    }
}
