package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Data prep for the small charts in Charts.kt (pure Java, unit tested): scaling a series into a box, top-N + "Other"
 * slices for a donut, finding the slice under a tap, and the spoken summary TalkBack reads.
 */
public final class ChartData {
    private ChartData() {}

    /** The value range to draw: {min, max} of the values (and [extra], e.g. a minimum line), widened when flat. */
    public static double[] range(double[] values, Double extra) {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            if (Double.isNaN(v)) continue;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        if (extra != null && !Double.isNaN(extra)) {
            lo = Math.min(lo, extra);
            hi = Math.max(hi, extra);
        }
        if (lo == Double.POSITIVE_INFINITY) return new double[] {0, 1};
        if (hi - lo < 1e-9) {
            // A flat line sits in the middle of the box.
            double pad = Math.max(1, Math.abs(hi) * 0.1);
            return new double[] {lo - pad, hi + pad};
        }
        return new double[] {lo, hi};
    }

    /**
     * Points for a line chart in a [width] × [height] box (y grows downwards), [pad] pixels kept free on every side so
     * the end dot isn't cut off. Returns {x0, y0, x1, y1, …}. One value is drawn in the middle.
     */
    public static float[] scale(double[] values, double lo, double hi, float width, float height, float pad) {
        int n = values.length;
        float[] out = new float[n * 2];
        float w = Math.max(0, width - 2 * pad), h = Math.max(0, height - 2 * pad);
        double span = hi - lo == 0 ? 1 : hi - lo;
        for (int i = 0; i < n; i++) {
            out[2 * i] = pad + (n == 1 ? w / 2f : w * i / (n - 1f));
            out[2 * i + 1] = y(values[i], lo, span, h, pad);
        }
        return out;
    }

    /** The y position of [value] in the same box as [scale] (for a minimum line). */
    public static float yOf(double value, double lo, double hi, float height, float pad) {
        double span = hi - lo == 0 ? 1 : hi - lo;
        return y(value, lo, span, Math.max(0, height - 2 * pad), pad);
    }

    private static float y(double v, double lo, double span, float h, float pad) {
        double t = (v - lo) / span;
        t = Math.max(0, Math.min(1, t));
        return (float) (pad + h * (1 - t));
    }

    /** One donut slice: [index] is the position in the input (-1 for "Other"). */
    public static final class Slice {
        public final String label;
        public final double value;
        public final double fraction;
        public final int index;

        Slice(String label, double value, double fraction, int index) {
            this.label = label;
            this.value = value;
            this.fraction = fraction;
            this.index = index;
        }

        @Override
        public String toString() {
            return label + "=" + value;
        }
    }

    /**
     * The [n] biggest positive values, largest first (ties keep input order), with the rest summed into one
     * [otherLabel] slice at the end. Zero and negative values are left out. Fractions add up to 1.
     */
    public static List<Slice> topN(List<String> labels, double[] values, int n, String otherLabel) {
        List<Integer> order = new ArrayList<>();
        double total = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] > 0 && !Double.isNaN(values[i])) {
                order.add(i);
                total += values[i];
            }
        }
        List<Slice> out = new ArrayList<>();
        if (total <= 0) return out;
        // Stable sort, largest first.
        Collections.sort(order, (a, b) -> Double.compare(values[b], values[a]));
        // When only one would go into "Other", show it instead.
        int keep = order.size() <= n + 1 ? order.size() : n;
        double rest = 0;
        for (int k = 0; k < order.size(); k++) {
            int i = order.get(k);
            if (k < keep) out.add(new Slice(labels.get(i), values[i], values[i] / total, i));
            else rest += values[i];
        }
        if (rest > 0) out.add(new Slice(otherLabel, rest, rest / total, -1));
        return out;
    }

    /**
     * The slice under a tap at ([x], [y]) relative to the donut's center, slices drawn clockwise from 12 o'clock.
     * -1 when the tap is in the hole (radius below [inner]) or outside ([outer]).
     */
    public static int sliceAt(double[] fractions, double x, double y, double inner, double outer) {
        double r = Math.hypot(x, y);
        if (r < inner || r > outer || fractions.length == 0) return -1;
        // 0° at 12 o'clock, growing clockwise (screen y points down).
        double deg = Math.toDegrees(Math.atan2(x, -y));
        if (deg < 0) deg += 360;
        double t = deg / 360, acc = 0;
        for (int i = 0; i < fractions.length; i++) {
            acc += fractions[i];
            if (t < acc) return i;
        }
        return fractions.length - 1;
    }

    /** "12", "1.5", "0.25": a short number for spoken summaries. */
    public static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.valueOf((long) Math.rint(v));
        String s = String.format(java.util.Locale.US, "%.2f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** "Stock over the last 90 days, from 40 to 12, lowest 8" (lowest only when below both ends). */
    public static String describeSeries(String what, double[] values) {
        if (values.length == 0) return what + ": no data";
        double first = values[0], last = values[values.length - 1], low = first;
        for (double v : values) low = Math.min(low, v);
        String s = what + ", from " + num(first) + " to " + num(last);
        if (low < first && low < last) s += ", lowest " + num(low);
        return s;
    }

    /** "Resistors 52%, Capacitors 30%, Other 18%": the donut read out, largest first. */
    public static String describeSlices(String what, List<Slice> slices) {
        if (slices.isEmpty()) return what + ": no data";
        StringBuilder b = new StringBuilder(what).append(": ");
        for (int i = 0; i < slices.size(); i++) {
            if (i > 0) b.append(", ");
            b.append(slices.get(i).label).append(' ').append(percent(slices.get(i).fraction));
        }
        return b.toString();
    }

    /** "52%", "<1%" for a sliver that isn't zero. */
    public static String percent(double fraction) {
        if (fraction > 0 && fraction < 0.005) return "<1%";
        return Math.round(fraction * 100) + "%";
    }
}
