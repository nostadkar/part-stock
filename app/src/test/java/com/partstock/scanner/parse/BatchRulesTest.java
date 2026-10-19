package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/** Batch scanning: only bags that pass every rule may be saved without review. */
public class BatchRulesTest {

    private static ParsedPart mouserBag(String po, String line, int qty) {
        ParsedPart p = new ParsedPart();
        p.labelType = ParsedPart.MOUSER;
        p.distributor = ParsedPart.MOUSER;
        p.distributorPn = "798-DF11-12DP-2DS24";
        p.mpn = "DF11-12DP-2DS24";
        p.mpnDerived = true;
        p.quantity = qty;
        p.purchaseOrder = po;
        p.lineItem = line;
        return p;
    }

    private static ParsedPart plain(String mpn, Integer qty) {
        ParsedPart p = new ParsedPart();
        p.mpn = mpn;
        p.quantity = qty;
        return p;
    }

    @Test
    public void confidentWhenEverythingPasses() {
        ParsedPart p = mouserBag("PO-1", "028", 50);
        assertTrue(BatchRules.reasons(p, BatchRules.LOOKUP_EXACT, false, BatchRules.DIFFERENT).isEmpty());
    }

    @Test
    public void realDigiKeyLabelIsConfidentAfterAnExactLookup() {
        ParsedPart p = LabelParser.parse(LabelParserTest.digiKey());
        assertTrue(BatchRules.reasons(p, BatchRules.LOOKUP_EXACT, false, BatchRules.DIFFERENT).isEmpty());
        // Without a lookup it is never confident.
        assertFalse(BatchRules.reasons(p, BatchRules.LOOKUP_NONE, false, BatchRules.DIFFERENT).isEmpty());
    }

    @Test
    public void everyLookupOutcomeButExactNeedsReview() {
        ParsedPart p = mouserBag("PO-1", "028", 50);
        for (String l : new String[] {
                BatchRules.LOOKUP_NONE, BatchRules.LOOKUP_PENDING, BatchRules.LOOKUP_CORRECTED,
                BatchRules.LOOKUP_NOT_FOUND, BatchRules.LOOKUP_FAILED, null, "something new"}) {
            assertEquals(l, 1, BatchRules.reasons(p, l, false, BatchRules.DIFFERENT).size());
        }
    }

    @Test
    public void missingPartNumberOrQuantityNeedsReview() {
        List<String> r = BatchRules.reasons(plain(null, 10), BatchRules.LOOKUP_EXACT, false, 0);
        assertEquals(1, r.size());
        assertTrue(r.get(0).contains("part number"));
        r = BatchRules.reasons(plain("LM358", null), BatchRules.LOOKUP_EXACT, false, 0);
        assertTrue(r.get(0).contains("quantity"));
        r = BatchRules.reasons(plain("LM358", 0), BatchRules.LOOKUP_EXACT, false, 0);
        assertTrue(r.get(0).contains("quantity"));
    }

    @Test
    public void kitLabelAloneIsPerBoard() {
        ParsedPart p = plain("2SK3019-TP", 2);
        p.kitPerBoard = 2;
        assertEquals(1, BatchRules.reasons(p, BatchRules.LOOKUP_EXACT, false, 0).size());
        // With a distributor label on the bag, the quantity is the bag count.
        p.distributorPn = "2SK3019-TPMSCT-ND";
        assertTrue(BatchRules.reasons(p, BatchRules.LOOKUP_EXACT, false, 0).isEmpty());
    }

    @Test
    public void duplicates() {
        ParsedPart p = mouserBag("PO-1", "028", 50);
        assertFalse(BatchRules.reasons(p, BatchRules.LOOKUP_EXACT, true, 0).isEmpty());
        assertTrue(BatchRules.isDuplicate(true, BatchRules.DIFFERENT));
        assertTrue(BatchRules.isDuplicate(false, BatchRules.SAME));
        assertFalse(BatchRules.isDuplicate(false, BatchRules.MAYBE_SAME));
        assertFalse(BatchRules.reasons(p, BatchRules.LOOKUP_EXACT, false, BatchRules.MAYBE_SAME).isEmpty());
    }

    @Test
    public void sameBag() {
        assertEquals(BatchRules.SAME, BatchRules.sameBag(mouserBag("PO-1", "028", 50), mouserBag("po-1", "028", 50)));
        assertEquals(BatchRules.DIFFERENT, BatchRules.sameBag(mouserBag("PO-1", "028", 50), mouserBag("PO-1", "029", 50)));
        // No order details: same part and quantity might be the same bag; another quantity is another bag.
        assertEquals(BatchRules.MAYBE_SAME, BatchRules.sameBag(plain("LM358", 10), plain("lm 358", 10)));
        assertEquals(BatchRules.DIFFERENT, BatchRules.sameBag(plain("LM358", 10), plain("LM358", 20)));
        assertEquals(BatchRules.DIFFERENT, BatchRules.sameBag(plain("LM358", 10), plain("NE555", 10)));
        assertEquals(BatchRules.DIFFERENT, BatchRules.sameBag(mouserBag("PO-1", "028", 50), plain("DF11-12DP-2DS24", 50)));
        assertEquals(BatchRules.DIFFERENT, BatchRules.sameBag(null, plain("LM358", 10)));
    }

    @Test
    public void exactHit() {
        assertTrue(BatchRules.exactHit("798-DF11-12DP-2DS24", "798-DF11-12DP-2DS24", "DF11-12DP-2DS24"));
        assertTrue(BatchRules.exactHit("df11-12dp-2ds24", "798-DF11-12DP-2DS24", "DF11-12DP-2DS24"));
        assertFalse(BatchRules.exactHit("LT8609AIMSE16#PBE", "584-LT8609AIMSE16PBF", "LT8609AIMSE16#PBF"));
        assertFalse(BatchRules.exactHit("", "A", "A"));
        assertFalse(BatchRules.exactHit("A", null, null));
    }
}
