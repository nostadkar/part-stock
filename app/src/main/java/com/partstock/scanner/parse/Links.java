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
