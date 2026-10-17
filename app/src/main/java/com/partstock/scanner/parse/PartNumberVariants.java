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
            case 'E': return new String[]{"F"};
            case 'F': return new String[]{"E"};
            case 'G': return new String[]{"6"};
            case '6': return new String[]{"G"};
            case 'Z': return new String[]{"2"};
            case '2': return new String[]{"Z"};
            case 'D': return new String[]{"0"};
            default: return new String[0];
        }
    }

    /** Edit distance between two part numbers, ignoring case and spaces. */
    public static int distance(String a, String b) {
        a = norm(a);
        b = norm(b);
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int sub = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /**
     * Index of the candidate closest to {@code wanted}, if it is within {@code maxDist} edits and no other
     * candidate is equally close (a tie means we can't tell which part it is). -1 otherwise.
     */
    public static int closest(List<String> candidates, String wanted, int maxDist) {
        int best = -1, bestD = Integer.MAX_VALUE;
        boolean tie = false;
        for (int i = 0; i < candidates.size(); i++) {
            String c = candidates.get(i);
            if (c == null || c.trim().isEmpty()) continue;
            int d = distance(c, wanted);
            if (d < bestD) { best = i; bestD = d; tie = false; }
            else if (d == bestD && !norm(c).equals(norm(candidates.get(best)))) tie = true;
        }
        return (best >= 0 && bestD <= maxDist && !tie) ? best : -1;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toUpperCase(java.util.Locale.US).replaceAll("\\s+", "");
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
