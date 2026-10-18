package com.partstock.scanner.parse;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Value (10kΩ, 100nF, 4.7µH, 16MHz), tolerance (1%), voltage (50V) and package (0603, SOT-23, MSOP-16) of a part,
 * worked out from its description or from a distributor's attributes, plus a search that treats equal values as
 * equal (10k = 10K = 10kΩ = 10000; 0.1uF = 100nF). Pure Java so it is unit tested.
 */
public final class PartSpecs {
    public final String value;
    public final String tolerance;
    public final String voltage;
    public final String pkg;

    public PartSpecs(String value, String tolerance, String voltage, String pkg) {
        this.value = value == null ? "" : value.trim();
        this.tolerance = tolerance == null ? "" : tolerance.trim();
        this.voltage = voltage == null ? "" : voltage.trim();
        this.pkg = pkg == null ? "" : pkg.trim();
    }

    public static final PartSpecs EMPTY = new PartSpecs("", "", "", "");

    public boolean isEmpty() {
        return value.isEmpty() && tolerance.isEmpty() && voltage.isEmpty() && pkg.isEmpty();
    }

    /** This one's fields, with blanks filled from [other]. */
    public PartSpecs orElse(PartSpecs other) {
        if (other == null) return this;
        return new PartSpecs(
                value.isEmpty() ? other.value : value,
                tolerance.isEmpty() ? other.tolerance : tolerance,
                voltage.isEmpty() ? other.voltage : voltage,
                pkg.isEmpty() ? other.pkg : pkg);
    }

    /** Non-blank fields in display order, for the small chips on an inventory row. */
    public List<String> chips() {
        List<String> out = new ArrayList<>();
        for (String s : new String[] {value, tolerance, voltage, pkg}) if (!s.isEmpty()) out.add(s);
        return out;
    }

    @Override
    public String toString() {
        return String.join(" ", chips());
    }

    // ---------------------------------------------------------------- extraction

    private static final String NUM = "(\\d+(?:[.,]\\d+)?)";
    private static final String START = "(?<![\\w.,/])";

    private static final Pattern IND = Pattern.compile(START + NUM + "\\s*([nuUµμmM])H(?![A-Za-z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAP = Pattern.compile(START + NUM + "\\s*([pPnNuUµμmM])F(?![A-Za-z])", Pattern.CASE_INSENSITIVE);
    /** Group 2 = prefix, group 3 = space between prefix and OHM (DigiKey: "1M OHM" mega, "10 MOHM" milli). */
    private static final Pattern RES = Pattern.compile(
            START + NUM + "\\s*(?:([kKmMgG])(\\s*))?(?:OHMS?|Ω)(?![A-Za-z])", Pattern.CASE_INSENSITIVE);
    /** "10K" with no OHM, only trusted in a resistor description. */
    private static final Pattern RES_BARE = Pattern.compile(START + NUM + "([kKM])(?![A-Za-z0-9])");
    private static final Pattern FREQ = Pattern.compile(START + NUM + "\\s*([kKmMgG]?)HZ(?![A-Za-z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern VOLT = Pattern.compile(
            START + "(\\d+(?:\\.\\d+)?)\\s*(K?V)(?:DC|AC)?(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOL = Pattern.compile("(±\\s*)?(?<![\\w.])(\\d+(?:\\.\\d+)?)\\s*%");

    private static final Pattern RESISTOR = Pattern.compile(
            "\\bRES\\b|RESISTOR|THICK FILM|THIN FILM|POTENTIOMETER|\\bPOT\\b|TRIMMER|THERMISTOR|\\bNTC\\b|FERRITE|BEAD",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_PASSIVE = Pattern.compile(
            "MOSFET|TRANSISTOR|\\bFET\\b|\\bIC\\b|REGULATOR|DIODE|\\bLED\\b|DRIVER|AMPLIFIER|SWITCH|OPTO|CONTROLLER|"
                    + "CONVERTER|MICROCONTROLLER|\\bMCU\\b|SENSOR",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CRYSTAL = Pattern.compile("CRYSTAL|\\bXTAL\\b|OSCILLATOR|RESONATOR|\\bOSC\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CHIP_SIZE = Pattern.compile(
            "(?<![\\w.])(01005|0201|0402|0603|0805|1008|1206|1210|1806|1812|2010|2220|2512)(?![\\w.])");
    private static final Pattern IC_PKG = Pattern.compile(
            "(?<![A-Za-z0-9])(HTSSOP|TSSOP|SSOP|MSOP|SOIC|SOP|TSOT|SOT|SOD|SC|TO|DO|VQFN|WQFN|UQFN|QFN|DFN|VSON|WSON|SON|"
                    + "LQFP|TQFP|QFP|BGA|LGA|PDIP|DIP|SIP|SMA|SMB|SMC)-?(\\d{1,3}[A-Z]{0,2}(?:-\\d{1,2}[A-Z]?)?)(?![A-Za-z0-9])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_PKG = Pattern.compile("(?<![A-Za-z0-9])(D2PAK|DPAK|SMA|SMB|SMC|PowerPAK)(?![A-Za-z0-9])",
            Pattern.CASE_INSENSITIVE);

    /** Specs found in descriptions (first text that gives a field wins), e.g. "RES 10K OHM 1% 1/10W 0603". */
    public static PartSpecs fromDescription(String... texts) {
        PartSpecs out = EMPTY;
        if (texts == null) return out;
        for (String t : texts) if (t != null && !t.trim().isEmpty()) out = out.orElse(fromOne(t));
        return out;
    }

    private static PartSpecs fromOne(String text) {
        return new PartSpecs(value(text), tolerance(text), voltage(text), pkg(text));
    }

    static String value(String text) {
        boolean caseKnown = !text.equals(text.toUpperCase(Locale.ROOT));
        if (CRYSTAL.matcher(text).find()) {
            Matcher f = FREQ.matcher(text);
            if (f.find()) {
                double mult = prefix(f.group(2), true, false);
                return format(num(f.group(1)) * mult, 'Z');
            }
            return "";
        }
        Matcher m = IND.matcher(text);
        if (m.find()) return format(num(m.group(1)) * prefix(m.group(2), false, true), 'H');
        m = CAP.matcher(text);
        if (m.find()) return format(num(m.group(1)) * prefix(m.group(2), false, true), 'F');
        boolean resistor = RESISTOR.matcher(text).find();
        if (!resistor && NOT_PASSIVE.matcher(text).find()) return "";
        m = RES.matcher(text);
        if (m.find()) {
            String p = m.group(2);
            double mult;
            if (p == null) {
                mult = 1;
            } else if (p.equals("M") && !caseKnown) {
                // All capitals (DigiKey): "1M OHM" is mega, "10 MOHM" is milli.
                mult = m.group(3) != null && !m.group(3).isEmpty() ? 1e6 : 1e-3;
            } else {
                mult = prefix(p, false, false);
            }
            return format(num(m.group(1)) * mult, 'R');
        }
        if (resistor) {
            m = RES_BARE.matcher(text);
            if (m.find()) return format(num(m.group(1)) * prefix(m.group(2), true, false), 'R');
        }
        return "";
    }

    static String tolerance(String text) {
        Matcher m = TOL.matcher(text);
        if (!m.find()) return "";
        return plain(num(m.group(2))) + "%";
    }

    static String voltage(String text) {
        Matcher m = VOLT.matcher(text);
        if (!m.find()) return "";
        boolean kilo = m.group(2).length() > 1;
        return plain(num(m.group(1))) + (kilo ? "kV" : "V");
    }

    static String pkg(String text) {
        Matcher m = CHIP_SIZE.matcher(text);
        int best = Integer.MAX_VALUE;
        String found = "";
        if (m.find()) {
            best = m.start();
            found = m.group(1);
        }
        m = IC_PKG.matcher(text);
        if (m.find() && m.start() < best) {
            best = m.start();
            found = m.group(1).toUpperCase(Locale.ROOT) + "-" + m.group(2).toUpperCase(Locale.ROOT);
        }
        m = NAMED_PKG.matcher(text);
        if (m.find() && m.start() < best) {
            String g = m.group(1);
            found = g.equalsIgnoreCase("PowerPAK") ? "PowerPAK" : g.toUpperCase(Locale.ROOT);
        }
        return found;
    }

    /**
     * Specs from a distributor's attribute list (name → value), e.g. Mouser's "Resistance" = "10 kOhms",
     * "Package / Case" = "0603 (1608 metric)". Missing attributes stay blank.
     */
    public static PartSpecs fromAttributes(Map<String, String> attrs) {
        if (attrs == null || attrs.isEmpty()) return EMPTY;
        String value = "", tol = "", volt = "", pkg = "";
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            String name = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (v.isEmpty() || v.equals("-")) continue;
            if (value.isEmpty() && (name.equals("resistance") || name.equals("capacitance") || name.equals("inductance")
                    || name.equals("frequency"))) {
                char unit = name.equals("resistance") ? 'R' : name.equals("capacitance") ? 'F'
                        : name.equals("inductance") ? 'H' : 'Z';
                Value parsed = parseValue(v.replaceAll("(?i)\\s*(ohms?|Ω|farads?|henr(y|ies)|hz)\\s*$", "")
                        .replace(" ", ""));
                if (parsed != null) value = format(parsed.number, unit);
            } else if (tol.isEmpty() && name.equals("tolerance")) {
                tol = tolerance(v);
            } else if (volt.isEmpty() && name.startsWith("voltage rating")) {
                volt = voltage(v);
            } else if (pkg.isEmpty() && (name.equals("package / case") || name.equals("case code - in")
                    || name.equals("package"))) {
                pkg = pkg(v);
                if (pkg.isEmpty() && v.length() <= 16) pkg = v.toUpperCase(Locale.ROOT);
            }
        }
        return new PartSpecs(value, tol, volt, pkg);
    }

    // ---------------------------------------------------------------- values

    /** A parsed value; [unit] is 'R' (ohm), 'F', 'H', 'Z' (hertz) or 0 when the text gave no unit. */
    public static final class Value {
        public final double number;
        public final char unit;

        Value(double number, char unit) {
            this.number = number;
            this.unit = unit;
        }

        /** Plain number with no prefix or unit (e.g. "10000"). */
        boolean plain;
    }

    private static final Pattern VALUE = Pattern.compile(
            "^(\\d+(?:[.,]\\d+)?|[.,]\\d+)(meg|MEG|[pPnNuUµμmkKMG])?((?i:ohms?|Ω|r|f|h|hz))?$");
    private static final Pattern RKM = Pattern.compile("^(\\d+)([rRkKmMuUnNpPµμ])(\\d+)$");

    /**
     * Reads a value typed in search or stored on a part: 10k, 10K, 10kΩ, 10kohm, 10000, 4k7, 4R7, 0.1uF, 100nF, 100n,
     * 4.7µH, 16MHz. Returns null when it isn't a value. "0603" is a package, not 603.
     */
    public static Value parseValue(String s) {
        if (s == null) return null;
        String t = s.trim().replace(" ", "");
        if (t.isEmpty()) return null;
        Matcher r = RKM.matcher(t);
        if (r.matches()) {
            char c = r.group(2).charAt(0);
            double n = Double.parseDouble(r.group(1) + "." + r.group(3));
            boolean ohm = c == 'r' || c == 'R';
            return new Value(n * (ohm ? 1 : prefix(String.valueOf(c), false, false)), ohm ? 'R' : 0);
        }
        Matcher m = VALUE.matcher(t);
        if (!m.matches()) return null;
        String n = m.group(1);
        String p = m.group(2);
        String u = m.group(3);
        if (p == null && u == null && n.length() > 1 && n.charAt(0) == '0' && Character.isDigit(n.charAt(1))) {
            return null; // 0603, 0402: package codes
        }
        char unit = 0;
        if (u != null) {
            String lu = u.toLowerCase(Locale.ROOT);
            if (lu.startsWith("ohm") || lu.equals("ω") || lu.equals("r")) unit = 'R';
            else if (lu.equals("f")) unit = 'F';
            else if (lu.equals("h")) unit = 'H';
            else if (lu.equals("hz")) unit = 'Z';
        }
        if (unit == 'R' && "r".equalsIgnoreCase(u) && p != null) return null; // "10kr" isn't a thing
        double mult = p == null ? 1 : prefix(p, unit == 'Z', unit == 'F' || unit == 'H');
        Value v = new Value(num(n) * mult, unit);
        v.plain = p == null && u == null;
        return v;
    }

    /** Same value within rounding; a unit only has to agree when both sides have one. */
    public static boolean sameValue(Value a, Value b) {
        if (a == null || b == null) return false;
        if (a.unit != 0 && b.unit != 0 && a.unit != b.unit) return false;
        double x = a.number, y = b.number;
        if (x == y) return true;
        double big = Math.max(Math.abs(x), Math.abs(y));
        return big > 0 && Math.abs(x - y) / big < 1e-6;
    }

    /**
     * SI prefix multiplier. 'm' is milli and 'M' mega, except: for hertz any m/M is mega, and for farads and henries
     * any m/M is milli (labels write "MF"/"MH" in capitals).
     */
    static double prefix(String p, boolean hertz, boolean faradOrHenry) {
        if (p == null || p.isEmpty()) return 1;
        switch (p) {
            case "p": case "P": return 1e-12;
            case "n": case "N": return 1e-9;
            case "u": case "U": case "µ": case "μ": return 1e-6;
            case "k": case "K": return 1e3;
            case "G": case "g": return 1e9;
            case "meg": case "MEG": return 1e6;
            case "m": return hertz ? 1e6 : 1e-3;
            case "M": return faradOrHenry ? 1e-3 : 1e6;
            default: return 1;
        }
    }

    private static double num(String s) {
        String t = s.replace(',', '.');
        if (t.startsWith(".")) t = "0" + t;
        return Double.parseDouble(t);
    }

    private static String plain(double d) {
        return new BigDecimal(d).round(new MathContext(4)).stripTrailingZeros().toPlainString();
    }

    /** 10000 'R' → "10kΩ", 1e-7 'F' → "100nF", 4.7e-6 'H' → "4.7µH", 1.6e7 'Z' → "16MHz". */
    public static String format(double v, char unit) {
        String sym;
        String[] prefixes;
        double[] scales;
        switch (unit) {
            case 'R':
                sym = "Ω";
                prefixes = new String[] {"m", "", "k", "M", "G"};
                scales = new double[] {1e-3, 1, 1e3, 1e6, 1e9};
                break;
            case 'F':
                sym = "F";
                prefixes = new String[] {"p", "n", "µ", "m", ""};
                scales = new double[] {1e-12, 1e-9, 1e-6, 1e-3, 1};
                break;
            case 'H':
                sym = "H";
                prefixes = new String[] {"n", "µ", "m", ""};
                scales = new double[] {1e-9, 1e-6, 1e-3, 1};
                break;
            case 'Z':
                sym = "Hz";
                prefixes = new String[] {"", "k", "M", "G"};
                scales = new double[] {1, 1e3, 1e6, 1e9};
                break;
            default:
                return plain(v);
        }
        if (v == 0) return "0" + sym;
        int i = 0;
        // Largest scale that leaves a number of at least 1 (999.9nF stays nF, 1000nF becomes 1µF).
        for (int k = 0; k < scales.length; k++) if (Math.abs(v) >= scales[k] * 0.99999) i = k;
        return plain(v / scales[i]) + prefixes[i] + sym;
    }

    // ---------------------------------------------------------------- search

    /** Uppercase, µ → U, Ω → OHM, and spaces, hyphens and underscores dropped, so SOT23 finds SOT-23. */
    public static String norm(String s) {
        if (s == null) return "";
        return s.toUpperCase(Locale.ROOT).replace("µ", "U").replace("Μ", "U").replace("Ω", "OHM")
                .replaceAll("[\\s\\-_]+", "");
    }

    /**
     * True when the part matches the search [query]: the whole query appears in one of the fields, or every word of
     * it matches. A word that is a value (10k, 0.1uF, 4k7) matches the part's value numerically when the part has one;
     * "1%" matches the tolerance exactly; anything else is a text match on the fields and specs.
     */
    public static boolean matches(String query, List<String> fields, PartSpecs specs) {
        if (specs == null) specs = EMPTY;
        String whole = norm(query);
        if (whole.isEmpty()) return true;
        List<String> all = new ArrayList<>();
        if (fields != null) for (String f : fields) all.add(norm(f));
        for (String c : specs.chips()) all.add(norm(c));
        String[] words = query.trim().split("\\s+");
        // Several words typed as one phrase ("LT8609 AIMSE"). A single word goes through the word rules, so that
        // "10k" doesn't find a 110k part by its text.
        if (words.length > 1) for (String f : all) if (f.contains(whole)) return true;
        Value partValue = parseValue(specs.value);
        for (String w : words) {
            if (!wordMatches(w, all, specs, partValue)) return false;
        }
        return true;
    }

    private static boolean wordMatches(String w, List<String> all, PartSpecs specs, Value partValue) {
        String t = w.trim();
        if (t.endsWith("%") && !specs.tolerance.isEmpty()) {
            Value a = parseValue(t.substring(0, t.length() - 1));
            Value b = parseValue(specs.tolerance.replace("%", ""));
            if (a != null && b != null) return sameValue(a, b);
        }
        Value v = parseValue(t);
        if (v != null && partValue != null) {
            if (sameValue(v, partValue)) return true;
            if (!v.plain) return false; // "10k" must not match a 110k part through its text
        }
        String n = norm(t);
        if (n.isEmpty()) return true;
        for (String f : all) if (f.contains(n)) return true;
        return false;
    }
}
