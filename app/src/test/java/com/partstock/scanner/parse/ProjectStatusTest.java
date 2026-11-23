package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ProjectStatusTest {

    private static BuildPlan.Stock stock(long id, String mpn, int onHand, String category) {
        BuildPlan.Stock s = new BuildPlan.Stock();
        s.id = id;
        s.mpn = mpn;
        s.onHand = onHand;
        s.category = category;
        return s;
    }

    private static BuildPlan.Req req(String mpn, int perBoard) {
        BuildPlan.Req r = new BuildPlan.Req();
        r.mpn = mpn;
        r.label = mpn;
        r.perBoard = perBoard;
        return r;
    }

    private static final List<BuildPlan.Stock> STOCK = Arrays.asList(
            stock(1, "R1", 100, Categories.RESISTORS), stock(2, "C1", 1, Categories.CAPACITORS),
            stock(3, "U1", 0, ""), stock(4, "D1", 5, Categories.DIODES));

    private static ProjectStatus.Summary summary(BuildPlan.Req... reqs) {
        return ProjectStatus.of(BuildPlan.plan(Arrays.asList(reqs), STOCK, 1));
    }

    @Test
    public void countsLinesByCoverage() {
        ProjectStatus.Summary s = summary(req("R1", 10), req("C1", 2), req("U1", 1), req("X9", 1));
        assertEquals(4, s.lines);
        assertEquals(1, s.covered);
        assertEquals(1, s.partial);
        assertEquals(1, s.none);
        assertEquals(1, s.noMatch);
        assertEquals(ProjectStatus.State.NEEDS_MATCHING, s.state);
        assertEquals("Needs matching (1 line)", s.stateText());
        assertEquals("1 of 4 lines covered, 1 partial, 1 none in stock, 1 no match", s.barDescription());
    }

    @Test
    public void blockersBiggestFirstWithCategories() {
        ProjectStatus.Summary s = summary(req("R1", 10), req("C1", 2), req("U1", 3), req("X9", 1));
        assertEquals(3, s.blocking.size());
        assertEquals("U1", s.blocking.get(0).label);
        assertEquals(3, s.blocking.get(0).missing);
        assertEquals(Categories.OTHER, s.blocking.get(0).category); // blank → classified from description
        assertEquals("C1", s.blocking.get(1).label);
        assertEquals(Categories.CAPACITORS, s.blocking.get(1).category);
        assertTrue(s.blocking.get(2).noMatch);
    }

    @Test
    public void readyAndMissingStates() {
        ProjectStatus.Summary ready = summary(req("R1", 10), req("D1", 1));
        assertEquals(ProjectStatus.State.READY, ready.state);
        assertEquals(5, ready.buildable);
        assertEquals("Ready to build ×5", ready.stateText());
        assertEquals(0, ready.blocking.size());

        ProjectStatus.Summary missing = summary(req("R1", 10), req("C1", 2), req("U1", 1));
        assertEquals(ProjectStatus.State.MISSING, missing.state);
        assertEquals("Missing 2 parts", missing.stateText());

        ProjectStatus.Summary empty = summary();
        assertEquals(ProjectStatus.State.EMPTY, empty.state);
        assertEquals(0f, empty.coveredFraction(), 0);
    }

    @Test
    public void sortOrders() {
        List<ProjectStatus.Row<String>> rows = new ArrayList<>();
        rows.add(new ProjectStatus.Row<>("a", "beta", "2026-01-03 10:00:00", summary(req("R1", 10), req("C1", 2), req("U1", 1))));
        rows.add(new ProjectStatus.Row<>("b", "Alpha", "2026-01-01 10:00:00", summary(req("R1", 10), req("D1", 1))));
        rows.add(new ProjectStatus.Row<>("c", "gamma", "2026-01-02 10:00:00", summary(req("X9", 1))));
        rows.add(new ProjectStatus.Row<>("d", "delta", "2026-01-04 10:00:00", summary()));
        rows.add(new ProjectStatus.Row<>("e", "eps", "2026-01-05 10:00:00", summary(req("C1", 2))));

        assertEquals("b,a,d,e,c", ids(ProjectStatus.sorted(rows, ProjectStatus.Sort.NAME)));
        assertEquals("e,d,a,c,b", ids(ProjectStatus.sorted(rows, ProjectStatus.Sort.RECENT)));
        // Ready (b) first; then fewest blockers: e and c (1 each, none covered: by name), a (2); empty BOM last.
        assertEquals("b,e,c,a,d", ids(ProjectStatus.sorted(rows, ProjectStatus.Sort.CLOSEST)));
        assertEquals(ProjectStatus.Sort.RECENT, ProjectStatus.Sort.parse("bogus"));
        assertEquals(ProjectStatus.Sort.CLOSEST, ProjectStatus.Sort.parse("CLOSEST"));
    }

    private static String ids(List<ProjectStatus.Row<String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (ProjectStatus.Row<String> r : rows) sb.append(sb.length() == 0 ? "" : ",").append(r.item);
        return sb.toString();
    }
}
