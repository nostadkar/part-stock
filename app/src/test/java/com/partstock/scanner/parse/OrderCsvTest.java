package com.partstock.scanner.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Mouser and DigiKey order CSVs. The header rows below follow the exports as far as they could be checked; the
 * column mapping matches by keywords, so small wording changes ("Mouser #" vs "Mouser No") still work.
 */
public class OrderCsvTest {

    static final String MOUSER_HISTORY =
            "Sales Order #,Web Order #,Mouser #,Mfr. #,Manufacturer,Customer #,Description,RoHS,Order Qty.,"
                    + "Price (USD),Ext.: (USD)\n"
                    + "264000123,1987654,798-DF11-12DP-2DS24,DF11-12DP-2DS24,Hirose,809056-412,"
                    + "\"Headers & Wire Housings 2MM R/A PCB HEADER 12P\",Compliant,10,$0.966,$9.66\n"
                    + "264000123,1987654,584-8609AIMSE16#PBF,LT8609AIMSE16#PBF,Analog Devices Inc.,811650-101,"
                    + "Switching Voltage Regulators 42V 2A/3A,Compliant,\"1,000\",$4.12,\"$4,120.00\"\n"
                    + ",,,,,,,,Merchandise Total:,,\"$4,129.66\"\n";

    static final String MOUSER_CART =
            "Mouser No,Mfr. No,Manufacturer,Customer No,Description,RoHS,Lifecycle,Order Qty.,Price (USD),Ext. (USD)\n"
                    + "71-CRCW060310K0FKEA,CRCW060310K0FKEA,Vishay,,Thick Film Resistors - SMD 1/10W 10K 1%,"
                    + "Compliant,,100,0.012,1.20\n";

    static final String DIGIKEY =
            "Index,Quantity,Part Number,Manufacturer Part Number,Description,Customer Reference,Available,"
                    + "Backorder,Unit Price,Extended Price\n"
                    + "1,500,2SK3019-TPMSCT-ND,2SK3019-TP,MOSFET N-CH 30V 100MA SOT523,811755-101,500,0,0.0544,27.20\n"
                    + "2,10,311-10.0KHRCT-ND,RC0603FR-0710KL,RES 10K OHM 1% 1/10W 0603,,10,0,0.10,1.00\n";

    static final String DIGIKEY_NEW =
            "Index,DigiKey Product Number,Manufacturer Product Number,Manufacturer,Description,Customer Reference,"
                    + "Quantity,Unit Price,Extended Price\n"
                    + "1,2SK3019-TPMSCT-ND,2SK3019-TP,Micro Commercial Co,MOSFET N-CH 30V 100MA SOT523,811755-101,"
                    + "500,$0.05440,$27.20\n";

    @Test
    public void mouserOrderHistory() {
        OrderCsv.Order o = OrderCsv.parse(MOUSER_HISTORY, "Mouser order.csv");
        assertNull(o.error);
        assertEquals(ParsedPart.MOUSER, o.distributor);
        assertEquals("264000123", o.orderNumber);
        assertEquals(2, o.lines.size());
        OrderCsv.Line a = o.lines.get(0);
        assertEquals("798-DF11-12DP-2DS24", a.distributorPn);
        assertEquals("DF11-12DP-2DS24", a.mpn);
        assertEquals("Hirose", a.manufacturer);
        assertEquals("809056-412", a.customerPn);
        assertEquals("HEADERS & WIRE HOUSINGS 2MM R/A PCB HEADER 12P", a.description);
        assertEquals(10, a.quantity);
        assertEquals(0.966, a.unitPrice, 1e-9);
        assertEquals("1", a.line);
        OrderCsv.Line b = o.lines.get(1);
        assertEquals(1000, b.quantity);
        assertEquals(4.12, b.unitPrice, 1e-9);
        assertEquals("2", b.line);
    }

    @Test
    public void mouserCartTakesOrderNumberFromFileName() {
        OrderCsv.Order o = OrderCsv.parse(MOUSER_CART, "Order_264000999 (1).csv");
        assertEquals(ParsedPart.MOUSER, o.distributor);
        assertEquals("264000999", o.orderNumber);
        assertEquals(1, o.lines.size());
        assertEquals("CRCW060310K0FKEA", o.lines.get(0).mpn);
        assertEquals("", o.lines.get(0).customerPn);
        assertEquals(0.012, o.lines.get(0).unitPrice, 1e-9);
    }

    @Test
    public void digiKeyOrder() {
        // DigiKey's cart export calls its own number just "Part Number"; the -ND values say whose it is.
        OrderCsv.Order o = OrderCsv.parse(DIGIKEY, "84046368.csv");
        assertNull(o.error);
        assertEquals(ParsedPart.DIGIKEY,
                OrderCsv.parse(DIGIKEY.replace(",Part Number,", ",Digi-Key Part Number,"), null).distributor);
        assertEquals(ParsedPart.DIGIKEY, o.distributor);
        assertEquals("84046368", o.orderNumber);
        assertEquals(2, o.lines.size());
        OrderCsv.Line a = o.lines.get(0);
        assertEquals("2SK3019-TPMSCT-ND", a.distributorPn);
        assertEquals("2SK3019-TP", a.mpn);
        assertEquals("811755-101", a.customerPn);
        assertEquals(500, a.quantity);
        assertEquals(0.0544, a.unitPrice, 1e-9);
        assertEquals("1", a.line);
        assertEquals("RC0603FR-0710KL", o.lines.get(1).mpn);
    }

    @Test
    public void digiKeyNewerHeaders() {
        OrderCsv.Order o = OrderCsv.parse(DIGIKEY_NEW, "Order 84046368.csv");
        assertEquals(ParsedPart.DIGIKEY, o.distributor);
        OrderCsv.Line a = o.lines.get(0);
        assertEquals("2SK3019-TPMSCT-ND", a.distributorPn);
        assertEquals("2SK3019-TP", a.mpn);
        assertEquals("Micro Commercial Co", a.manufacturer);
        assertEquals(500, a.quantity);
        assertEquals(0.0544, a.unitPrice, 1e-9);
    }

    @Test
    public void headerAfterPreambleAndShippedQuantityPreferred() {
        String csv = "Mouser Electronics Order History\nExported,10/18/2026\n\n"
                + "Mouser Part #,Mfr Part #,Qty Ordered,Qty Shipped,Unit Price\n"
                + "595-TPS7A4901DGNR,TPS7A4901DGNR,10,8,1.95\n";
        OrderCsv.Order o = OrderCsv.parse(csv, null);
        assertEquals(ParsedPart.MOUSER, o.distributor);
        assertEquals(8, o.lines.get(0).quantity);
        assertEquals("", o.orderNumber);
    }

    @Test
    public void notAnOrder() {
        assertNotNull(OrderCsv.parse("Name,Phone\nBob,123\n", "contacts.csv").error);
        assertNotNull(OrderCsv.parse("", "x.csv").error);
        // The app's own inventory export has an MPN and quantity but no distributor column.
        assertNotNull(OrderCsv.parse("MPN,Quantity\nABC,1\n", "inventory.csv").error);
        assertNotNull(OrderCsv.parse("Part Number,Quantity\nABC-123,1\n", "parts.csv").error);
    }

    @Test
    public void fingerprintIsStablePerOrderLine() {
        OrderCsv.Order o = OrderCsv.parse(MOUSER_HISTORY, null);
        String fp = o.lines.get(0).fingerprint(o.distributor, o.orderNumber);
        assertEquals("ORDER|MOUSER|264000123|1", fp);
        assertEquals(fp, OrderCsv.parse(MOUSER_HISTORY, null).lines.get(0).fingerprint(o.distributor, " 264000123 "));
        assertNotEquals(fp, o.lines.get(1).fingerprint(o.distributor, o.orderNumber));
        // Without an order number the part number and quantity stand in for it.
        assertEquals("ORDER|MOUSER|798-DF11-12DP-2DS24|10|1", o.lines.get(0).fingerprint(o.distributor, ""));
    }

    @Test
    public void headerKeywords() {
        assertEquals(0.5, OrderCsv.price("$0.50"), 1e-9);
        assertEquals(1234.5, OrderCsv.price("1,234.50 USD"), 1e-9);
        assertNull(OrderCsv.price(""));
        assertEquals(Integer.valueOf(1000), OrderCsv.quantity("1,000"));
        assertNull(OrderCsv.quantity("Merchandise Total:"));
        assertEquals("84046368", OrderCsv.orderFromFileName("DK_84046368_2026.csv"));
    }
}
