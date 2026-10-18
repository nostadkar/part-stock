package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Project / BOM maths: matches BOM lines to stock (a part picked by hand, else MPN, then customer PN, then
 * distributor PN), works out how many boards the stock supports and what is short for N boards. Two BOM lines that
 * use the same stock part add up. A line with no match can't be built and is short by its full need.
 */
public final class BuildPlan {
    private BuildPlan() {}

    /** One part in stock. */
    public static final class Stock {
        public long id;
        public String mpn = "", customerPn = "", distributorPn = "", distributor = "", manufacturer = "",
                description = "", link = "";
        public int onHand;
        public Double unitCost;
    }

    /** One BOM line. {@link #pickedId} is a stock part chosen by hand (null = match automatically). */
    public static final class Req {
        public String mpn = "", customerPn = "", distributorPn = "", manufacturer = "", description = "", label = "";
        public int perBoard;
        public Long pickedId;

        public static Req of(BomCsv.Line l) {
            Req r = new Req();
            r.mpn = l.mpn;
            r.customerPn = l.customerPn;
            r.distributorPn = l.distributorPn;
            r.manufacturer = l.manufacturer;
            r.description = l.description.isEmpty() ? (l.value + " " + l.shortFootprint()).trim() : l.description;
            r.label = l.label();
            r.perBoard = l.perBoard;
            return r;
        }
    }

    public static final class LineResult {
        public Req req;
        /** Null when nothing in stock matches. */
        public Stock part;
        /** "picked", "MPN", "customer PN", "distributor PN", or null. */
        public String matchedOn;
        /** perBoard × boards. */
        public int need;
    }

    /** What's short for the planned number of boards, per stock part (or per unmatched line). */
    public static final class Shortage {
        public String label;
        public Stock part; // null for an unmatched line
        public Req req; // the first BOM line using it
        public int need, onHand, missing, orderQty;
    }

    public static final class Result {
        /** Boards the stock supports (0 when a line has no match). */
        public int buildable;
        public int boards;
        public final List<LineResult> lines = new ArrayList<>();
        /** Parts (or unmatched lines) that stop one more board: their stock runs out first. */
        public final List<String> limiting = new ArrayList<>();
        public final List<Shortage> shortages = new ArrayList<>();
        /** Stock part id → total pieces used for [boards] boards (what "Build N" subtracts). */
        public final Map<Long, Integer> use = new LinkedHashMap<>();
        public int unmatched;

        /** The shortage list as the reorder CSV (Mouser Part Number, Quantity, …; Minimum = needed for N boards). */
        public String reorderCsv() {
            List<ReorderList.Item> items = new ArrayList<>();
            for (Shortage s : shortages) {
                ReorderList.Item it = new ReorderList.Item();
                if (s.part != null) {
                    it.distributor = s.part.distributor;
                    it.distributorPn = s.part.distributorPn;
                    it.mpn = s.part.mpn.isEmpty() ? s.req.mpn : s.part.mpn;
                    it.manufacturer = s.part.manufacturer;
                    it.description = s.part.description;
                    it.link = s.part.link;
                    it.unitCost = s.part.unitCost;
                } else {
                    it.distributorPn = s.req.distributorPn;
                    it.mpn = s.req.mpn.isEmpty() ? s.req.label : s.req.mpn;
                    it.manufacturer = s.req.manufacturer;
                    it.description = s.req.description;
                }
                it.onHand = s.onHand;
                it.minimum = s.need;
                items.add(it);
            }
            return ReorderList.csv(items);
        }
    }

    /** A stock part and what it matched on. */
    public static final class Match {
        public final Stock part;
        public final String on;

        Match(Stock part, String on) {
            this.part = part;
            this.on = on;
        }
    }

    /** The stock part for a BOM line, and what it matched on; null when none. */
    public static Match match(Req r, List<Stock> stock) {
        if (r.pickedId != null) {
            for (Stock s : stock) if (s.id == r.pickedId) return new Match(s, "picked");
        }
        String[][] keys = {{r.mpn, "MPN"}, {r.customerPn, "customer PN"}, {r.distributorPn, "distributor PN"}};
        for (int k = 0; k < keys.length; k++) {
            String want = norm(keys[k][0]);
            if (want.isEmpty()) continue;
            for (Stock s : stock) {
                String have = norm(k == 0 ? s.mpn : k == 1 ? s.customerPn : s.distributorPn);
                if (want.equals(have)) return new Match(s, keys[k][1]);
            }
        }
        return null;
    }

    public static Result plan(List<Req> reqs, List<Stock> stock, int boards) {
        Result res = new Result();
        res.boards = Math.max(0, boards);
        Map<Long, Integer> perBoard = new LinkedHashMap<>();
        Map<Long, Stock> byId = new LinkedHashMap<>();
        Map<Long, Req> firstReq = new LinkedHashMap<>();
        List<Req> unmatched = new ArrayList<>();
        for (Req r : reqs) {
            LineResult lr = new LineResult();
            lr.req = r;
            lr.need = r.perBoard * res.boards;
            Match m = match(r, stock);
            if (m != null) {
                lr.part = m.part;
                lr.matchedOn = m.on;
                perBoard.merge(lr.part.id, r.perBoard, Integer::sum);
                byId.put(lr.part.id, lr.part);
                firstReq.putIfAbsent(lr.part.id, r);
            } else {
                unmatched.add(r);
            }
            res.lines.add(lr);
        }
        res.unmatched = unmatched.size();

        int buildable = Integer.MAX_VALUE;
        for (Map.Entry<Long, Integer> e : perBoard.entrySet()) {
            int per = e.getValue();
            if (per <= 0) continue;
            buildable = Math.min(buildable, Math.max(0, byId.get(e.getKey()).onHand) / per);
        }
        if (reqs.isEmpty() || !unmatched.isEmpty() || buildable == Integer.MAX_VALUE) buildable = 0;
        res.buildable = buildable;

        for (Map.Entry<Long, Integer> e : perBoard.entrySet()) {
            Stock s = byId.get(e.getKey());
            int per = e.getValue();
            if (per > 0 && Math.max(0, s.onHand) / per == res.buildable) res.limiting.add(stockLabel(s));
        }
        for (Req r : unmatched) res.limiting.add(r.label + " (no match)");

        for (Map.Entry<Long, Integer> e : perBoard.entrySet()) {
            Stock s = byId.get(e.getKey());
            int need = e.getValue() * res.boards;
            res.use.put(s.id, need);
            int missing = Math.max(0, need - Math.max(0, s.onHand));
            if (missing > 0) res.shortages.add(shortage(stockLabel(s), s, firstReq.get(s.id), need, s.onHand, missing));
        }
        for (Req r : unmatched) {
            int need = r.perBoard * res.boards;
            if (need > 0) res.shortages.add(shortage(r.label, null, r, need, 0, need));
        }
        return res;
    }

    private static Shortage shortage(String label, Stock s, Req r, int need, int onHand, int missing) {
        Shortage sh = new Shortage();
        sh.label = label;
        sh.part = s;
        sh.req = r;
        sh.need = need;
        sh.onHand = onHand;
        sh.missing = missing;
        sh.orderQty = ReorderList.roundUp(missing);
        return sh;
    }

    static String stockLabel(Stock s) {
        if (!s.mpn.isEmpty()) return s.mpn;
        if (!s.customerPn.isEmpty()) return s.customerPn;
        return s.distributorPn;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toUpperCase(Locale.US).replaceAll("\\s+", "");
    }
}
