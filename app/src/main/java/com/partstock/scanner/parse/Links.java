package com.partstock.scanner.parse;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;

/** Builds a web link to a part. Needs no account or API key. */
public final class Links {
    private Links() {}

    /**
     * Mouser part numbers open the product page directly. DigiKey links go to a search for the part number,
     * which jumps straight to the product when the number is exact. With only an MPN, search Mouser for it.
     */
    public static String productUrl(String distributor, String distributorPn, String mpn) {
        String dpn = blank(distributorPn) ? null : distributorPn.trim();
        String m = blank(mpn) ? null : mpn.trim();
        if (dpn != null && ParsedPart.MOUSER.equalsIgnoreCase(distributor)) {
            return "https://www.mouser.com/ProductDetail/" + enc(dpn);
        }
        if (ParsedPart.DIGIKEY.equalsIgnoreCase(distributor) && (dpn != null || m != null)) {
            return "https://www.digikey.com/en/products/result?keywords=" + enc(dpn != null ? dpn : m);
        }
        if (dpn != null && dpn.toUpperCase(Locale.US).endsWith("-ND")) {
            return "https://www.digikey.com/en/products/result?keywords=" + enc(dpn);
        }
        if (m != null) return "https://www.mouser.com/c/?q=" + enc(m);
        return null;
    }

    /**
     * Where the Datasheet button goes: the saved datasheet link from a Mouser / DigiKey lookup when there is one,
     * otherwise a web search for "&lt;part number&gt; datasheet" (MPN first, then the distributor part number).
     * Null when the part has no number to search for.
     */
    public static String datasheetUrl(String saved, String distributorPn, String mpn) {
        if (!blank(saved)) {
            String s = saved.trim();
            if (s.startsWith("//")) s = "https:" + s; // DigiKey sometimes lists protocol-relative links
            if (s.startsWith("http://") || s.startsWith("https://")) return s;
        }
        String q = !blank(mpn) ? mpn.trim() : !blank(distributorPn) ? distributorPn.trim() : null;
        if (q == null) return null;
        return "https://www.google.com/search?q=" + enc(q + " datasheet");
    }

    /** True when {@link #datasheetUrl} fell back to a web search (no saved datasheet link). */
    public static boolean isDatasheetSearch(String url) {
        return url != null && url.startsWith("https://www.google.com/search?");
    }

    /** Short name for the button, e.g. "Open on Mouser". */
    public static String siteName(String url) {
        if (url == null) return "";
        if (url.contains("digikey.")) return "DigiKey";
        if (url.contains("mouser.")) return "Mouser";
        return "web";
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
