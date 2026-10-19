package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class HighlightTest {

    private static String show(String text, String query) {
        StringBuilder sb = new StringBuilder();
        for (int[] r : Highlight.ranges(text, query)) sb.append(r[0]).append('-').append(r[1]).append(' ');
        return sb.toString().trim();
    }

    @Test
    public void findsWordsIgnoringCase() {
        assertEquals("0-5", show("LM358DR", "lm358"));
        assertEquals("4-7 8-12", show("RES 10K 0603 1%", "0603 10k"));
    }

    @Test
    public void mergesOverlaps() {
        assertEquals("0-4", show("AAAA", "aa"));
        assertEquals("0-5", show("ABCDE", "abc cde"));
    }

    @Test
    public void emptyForNoMatchOrBlankQuery() {
        assertEquals(0, Highlight.ranges("LM358", "xyz").size());
        assertEquals(0, Highlight.ranges("LM358", "  ").size());
        List<int[]> none = Highlight.ranges("", "a");
        assertEquals(0, none.size());
    }
}
