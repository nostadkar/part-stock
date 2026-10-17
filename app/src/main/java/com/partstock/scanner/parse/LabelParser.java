package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns OCR lines from a parts-bag label into a {@link ParsedPart}.
 *
 * Supported layouts:
 *  - DigiKey: small field name with the value on the line below it ("Part Description" / "MOSFET N-CH ...").
 *  - Mouser: inline "Field: value" lines, manufacturer + category on the line under "Desc:".
 *  - Entrust kit labels: a table with the field name in a left cell and the value in the cell to its right.
 *
 * Pure Java with no Android dependencies so it can be unit-tested on a desktop JVM.
 */
public final class LabelParser {
    private LabelParser() {}

    private enum Kind { PN, QTY, TEXT }
    private enum Dir { BELOW, RIGHT }

    private static final int CI = Pattern.CASE_INSENSITIVE;

    /** A field name on a label. {@code any} finds it inside a line, {@code pure} matches a line that is only the name. */
    private static final class Field {
        final Pattern any;
        final Pattern pure;
        final Kind kind;

        Field(String label, Kind kind) {
            this.any = Pattern.compile("(?<![A-Z0-9])(?:" + label + ")(?![A-Z0-9])", CI);
            this.pure = Pattern.compile("^[^A-Z0-9]*(?:" + label + ")[^A-Z0-9]*$", CI);
            this.kind = kind;
        }
    }

    // DigiKey
    private static final Field DK_PN = new Field(
            "(?<!(?:MANUFACTURER|MFR|MFG)\\s{0,2})(?:DIGI-?KEY\\s*)?PART\\s*(?:NUMBER|NO\\.?|#)", Kind.PN);
    private static final Field DK_MPN = new Field(
            "(?:MANUFACTURER|MFR\\.?|MFG\\.?)\\s*PART\\s*(?:NUMBER|NO\\.?|#)?", Kind.PN);
    private static final Field DK_DESC = new Field("(?:PART\\s*)?DESCRIPTION", Kind.TEXT);
    private static final Field DK_MFR = new Field("(?:MANUFACTURER|MFR|MFG)(?!\\s*\\.?\\s*PART)", Kind.TEXT);
    private static final Field DK_QTY = new Field("QUANTITY|QTY", Kind.QTY);
    private static final Field DK_CREF = new Field("CUST(?:OMER|\\.)?\\s*REF(?:ERENCE|\\.)?", Kind.PN);
    private static final Field DK_DATE = new Field("DATE\\s*CODE", Kind.PN);
    private static final Field DK_PO = new Field("PURCHASE\\s*ORDER", Kind.TEXT);

    // Entrust kit label
    private static final Field EN_ITEM = new Field("ITEM", Kind.QTY);
    private static final Field EN_PN = new Field("(?<!ALT\\.?\\s{0,2})P\\s?/?\\s?N", Kind.PN);
    private static final Field EN_ALT = new Field("ALT\\.?\\s*P\\s?/?\\s?N", Kind.PN);
    private static final Field EN_DESC = new Field("DESC(?:RIPTION)?", Kind.TEXT);
    private static final Field EN_QTY = new Field("QTY", Kind.QTY);

    private static final List<Field> ALL_FIELDS = Arrays.asList(
            DK_PN, DK_MPN, DK_DESC, DK_MFR, DK_QTY, DK_CREF, DK_DATE, DK_PO,
            EN_ITEM, EN_PN, EN_ALT, EN_DESC, EN_QTY);

    /** Other field names that must never be taken as a value. */
    private static final Pattern OTHER_LABELS = Pattern.compile(
            "^[^A-Z0-9]*(?:MASTER\\s*PART\\s*ID|ORDER|COO|LOT\\s*CODE|REF\\s*DES|IDX|MSL|OLPN|"
                    + "CUST(?:OMER)?\\s*REF(?:ERENCE)?|ROHS\\d?(?:\\s*COMP\\w*)?)[^A-Z0-9]*$", CI);

    // Mouser inline fields
    private static final Pattern MO_PN = Pattern.compile(
            "^[^A-Z0-9]*(?:MOUSER\\s*)?P\\s*/?\\s*N\\s*[:.;]?\\s*([A-Z0-9][A-Z0-9\\-./+#]*)", CI);
    private static final Pattern MO_CUST_PN = Pattern.compile(
            "[A-Z]{2,5}\\.?\\s*P\\s*/\\s*N\\s*[:.;]?\\s*([A-Z0-9][A-Z0-9\\-./+#]*)", CI);
    private static final Pattern MO_DESC = Pattern.compile("^[^A-Z0-9]*DESC\\w*\\s*[:.;]?\\s*([A-Z0-9].*)$", CI);
    private static final Pattern MO_QTY = Pattern.compile("(?<![A-Z0-9])QTY\\s*[:.;]?\\s*([0-9OoIl|,]+)", CI);
    private static final Pattern MO_COO = Pattern.compile("(?<![A-Z0-9])COO\\s*[:.;]?\\s*([A-Z]{2,})", CI);
    private static final Pattern MO_CUST_PO = Pattern.compile(
            "CUST\\w*\\s*P\\.?\\s*[O0]\\.?\\s*[:.;#]?\\s*([0-9]{4,})", CI);
    private static final Pattern MO_FIELD_LINE = Pattern.compile(
            "^[^A-Z0-9]*(?:ROHS|QTY|COO|CUST|LINE\\s*ITEM|INVOICE|P\\s*/\\s*N|DESC|WWW)", CI);
    private static final Pattern MOUSER_PN_PREFIX = Pattern.compile("^(\\d{2,3})-(.{3,})$");

    private static final Pattern ND_TOKEN = Pattern.compile(
            "(?<![A-Z0-9])([A-Z0-9][A-Z0-9\\-./+#]*-ND)(?![A-Z0-9])", CI);
    private static final Pattern REFDES_TOKEN = Pattern.compile(
            "^(?:Q|R|C|U|D|L|J|Y|F|K|T|X|P|S|SW|TP|FB|RN|CR|VR|BT|LED|IC)\\d{1,4}[A-Z]?$", CI);
    private static final Pattern HEADER_QTY = Pattern.compile("QTY\\s*[:#]?\\s*([0-9OoIl]+)", CI);

    /** Manufacturers whose names are more than one word, so the Mouser "maker + category" line splits right. */
    private static final List<String> MULTI_WORD_MAKERS = Arrays.asList(
            "TEXAS INSTRUMENTS", "TE CONNECTIVITY", "ANALOG DEVICES", "MICROCHIP TECHNOLOGY",
            "ON SEMICONDUCTOR", "NXP SEMICONDUCTORS", "DIODES INCORPORATED", "INFINEON TECHNOLOGIES",
            "MICRO COMMERCIAL", "ROHM SEMICONDUCTOR", "TAIYO YUDEN", "KEMET ELECTRONICS",
            "BOURNS INC", "AMPHENOL ICC", "AMPHENOL FCI", "PHOENIX CONTACT", "WURTH ELEKTRONIK",
            "WURTH ELECTRONICS", "SAMSUNG ELECTRO-MECHANICS", "LITTELFUSE INC", "NICHICON",
            "PANASONIC ELECTRONIC", "MAXIM INTEGRATED", "SILICON LABS", "STMICROELECTRONICS",
            "TOSHIBA SEMICONDUCTOR", "VISHAY DALE", "VISHAY SEMICONDUCTORS", "VISHAY GENERAL",
            "YAGEO", "MOLEX", "JST SALES", "C&K");

    public static ParsedPart parse(List<OcrLine> input) {
        List<OcrLine> lines = new ArrayList<>();
        for (OcrLine l : input) if (!l.text.isEmpty()) lines.add(l);
        Collections.sort(lines, (a, b) -> a.top != b.top ? Integer.compare(a.top, b.top)
                : Integer.compare(a.left, b.left));

        ParsedPart p = new ParsedPart();
        StringBuilder raw = new StringBuilder();
        for (OcrLine l : lines) raw.append(l.text).append('\n');
        p.rawText = raw.toString().trim();
        String all = p.rawText.toUpperCase(Locale.US);

        if (all.contains("ENTRUST") || all.matches("(?s).*ALT\\.?\\s*P\\s?/?\\s?N.*")
                || all.matches("(?s).*REF\\s*DES.*") || all.contains("KIT LABEL")) {
            p.labelType = ParsedPart.ENTRUST;
            parseEntrust(lines, p);
        } else if (all.contains("MOUSER")) {
            p.labelType = ParsedPart.MOUSER;
            parseMouser(lines, p);
        } else if (all.contains("DIGIKEY") || all.contains("DIGI-KEY") || all.contains("DIGI KEY")
                || ND_TOKEN.matcher(all).find() || all.contains("PART DESCRIPTION")
                || all.contains("MANUFACTURER PART")) {
            p.labelType = ParsedPart.DIGIKEY;
            parseDigiKey(lines, p);
        } else if (Pattern.compile("^[^A-Z0-9]*P\\s*/\\s*N\\s*:", CI | Pattern.MULTILINE).matcher(all).find()
                && all.contains("DESC")) {
            p.labelType = ParsedPart.MOUSER;
            parseMouser(lines, p);
        } else {
            parseUnknown(lines, p);
        }
        return p;
    }

    // ---------------------------------------------------------------- label types

    private static void parseDigiKey(List<OcrLine> lines, ParsedPart p) {
        Set<OcrLine> none = Collections.emptySet();
        p.distributor = ParsedPart.DIGIKEY;
        p.distributorPn = extract(DK_PN, lines, Dir.BELOW, none);
        if (p.distributorPn == null || !p.distributorPn.endsWith("-ND")) {
            String nd = findNdToken(lines);
            if (nd != null) p.distributorPn = nd;
        }
        p.mpn = extract(DK_MPN, lines, Dir.BELOW, none);
        p.description = upper(extract(DK_DESC, lines, Dir.BELOW, none));
        p.manufacturer = extract(DK_MFR, lines, Dir.BELOW, none);
        p.quantity = toInt(extract(DK_QTY, lines, Dir.BELOW, none));
        p.customerPn = extract(DK_CREF, lines, Dir.BELOW, none);
        p.dateCode = extract(DK_DATE, lines, Dir.BELOW, none);
        String po = extract(DK_PO, lines, Dir.BELOW, none);
        if (po != null) p.notes = "PO " + po;
    }

    private static void parseMouser(List<OcrLine> lines, ParsedPart p) {
        p.distributor = ParsedPart.MOUSER;
        OcrLine descLine = null;
        String coo = null, custPo = null;
        for (OcrLine l : lines) {
            String t = l.text;
            Matcher m;
            if ((m = MO_PN.matcher(t)).find()) {
                if (p.distributorPn == null) p.distributorPn = cleanPn(m.group(1));
                else if (p.customerPn == null) p.customerPn = cleanPn(m.group(1));
                continue;
            }
            if (p.customerPn == null && (m = MO_CUST_PN.matcher(t)).find()) {
                p.customerPn = cleanPn(m.group(1));
                continue;
            }
            if (p.description == null && (m = MO_DESC.matcher(t)).find()) {
                p.description = upper(m.group(1));
                descLine = l;
                continue;
            }
            if (p.quantity == null && (m = MO_QTY.matcher(t)).find()) p.quantity = toInt(m.group(1));
            if (coo == null && (m = MO_COO.matcher(t)).find()) coo = m.group(1).toUpperCase(Locale.US);
            if (custPo == null && (m = MO_CUST_PO.matcher(t)).find()) custPo = m.group(1);
        }
        // Fallbacks for when OCR splits "Field:" and its value into separate lines.
        Set<OcrLine> none = Collections.emptySet();
        if (p.description == null) p.description = upper(extract(EN_DESC, lines, Dir.RIGHT, none));
        if (p.quantity == null) p.quantity = toInt(extract(EN_QTY, lines, Dir.RIGHT, none));

        String category = null;
        if (descLine != null) {
            String makerLine = neighbor(descLine, lines, Kind.TEXT, Dir.BELOW,
                    s -> MO_FIELD_LINE.matcher(s).find());
            if (makerLine != null) {
                String[] split = splitMaker(makerLine);
                p.manufacturer = split[0];
                category = split[1];
            }
        }
        if (p.distributorPn != null) {
            Matcher m = MOUSER_PN_PREFIX.matcher(p.distributorPn);
            if (m.matches()) {
                p.mpn = m.group(2);
                p.mpnDerived = true;
            }
        }
        List<String> notes = new ArrayList<>();
        if (category != null) notes.add(category);
        if (coo != null) notes.add("COO " + coo);
        if (custPo != null) notes.add("PO " + custPo);
        if (!notes.isEmpty()) p.notes = String.join("; ", notes);
    }

    private static void parseEntrust(List<OcrLine> lines, ParsedPart p) {
        Set<OcrLine> header = new HashSet<>();
        String headerText = null;
        for (OcrLine l : lines) {
            if (l.text.toUpperCase(Locale.US).contains("ENTRUST")) {
                header.add(l);
                headerText = l.text;
                Matcher m = HEADER_QTY.matcher(l.text);
                if (m.find()) p.kitQuantity = toInt(m.group(1));
                break;
            }
        }
        p.customerPn = extract(EN_PN, lines, Dir.RIGHT, header);
        p.mpn = extract(EN_ALT, lines, Dir.RIGHT, header);
        p.description = upper(extract(EN_DESC, lines, Dir.RIGHT, header));
        p.quantity = toInt(extract(EN_QTY, lines, Dir.RIGHT, header));
        Integer item = toInt(extract(EN_ITEM, lines, Dir.RIGHT, header));

        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (OcrLine l : lines) {
            String[] toks = l.text.trim().split("[\\s,;]+");
            int hits = 0;
            for (String t : toks) if (REFDES_TOKEN.matcher(t).matches()) hits++;
            if (toks.length >= 2 && hits >= Math.ceil(toks.length * 0.8)) {
                for (String t : toks) if (REFDES_TOKEN.matcher(t).matches()) refs.add(t.toUpperCase(Locale.US));
            }
        }
        if (!refs.isEmpty()) {
            p.refDes = String.join(" ", refs);
            p.refDesCount = refs.size();
            if (p.quantity == null) p.quantity = refs.size();
        }

        List<String> notes = new ArrayList<>();
        if (headerText != null) {
            String kit = headerText.replaceAll("(?i)\\s*QTY\\s*[:#]?\\s*[0-9OoIl]+\\s*$", "").trim();
            notes.add("Kit " + kit);
        }
        if (item != null) notes.add("item " + item);
        if (!notes.isEmpty()) p.notes = String.join("; ", notes);
    }

    private static void parseUnknown(List<OcrLine> lines, ParsedPart p) {
        parseMouser(lines, p);
        p.distributor = null;
        if (p.mpnDerived) {
            p.mpn = null;
            p.mpnDerived = false;
        }
        Set<OcrLine> none = Collections.emptySet();
        if (p.mpn == null) p.mpn = extract(DK_MPN, lines, Dir.BELOW, none);
        if (p.description == null) p.description = upper(extract(DK_DESC, lines, Dir.BELOW, none));
        if (p.manufacturer == null) p.manufacturer = extract(DK_MFR, lines, Dir.BELOW, none);
        if (p.quantity == null) p.quantity = toInt(extract(DK_QTY, lines, Dir.BELOW, none));
        p.labelType = ParsedPart.UNKNOWN;
    }

    // ---------------------------------------------------------------- field lookup

    private static String extract(Field f, List<OcrLine> lines, Dir pref, Set<OcrLine> exclude) {
        // 1. A line that is just the field name: take the value beside or below it.
        for (OcrLine l : lines) {
            if (exclude.contains(l) || !f.pure.matcher(l.text).matches()) continue;
            String v = neighbor(l, lines, f.kind, pref, null);
            if (v == null) v = neighbor(l, lines, f.kind, pref == Dir.BELOW ? Dir.RIGHT : Dir.BELOW, null);
            if (v != null) return v;
        }
        // 2. Field name and value merged into one line.
        for (OcrLine l : lines) {
            if (exclude.contains(l)) continue;
            Matcher m = f.any.matcher(l.text);
            while (m.find()) {
                String v = inlineValue(l.text.substring(m.end()), f.kind);
                if (v != null) return v;
            }
        }
        return null;
    }

    private static String neighbor(OcrLine label, List<OcrLine> lines, Kind kind, Dir dir,
                                   Predicate<String> reject) {
        String best = null;
        double bestScore = Double.MAX_VALUE;
        for (OcrLine c : lines) {
            if (c == label || isLabel(c.text)) continue;
            if (reject != null && reject.test(c.text)) continue;
            String v = spatialValue(c.text, kind);
            if (v == null) continue;
            double score;
            if (dir == Dir.BELOW) {
                double h = Math.max(label.height(), c.height());
                if (c.centerY() <= label.centerY() + 0.3 * label.height()) continue;
                if (c.top - label.bottom > 1.5 * h) continue;
                int slack = 2 * label.height();
                if (c.left > label.right + slack || c.right < label.left - slack) continue;
                score = (c.centerY() - label.centerY()) + 0.2 * Math.abs(c.left - label.left);
            } else {
                if (c.left < label.left + label.width() * 0.6) continue;
                if (label.verticalOverlap(c) < 0.4 * Math.min(label.height(), c.height())) continue;
                score = c.left - label.right;
            }
            if (score < bestScore) {
                bestScore = score;
                best = v;
            }
        }
        return best;
    }

    private static boolean isLabel(String text) {
        if (OTHER_LABELS.matcher(text).matches()) return true;
        for (Field f : ALL_FIELDS) if (f.pure.matcher(text).matches()) return true;
        return false;
    }

    /** Value from a whole neighbouring line. */
    private static String spatialValue(String text, Kind kind) {
        switch (kind) {
            case PN: {
                String pn = cleanPn(text);
                return pn != null && pn.matches(".*\\d.*") && pn.length() >= 3 ? pn : null;
            }
            case QTY: {
                String t = text.replaceAll("\\s", "");
                if (t.length() > 10) return null;
                Matcher m = Pattern.compile("^\\D{0,3}([0-9][0-9,]*)\\D{0,4}$").matcher(fixDigits(t));
                return m.matches() ? m.group(1) : null;
            }
            default: {
                String t = text.replaceAll("\\s+", " ").trim();
                return t.length() >= 2 && t.matches(".*[A-Za-z0-9].*") ? t : null;
            }
        }
    }

    /** Value following a field name on the same line. */
    private static String inlineValue(String rest, Kind kind) {
        String r = rest.replaceFirst("^[\\s:;.#=]+", "");
        if (r.isEmpty()) return null;
        switch (kind) {
            case PN: {
                Matcher m = Pattern.compile("^([A-Z0-9][A-Z0-9\\-./+#]*)", CI).matcher(r);
                if (!m.find()) return null;
                String pn = cleanPn(m.group(1));
                return pn != null && pn.matches(".*\\d.*") && pn.length() >= 3 ? pn : null;
            }
            case QTY: {
                Matcher m = Pattern.compile("^([0-9][0-9,]*)").matcher(fixDigits(r.split("\\s+")[0]));
                return m.find() ? m.group(1) : null;
            }
            default: {
                String t = r.replaceAll("\\s+", " ").trim();
                return t.length() >= 2 && t.matches(".*[A-Za-z].*") ? t : null;
            }
        }
    }

    private static String findNdToken(List<OcrLine> lines) {
        for (OcrLine l : lines) {
            Matcher m = ND_TOKEN.matcher(l.text.replaceAll("\\s*-\\s*", "-"));
            if (m.find()) return cleanPn(m.group(1));
        }
        return null;
    }

    // ---------------------------------------------------------------- cleanup helpers

    static String cleanPn(String s) {
        if (s == null) return null;
        String t = s.toUpperCase(Locale.US).replaceAll("\\s*-\\s*", "-").trim();
        t = t.replaceFirst("^[^A-Z0-9]+", "");
        if (t.isEmpty()) return null;
        t = t.split("\\s+")[0].replaceFirst("[.,;:]+$", "");
        return t.isEmpty() ? null : t;
    }

    /** Common OCR letter/digit confusions inside numbers. */
    static String fixDigits(String s) {
        return s.replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1').replace('|', '1');
    }

    static Integer toInt(String s) {
        if (s == null) return null;
        String d = fixDigits(s).replaceAll("[^0-9]", "");
        if (d.isEmpty() || d.length() > 9) return null;
        return Integer.parseInt(d);
    }

    private static String upper(String s) {
        return s == null ? null : s.replaceAll("\\s+", " ").trim().toUpperCase(Locale.US);
    }

    /** Splits Mouser's "Hirose Headers & Wire Housings" into maker and category. */
    static String[] splitMaker(String line) {
        String t = line.replaceAll("\\s+", " ").trim();
        String up = t.toUpperCase(Locale.US);
        for (String maker : MULTI_WORD_MAKERS) {
            if (up.startsWith(maker + " ") || up.equals(maker)) {
                String rest = t.substring(maker.length()).trim();
                return new String[]{t.substring(0, maker.length()), rest.isEmpty() ? null : rest};
            }
        }
        int sp = t.indexOf(' ');
        if (sp < 0) return new String[]{t, null};
        return new String[]{t.substring(0, sp), t.substring(sp + 1).trim()};
    }
}
