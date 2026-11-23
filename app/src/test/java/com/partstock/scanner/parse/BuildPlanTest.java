package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class BuildPlanTest {

    private static BuildPlan.Stock stock(long id, String mpn, int onHand) {
        BuildPlan.Stock s = new BuildPlan.Stock();
        s.id = id;
        s.mpn = mpn;
        s.onHand = onHand;
        return s;
    }

    private static BuildPlan.Req req(String mpn, int perBoard) {
        BuildPlan.Req r = new BuildPlan.Req();
        r.mpn = mpn;
        r.label = mpn;
        r.perBoard = perBoard;
        return r;
    }

    /** R1 10/board (have 100 → 10 boards), C1 2/board (have 7 → 3), U1 1/board (have 0), X1 no match. */
    private static BuildPlan.Result plan(int boards, boolean withUnmatched) {
        List<BuildPlan.Req> reqs = new ArrayList<>(Arrays.asList(req("R1", 10), req("C1", 2), req("U1", 1)));
        if (withUnmatched) reqs.add(req("X1", 4));
        List<BuildPlan.Stock> stock = Arrays.asList(stock(1, "R1", 100), stock(2, "C1", 7), stock(3, "U1", 0));
        return BuildPlan.plan(reqs, stock, boards);
    }

    @Test
    public void coverageAndStatus() {
        assertEquals(1f, BuildPlan.coverage(5, 0), 0);
        assertEquals(0.5f, BuildPlan.coverage(5, 10), 1e-6);
        assertEquals(1f, BuildPlan.coverage(50, 10), 0);
        assertEquals(0f, BuildPlan.coverage(-3, 10), 0);
        assertEquals(BuildPlan.Coverage.COVERED, BuildPlan.status(10, 10));
        assertEquals(BuildPlan.Coverage.COVERED, BuildPlan.status(0, 0));
        assertEquals(BuildPlan.Coverage.PARTIAL, BuildPlan.status(1, 10));
        assertEquals(BuildPlan.Coverage.NONE, BuildPlan.status(0, 10));
    }

    @Test
    public void perLineCoverage() {
        BuildPlan.Result r = plan(5, true);
        BuildPlan.LineResult r1 = r.lines.get(0), c1 = r.lines.get(1), u1 = r.lines.get(2), x1 = r.lines.get(3);
        assertEquals(BuildPlan.Coverage.COVERED, r1.status);
        assertEquals(1f, r1.coverage, 0);
        assertEquals(10, r1.boardsSupported);
        assertEquals(BuildPlan.Coverage.PARTIAL, c1.status);
        assertEquals(0.7f, c1.coverage, 1e-6);
        assertEquals(7, c1.have);
        assertEquals(10, c1.partNeed);
        assertEquals(3, c1.boardsSupported);
        assertEquals(BuildPlan.Coverage.NONE, u1.status);
        assertEquals(0, u1.boardsSupported);
        assertEquals(BuildPlan.Coverage.NO_MATCH, x1.status);
        assertEquals(-1, x1.boardsSupported);
        assertTrue(x1.limiting);
        assertTrue(u1.limiting);
        assertFalse(c1.limiting);
        assertFalse(r1.limiting);
    }

    @Test
    public void worstFirstPutsLimitingOnTop() {
        BuildPlan.Result r = plan(5, true);
        assertEquals(Arrays.asList(3, 2, 1, 0), BuildPlan.worstFirst(r.lines));
        BuildPlan.Result noX = plan(5, false);
        assertEquals(0, noX.buildable);
        assertEquals(Arrays.asList(2, 1, 0), BuildPlan.worstFirst(noX.lines));
    }

    @Test
    public void sharedPartCoverageUsesCombinedNeed() {
        // Two lines use the same part: 3 + 2 per board, 12 in stock → 2 boards; for 4 boards need 20.
        List<BuildPlan.Req> reqs = Arrays.asList(req("R1", 3), req("R1", 2));
        BuildPlan.Result r = BuildPlan.plan(reqs, Arrays.asList(stock(1, "R1", 12)), 4);
        assertEquals(2, r.buildable);
        for (BuildPlan.LineResult lr : r.lines) {
            assertEquals(20, lr.partNeed);
            assertEquals(0.6f, lr.coverage, 1e-6);
            assertEquals(2, lr.boardsSupported);
            assertTrue(lr.limiting);
            assertEquals(BuildPlan.Coverage.PARTIAL, lr.status);
        }
        assertEquals(12, r.lines.get(0).need);
    }

    @Test
    public void zeroBoardsIsCovered() {
        BuildPlan.Result r = plan(0, false);
        for (BuildPlan.LineResult lr : r.lines) assertEquals(BuildPlan.Coverage.COVERED, lr.status);
        assertTrue(r.shortages.isEmpty());
    }

    @Test
    public void topShortagesBiggestFirstAndLineOf() {
        BuildPlan.Result r = plan(5, true);
        // C1 short 3, U1 short 5, X1 short 20.
        List<BuildPlan.Shortage> top = r.topShortages(5);
        assertEquals(3, top.size());
        assertEquals("X1", top.get(0).label);
        assertEquals(20, top.get(0).missing);
        assertEquals("U1", top.get(1).label);
        assertEquals("C1", top.get(2).label);
        assertEquals(2, r.topShortages(2).size());
        assertEquals(3, r.lineOf(top.get(0)));
        assertEquals(1, r.lineOf(top.get(2)));
        // The original list keeps its order.
        assertEquals("C1", r.shortages.get(0).label);
    }
}
