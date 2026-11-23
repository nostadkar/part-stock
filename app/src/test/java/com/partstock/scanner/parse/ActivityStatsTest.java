package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ActivityStatsTest {

    // Monday 23 Nov 2026 is the start of "this week".
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

    private static ActivityStats.Change ch(long id, String name, String ts, String action, Integer before, Integer after) {
        return new ActivityStats.Change(id, name, ts, action, before, after, false);
    }

    @Test
    public void kinds() {
        assertEquals(ActivityStats.Kind.ADDED, ActivityStats.kind("scan add"));
        assertEquals(ActivityStats.Kind.ADDED, ActivityStats.kind("scan set"));
        assertEquals(ActivityStats.Kind.ADDED, ActivityStats.kind("adjust"));
        assertEquals(ActivityStats.Kind.USED, ActivityStats.kind("use"));
        assertEquals(ActivityStats.Kind.USED, ActivityStats.kind("use · Build Amp"));
        assertEquals(ActivityStats.Kind.NEUTRAL, ActivityStats.kind("undo"));
        assertEquals(ActivityStats.Kind.NEUTRAL, ActivityStats.kind("restore"));
        assertEquals(ActivityStats.Kind.NEUTRAL, ActivityStats.kind("edit"));
        assertEquals(ActivityStats.Kind.NEUTRAL, ActivityStats.kind("lookup Mouser"));
        assertEquals(ActivityStats.Kind.NEUTRAL, ActivityStats.kind("move"));
        assertEquals(ActivityStats.Kind.NEUTRAL, ActivityStats.kind("delete"));
        assertEquals(ActivityStats.Kind.NEUTRAL, ActivityStats.kind(null));
    }

    @Test
    public void piecesFollowTheDirection() {
        assertEquals(100, ActivityStats.pieces(ch(1, "a", "2026-11-23 10:00:00", "scan add", null, 100)));
        assertEquals(20, ActivityStats.pieces(ch(1, "a", "2026-11-23 10:00:00", "scan add", 80, 100)));
        // A recount that lowered the count is a correction, not use.
        assertEquals(0, ActivityStats.pieces(ch(1, "a", "2026-11-23 10:00:00", "scan set", 100, 80)));
        assertEquals(5, ActivityStats.pieces(ch(1, "a", "2026-11-23 10:00:00", "use", 10, 5)));
        assertEquals(0, ActivityStats.pieces(ch(1, "a", "2026-11-23 10:00:00", "undo", 5, 10)));
        assertEquals(0, ActivityStats.pieces(new ActivityStats.Change(1, "a", "2026-11-23 10:00:00", "use", 10, 5, true)));
    }

    @Test
    public void weeklyBucketsEndThisWeek() {
        List<ActivityStats.Change> c = Arrays.asList(
            ch(1, "100nF", "2026-11-23 09:00:00", "scan add", null, 120),  // this week (Mon)
            ch(1, "100nF", "2026-11-22 20:00:00", "use", 120, 100),        // previous week (Sun)
            ch(2, "LM358", "2026-11-16 08:00:00", "adjust", 4, 10),         // previous week (Mon)
            ch(2, "LM358", "2026-11-02 08:00:00", "use · Build Amp", 10, 7),// 4th week back
            ch(2, "LM358", "2026-11-24 08:00:00", "undo", 7, 10),           // neutral
            ch(3, "Old", "2026-01-05 08:00:00", "scan add", null, 50),      // outside 12 weeks
            ch(3, "Old", "2026-11-28 08:00:00", "scan add", 50, 60)         // after today: ignored
        );
        List<ActivityStats.Week> w = ActivityStats.weekly(c, TODAY, 4);
        assertEquals(4, w.size());
        assertEquals(LocalDate.of(2026, 11, 2), w.get(0).start);
        assertEquals(LocalDate.of(2026, 11, 23), w.get(3).start);
        assertEquals(120, w.get(3).added);
        assertEquals(0, w.get(3).used);
        assertEquals(6, w.get(2).added);
        assertEquals(20, w.get(2).used);
        assertEquals(3, w.get(0).used);
        assertEquals("Last 4 weeks: 126 added, 23 used", ActivityStats.summary(w));
        assertEquals("Last 12 weeks: 126 added, 23 used", ActivityStats.summary(ActivityStats.weekly(c, TODAY, 12)));
        assertEquals("Last 52 weeks: 176 added, 23 used", ActivityStats.summary(ActivityStats.weekly(c, TODAY, 52)));
    }

    @Test
    public void topUsedAndBiggest() {
        List<ActivityStats.Change> c = Arrays.asList(
            ch(1, "100nF", "2026-11-14 09:00:00", "scan add", null, 120),
            ch(1, "100nF", "2026-11-20 09:00:00", "use", 120, 100),
            ch(1, "100nF", "2026-11-21 09:00:00", "use · Build Amp", 100, 90),
            ch(2, "LM358", "2026-11-18 09:00:00", "use", 40, 10),
            ch(3, "BC547", "2026-11-18 09:00:00", "use", 5, 4),
            ch(4, "", "2026-11-19 09:00:00", "use", 2, 0),
            ch(5, "Old", "2025-01-01 09:00:00", "use", 500, 0)
        );
        List<ActivityStats.Used> top = ActivityStats.topUsed(c, TODAY, 12, 3);
        assertEquals("[100nF=30, LM358=30, =2]", top.toString());
        assertEquals(1, top.get(0).partId);

        List<ActivityStats.Big> big = ActivityStats.biggest(c, TODAY, 12, 3);
        assertEquals(3, big.size());
        assertEquals("Scanned 120 × 100nF · 14 Nov", big.get(0).text);
        assertEquals("Used 30 × LM358 · 18 Nov", big.get(1).text);
        assertEquals("Used 20 × 100nF · 20 Nov", big.get(2).text);
        assertEquals("Used 10 × 100nF · 21 Nov · Build Amp",
            ActivityStats.describe(c.get(2)));
        assertEquals("Used 2 × deleted part · 19 Nov", ActivityStats.describe(c.get(5)));
        assertEquals("Imported 5 × X · 1 Nov", ActivityStats.describe(ch(9, "X", "2026-11-01 00:00:00", "import", 0, 5)));
    }

    @Test
    public void untouchedSkipsNewAndEmptyParts() {
        List<ActivityStats.Touch> parts = Arrays.asList(
            new ActivityStats.Touch(1, "stale", 10, "2025-03-01 10:00:00", "2024-01-01 10:00:00", "2026-10-01 10:00:00"),
            new ActivityStats.Touch(2, "recent use", 10, "2026-06-01 10:00:00", null, "2026-06-01 10:00:00"),
            new ActivityStats.Touch(3, "legacy, no history", 5, null, null, "2024-05-01 10:00:00"),
            new ActivityStats.Touch(4, "empty", 0, "2024-01-01 10:00:00", null, "2024-01-01 10:00:00"),
            new ActivityStats.Touch(5, "added this year", 3, null, "2026-02-01 10:00:00", "2026-02-01 10:00:00"),
            new ActivityStats.Touch(6, "no dates", 3, "", "", "")
        );
        List<ActivityStats.Touch> out = ActivityStats.untouched(parts, TODAY, 12);
        assertEquals(2, out.size());
        assertEquals(3, out.get(0).partId); // oldest first
        assertEquals(1, out.get(1).partId);
        assertEquals(LocalDate.of(2025, 3, 1), ActivityStats.lastTouched(out.get(1)));
    }

    @Test
    public void dates() {
        assertEquals(LocalDate.of(2026, 11, 23), ActivityStats.weekStart(LocalDate.of(2026, 11, 29)));
        assertEquals(LocalDate.of(2026, 11, 23), ActivityStats.weekStart(LocalDate.of(2026, 11, 23)));
        assertNull(ActivityStats.date("bad"));
        assertNull(ActivityStats.date("2026-13-45 00:00:00"));
        assertEquals("14 Nov", ActivityStats.day(LocalDate.of(2026, 11, 14)));
    }
}
