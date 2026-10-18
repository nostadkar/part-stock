package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class ChangeSummaryTest {

    private static Map<String, String> m(String... kv) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put(kv[i], kv[i + 1]);
        return out;
    }

    @Test
    public void newAndDeleted() {
        assertEquals("New part", ChangeSummary.describe(null, m("mpn", "A")));
        assertEquals("New part", ChangeSummary.describe(m(), m("mpn", "A")));
        assertEquals("Deleted", ChangeSummary.describe(m("mpn", "A"), null));
        assertEquals("", ChangeSummary.describe(null, null));
    }

    @Test
    public void listsChangedFieldsSkippingBookkeeping() {
        Map<String, String> a = m("quantity", "500", "scans", "1", "updated_at", "2026-10-17 10:00:00",
                "location", "A3", "unit_cost", "0.12", "description", "RES 10K", "stock", "A3: 500");
        Map<String, String> b = m("quantity", "529", "scans", "2", "updated_at", "2026-10-18 10:00:00",
                "location", "R2", "unit_cost", "0.1", "description", "RES 10K OHM 1%", "stock", "A3: 500; R2: 29");
        assertEquals("bins A3: 500 → A3: 500; R2: 29; main bin A3 → R2; price 0.12 → 0.1; description",
                ChangeSummary.describe(a, b));
    }

    @Test
    public void sameValuesAndBlanks() {
        assertEquals("", ChangeSummary.describe(m("unit_cost", "0.1200", "location", "a3"), m("unit_cost", "0.12", "location", "A3")));
        assertEquals("", ChangeSummary.describe(m("location", ""), m("location", "Unassigned")));
        assertEquals("minimum – → 20", ChangeSummary.describe(m("min_qty", "null"), m("min_qty", "20")));
        assertEquals("price 1 → –", ChangeSummary.describe(m("unit_cost", "1.0"), m("unit_cost", null)));
        assertEquals("", ChangeSummary.describe(m("quantity", "1"), m("quantity", "2")));
    }
}
