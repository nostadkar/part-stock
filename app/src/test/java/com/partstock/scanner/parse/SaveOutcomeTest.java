package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The review screen's sticky Save bar preview. */
public class SaveOutcomeTest {

    @Test
    public void newPart() {
        assertEquals("New part · 6 in A3", SaveOutcome.preview(true, 6, true, " A3 ", null, 0, ""));
        assertEquals("New part · 6, no bin", SaveOutcome.preview(true, 6, false, "", null, 0, ""));
    }

    @Test
    public void addShowsTheNewTotal() {
        assertEquals("Add 6 → 18 in bin A3", SaveOutcome.preview(true, 6, true, "A3", 12, 12, "A3"));
        // Blank bin goes to the part's main bin.
        assertEquals("Add 6 → 18 in bin B1", SaveOutcome.preview(true, 6, true, "", 12, 4, "B1"));
        assertEquals("Add 6 → 18", SaveOutcome.preview(true, 6, true, "", 12, 12, "Unassigned"));
    }

    @Test
    public void setReplacesThisBinOnly() {
        assertEquals("Set count to 6 in A3", SaveOutcome.preview(true, 6, false, "A3", 12, 12, "A3"));
        // 12 total, 4 of them in A3: setting A3 to 6 leaves 14.
        assertEquals("Set count to 6 in A3 → 14 total", SaveOutcome.preview(true, 6, false, "A3", 12, 4, "B1"));
    }

    @Test
    public void missingInput() {
        assertEquals("Enter a part number to save", SaveOutcome.preview(false, 6, true, "A3", null, 0, ""));
        assertEquals("Enter a quantity to save", SaveOutcome.preview(true, null, true, "A3", null, 0, ""));
    }
}
