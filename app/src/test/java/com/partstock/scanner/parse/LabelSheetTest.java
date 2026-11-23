package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LabelSheetTest {

    @Test
    public void slotZeroMatchesTheOldLayout() {
        // The layout before start slots: x = 13.5 + (i % 3) * 198, y = 36 + (i / 3) * 72, 30 per page.
        for (int i = 0; i < 65; i++) {
            LabelSheet.Slot s = LabelSheet.slot(i, 0);
            int inPage = i % 30;
            assertEquals(i / 30, s.page);
            assertEquals(13.5f + (inPage % 3) * 198f, s.x, 0f);
            assertEquals(36f + (inPage / 3) * 72f, s.y, 0f);
        }
    }

    @Test
    public void startOffsetCarriesOntoTheNextPage() {
        LabelSheet.Slot first = LabelSheet.slot(0, 7);
        assertEquals(0, first.page);
        assertEquals(7, first.slot);
        assertEquals(1, first.col);
        assertEquals(2, first.row);
        assertEquals(13.5f + 198f, first.x, 0f);
        assertEquals(36f + 2 * 72f, first.y, 0f);
        LabelSheet.Slot wrap = LabelSheet.slot(23, 7); // slot 30 → first slot of page 2
        assertEquals(1, wrap.page);
        assertEquals(0, wrap.slot);
        assertEquals(13.5f, wrap.x, 0f);
        assertEquals(36f, wrap.y, 0f);
    }

    @Test
    public void pageCounts() {
        assertEquals(0, LabelSheet.pages(0, 5));
        assertEquals(1, LabelSheet.pages(30, 0));
        assertEquals(2, LabelSheet.pages(31, 0));
        assertEquals(2, LabelSheet.pages(25, 6));
        assertEquals(1, LabelSheet.pages(24, 6));
        assertEquals(24, LabelSheet.onFirstPage(40, 6));
        assertEquals(3, LabelSheet.onFirstPage(3, 6));
        assertEquals(29, LabelSheet.clampStart(99));
        assertEquals(0, LabelSheet.clampStart(-2));
        assertEquals("Row 3, column 2", LabelSheet.describe(7));
    }
}
