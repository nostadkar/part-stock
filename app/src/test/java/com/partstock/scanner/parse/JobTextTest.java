package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class JobTextTest {

    @Test
    public void sizesAndSpeed() {
        assertEquals("", JobText.bytes(0));
        assertEquals("512 B", JobText.bytes(512));
        assertEquals("820 KB", JobText.bytes(820 * 1024));
        assertEquals("4.2 MB", JobText.bytes((long) (4.2 * 1024 * 1024)));
        assertEquals("12 MB", JobText.bytes(12L * 1024 * 1024));
        assertEquals("", JobText.speed(1000, 100));
        assertEquals("1.0 MB/s", JobText.speed(2L * 1024 * 1024, 2000));
        assertEquals("3.0 MB of 8.0 MB · 1.5 MB/s", JobText.download(3L * 1024 * 1024, 8L * 1024 * 1024, 2000));
        assertEquals("3.0 MB", JobText.download(3L * 1024 * 1024, 0, 100));
        assertEquals("12 of 40", JobText.count(12, 40));
        assertEquals("12", JobText.count(12, 0));
    }

    @Test
    public void releaseNoteLines() {
        String body = "Fix #46: progress card for long jobs\n\n- Refresh prices runs in the background\n"
                + "## Notes\nCo-Authored-By: Someone <x@y>\nClaude-Session: https://example\n";
        assertEquals(Arrays.asList("Fix #46: progress card for long jobs", "Refresh prices runs in the background", "Notes"),
                JobText.firstLines(body, 3));
        assertEquals(Collections.emptyList(), JobText.firstLines(null, 3));
        assertEquals(1, JobText.firstLines("a\nb\nc", 1).size());
    }
}
