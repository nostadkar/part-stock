package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.List;

/**
 * Label fonts make OCR swap look-alike characters (I, 1, L, l / O, 0 / S, 5 / B, 8). When a part number
 * finds nothing at the distributor, try the same number with one look-alike swapped at a time.
 */
public final class PartNumberVariants {
    private PartNumberVariants() {}

    private static String[] alternatives(char c) {
        switch (c) {
            case 'I': return new String[]{"1", "L"};
            case 'L': return new String[]{"I", "1"};
            case '1': return new String[]{"I", "L"};
            case 'O': return new String[]{"0"};
            case '0': return new String[]{"O"};
            case 'S': return new String[]{"5"};
            case '5': return new String[]{"S"};
            case 'B': return new String[]{"8"};
            case '8': return new String[]{"B"};
            default: return new String[0];
        }
    }

    /** Up to {@code max} single-character variants, left to right, leaving a leading "NNN-" prefix alone. */
    public static List<String> confusions(String pn, int max) {
        List<String> out = new ArrayList<>();
        if (pn == null) return out;
        String s = pn.trim().toUpperCase(java.util.Locale.US);
        int start = 0;
        int dash = s.indexOf('-');
        if (dash > 0 && dash <= 3 && s.substring(0, dash).matches("\\d+")) start = dash + 1;
        for (int i = start; i < s.length() && out.size() < max; i++) {
            for (String alt : alternatives(s.charAt(i))) {
                if (out.size() >= max) break;
                out.add(s.substring(0, i) + alt + s.substring(i + 1));
            }
        }
        return out;
    }
}
