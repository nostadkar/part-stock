package com.partstock.scanner.parse;

/**
 * Where labels land on a Letter sheet of Avery 5160 / 8160 labels: 3 × 10 labels of 2.625 × 1 in. Sizes in PDF
 * points (1/72 in). Labels can start at any slot, so a partly used sheet can go through the printer again: label
 * {@code i} goes in slot {@code start + i}, counted across pages (pure Java, unit tested).
 */
public final class LabelSheet {
    private LabelSheet() {}

    public static final int PAGE_W = 612;
    public static final int PAGE_H = 792;
    public static final float LEFT = 13.5f;
    public static final float TOP = 36f;
    public static final float PITCH_X = 198f;
    public static final float PITCH_Y = 72f;
    public static final int COLS = 3;
    public static final int ROWS = 10;
    public static final int PER_PAGE = COLS * ROWS;

    /** A label's place: page (0-based), slot on that page (0 = top left, row by row), column, row and top-left corner. */
    public static final class Slot {
        public final int page, slot, col, row;
        public final float x, y;

        Slot(int page, int slot) {
            this.page = page;
            this.slot = slot;
            this.col = slot % COLS;
            this.row = slot / COLS;
            // Same float arithmetic as before slots could be chosen, so slot 0 gives the same PDF.
            this.x = LEFT + col * PITCH_X;
            this.y = TOP + row * PITCH_Y;
        }
    }

    /** A start slot the sheet has (0 … 29); anything else is clamped. */
    public static int clampStart(int start) {
        return Math.max(0, Math.min(PER_PAGE - 1, start));
    }

    /** Where label {@code index} goes when the first one is at slot {@code start} of the first page. */
    public static Slot slot(int index, int start) {
        int k = clampStart(start) + index;
        return new Slot(k / PER_PAGE, k % PER_PAGE);
    }

    /** Pages needed for {@code count} labels starting at slot {@code start}; 0 for no labels. */
    public static int pages(int count, int start) {
        if (count <= 0) return 0;
        return (clampStart(start) + count + PER_PAGE - 1) / PER_PAGE;
    }

    /** How many labels go on the first page. */
    public static int onFirstPage(int count, int start) {
        return Math.max(0, Math.min(count, PER_PAGE - clampStart(start)));
    }

    /** "Row 2, column 1" for slot 3 (1-based, for the picker and TalkBack). */
    public static String describe(int slot) {
        int s = clampStart(slot);
        return "Row " + (s / COLS + 1) + ", column " + (s % COLS + 1);
    }
}
