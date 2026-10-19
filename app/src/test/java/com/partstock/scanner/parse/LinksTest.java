package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LinksTest {

    @Test
    public void datasheetUsesSavedLink() {
        String url = Links.datasheetUrl(" https://www.vishay.com/docs/20035/dcrcwe3.pdf ", "71-CRCW060310K0FKEA", "CRCW060310K0FKEA");
        assertEquals("https://www.vishay.com/docs/20035/dcrcwe3.pdf", url);
        assertFalse(Links.isDatasheetSearch(url));
    }

    @Test
    public void datasheetFixesProtocolRelativeLink() {
        assertEquals("https://mm.digikey.com/Volume0/x.pdf", Links.datasheetUrl("//mm.digikey.com/Volume0/x.pdf", "", "X"));
    }

    @Test
    public void datasheetFallsBackToSearchByMpn() {
        String url = Links.datasheetUrl("", "2SK3019-TPMSCT-ND", "2SK3019-TP");
        assertEquals("https://www.google.com/search?q=2SK3019-TP+datasheet", url);
        assertTrue(Links.isDatasheetSearch(url));
    }

    @Test
    public void datasheetSearchUsesDistributorPnWithoutMpn() {
        assertEquals("https://www.google.com/search?q=595-LM358DR+datasheet", Links.datasheetUrl(null, "595-LM358DR", " "));
    }

    @Test
    public void datasheetIgnoresNonWebSavedValue() {
        assertEquals("https://www.google.com/search?q=LM358+datasheet", Links.datasheetUrl("n/a", "", "LM358"));
    }

    @Test
    public void datasheetNullWithoutPartNumber() {
        assertNull(Links.datasheetUrl("", "", ""));
    }
}
