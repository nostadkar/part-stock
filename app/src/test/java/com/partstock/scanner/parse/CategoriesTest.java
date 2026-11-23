package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class CategoriesTest {

    @Test
    public void searchMatchesWordStarts() {
        assertEquals(Arrays.asList(Categories.CAPACITORS), Categories.search(Categories.ALL, "cap"));
        assertEquals(Arrays.asList(Categories.POWER), Categories.search(Categories.ALL, "power reg"));
        assertEquals(Arrays.asList(Categories.LEDS), Categories.search(Categories.ALL, "OPTO"));
        assertEquals(Arrays.asList(Categories.AMPLIFIERS), Categories.search(Categories.ALL, "comp"));
        assertEquals(Categories.ALL, Categories.search(Categories.ALL, "  "));
        assertEquals(Arrays.asList(), Categories.search(Categories.ALL, "zzz"));
    }

    @Test
    public void chipsBeforeMoreKeepsTheSelectedOneVisible() {
        List<String> cats = Arrays.asList("A", "B", "C", "D", "E");
        assertEquals(cats, Categories.chipsBeforeMore(cats, null, 5));
        assertEquals(Arrays.asList("A", "B", "C"), Categories.chipsBeforeMore(cats, null, 3));
        assertEquals(Arrays.asList("A", "B", "C"), Categories.chipsBeforeMore(cats, "B", 3));
        assertEquals(Arrays.asList("A", "B", "E"), Categories.chipsBeforeMore(cats, "E", 3));
        assertEquals(Arrays.asList("A", "B", "C"), Categories.chipsBeforeMore(cats, "Z", 3));
    }
}
