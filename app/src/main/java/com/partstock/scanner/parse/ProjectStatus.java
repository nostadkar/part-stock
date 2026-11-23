package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What a project card on the Projects list shows, from a {@link BuildPlan} at 1 board: BOM lines by coverage (the
 * segmented bar), the parts that block one board, a state (ready ×N / missing N parts / needs matching) and the sort
 * orders of the list.
 */
public final class ProjectStatus {
    private ProjectStatus() {}

    public enum State {
        /** Every line matched and in stock for at least one board. */
        READY,
        /** Every line matched, but some parts are short for one board. */
        MISSING,
        /** Some lines have no stock part matched yet. */
        NEEDS_MATCHING,
        /** The BOM has no lines. */
        EMPTY,
    }

    /** A part (or unmatched line) that stops one board. [category] is a Categories bucket (for its icon). */
    public static final class Blocker {
        public String label = "";
        public String category = Categories.OTHER;
        public int missing;
        public boolean noMatch;
    }

    public static final class Summary {
        public int lines, covered, partial, none, noMatch;
        /** Boards the stock supports now. */
        public int buildable;
        public State state = State.EMPTY;
        /** Biggest shortage first. */
        public final List<Blocker> blocking = new ArrayList<>();

        /** The state chip's text: "Ready to build ×3", "Missing 3 parts", "Needs matching (2 lines)". */
        public String stateText() {
            switch (state) {
                case READY:
                    return "Ready to build ×" + buildable;
                case MISSING:
                    return "Missing " + blocking.size() + " part" + (blocking.size() == 1 ? "" : "s");
                case NEEDS_MATCHING:
                    return "Needs matching (" + noMatch + " line" + (noMatch == 1 ? ")" : "s)");
                default:
                    return "Empty BOM";
            }
        }

        /** Lines covered for one board, 0…1 (0 for an empty BOM). */
        public float coveredFraction() {
            return lines == 0 ? 0f : covered / (float) lines;
        }

        /** For TalkBack: the bar read out as counts. */
        public String barDescription() {
            StringBuilder sb = new StringBuilder();
            sb.append(covered).append(" of ").append(lines).append(lines == 1 ? " line" : " lines").append(" covered");
            if (partial > 0) sb.append(", ").append(partial).append(" partial");
            if (none > 0) sb.append(", ").append(none).append(" none in stock");
            if (noMatch > 0) sb.append(", ").append(noMatch).append(" no match");
            return sb.toString();
        }
    }

    /**
     * The summary of [plan] (made for 1 board). A stock part's category comes from {@link BuildPlan.Stock#category}
     * (else its description); unmatched lines are classified from their description.
     */
    public static Summary of(BuildPlan.Result plan) {
        Summary s = new Summary();
        s.lines = plan.lines.size();
        for (BuildPlan.LineResult lr : plan.lines) {
            switch (lr.status) {
                case COVERED: s.covered++; break;
                case PARTIAL: s.partial++; break;
                case NONE: s.none++; break;
                default: s.noMatch++; break;
            }
        }
        s.buildable = plan.buildable;
        for (BuildPlan.Shortage sh : plan.topShortages(Integer.MAX_VALUE)) {
            Blocker b = new Blocker();
            b.label = sh.label == null ? "" : sh.label;
            b.missing = sh.missing;
            b.noMatch = sh.part == null;
            String cat = sh.part != null ? sh.part.category : null;
            if (cat == null || cat.trim().isEmpty()) {
                cat = sh.part != null ? Categories.classify(sh.part.description, sh.req == null ? "" : sh.req.description)
                        : Categories.classify(sh.req == null ? "" : sh.req.description, b.label);
            }
            b.category = cat;
            s.blocking.add(b);
        }
        if (s.lines == 0) s.state = State.EMPTY;
        else if (s.noMatch > 0) s.state = State.NEEDS_MATCHING;
        else if (plan.buildable > 0) s.state = State.READY;
        else s.state = State.MISSING;
        return s;
    }

    public enum Sort {
        NAME("Name"),
        CLOSEST("Closest to buildable"),
        RECENT("Most recently changed");

        public final String label;

        Sort(String label) {
            this.label = label;
        }

        public static Sort parse(String name) {
            for (Sort s : values()) if (s.name().equals(name)) return s;
            return RECENT;
        }
    }

    /** A project row to sort: its name, last-changed stamp ("yyyy-MM-dd HH:mm:ss" sorts as text) and summary. */
    public static final class Row<T> {
        public final T item;
        public final String name;
        public final String updatedAt;
        public final Summary summary;

        public Row(T item, String name, String updatedAt, Summary summary) {
            this.item = item;
            this.name = name == null ? "" : name;
            this.updatedAt = updatedAt == null ? "" : updatedAt;
            this.summary = summary;
        }
    }

    /**
     * [rows] in [sort] order. Closest to buildable: ready ones first (most boards first), then the fewest blocking
     * parts, then the most lines covered; empty BOMs last; ties by name.
     */
    public static <T> List<Row<T>> sorted(List<Row<T>> rows, Sort sort) {
        List<Row<T>> out = new ArrayList<>(rows);
        Comparator<Row<T>> byName = (a, b) -> {
            int c = a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT));
            return c != 0 ? c : a.name.compareTo(b.name);
        };
        Comparator<Row<T>> cmp;
        switch (sort) {
            case NAME:
                cmp = byName;
                break;
            case CLOSEST:
                cmp = (a, b) -> {
                    Summary x = a.summary, y = b.summary;
                    int c = Boolean.compare(x.state == State.EMPTY, y.state == State.EMPTY);
                    if (c != 0) return c;
                    c = Integer.compare(y.buildable, x.buildable);
                    if (c != 0) return c;
                    c = Integer.compare(x.blocking.size(), y.blocking.size());
                    if (c != 0) return c;
                    c = Float.compare(y.coveredFraction(), x.coveredFraction());
                    if (c != 0) return c;
                    return byName.compare(a, b);
                };
                break;
            default:
                cmp = (a, b) -> b.updatedAt.compareTo(a.updatedAt); // stable: ties keep the database order
                break;
        }
        out.sort(cmp);
        return out;
    }
}
