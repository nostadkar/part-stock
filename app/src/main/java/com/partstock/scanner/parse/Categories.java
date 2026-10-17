package com.partstock.scanner.parse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sorts parts into buckets from Mouser's category or the description text. Rules are checked in order, so the
 * specific ones come first ("Switching Voltage Regulators" is power, not a switch; "TVS diode" is protection).
 */
public final class Categories {
    private Categories() {}

    public static final String POWER = "Power ICs & regulators";
    public static final String MCU = "Microcontrollers & processors";
    public static final String MEMORY = "Memory";
    public static final String INTERFACE = "Interface & communication";
    public static final String AMPLIFIERS = "Amplifiers & comparators";
    public static final String LOGIC = "Logic";
    public static final String SENSORS = "Sensors";
    public static final String OTHER_ICS = "Other ICs";
    public static final String TRANSISTORS = "Transistors";
    public static final String PROTECTION = "Fuses & protection";
    public static final String DIODES = "Diodes";
    public static final String LEDS = "LEDs & optoelectronics";
    public static final String RESISTORS = "Resistors";
    public static final String CAPACITORS = "Capacitors";
    public static final String INDUCTORS = "Inductors & ferrites";
    public static final String CRYSTALS = "Crystals & oscillators";
    public static final String CONNECTORS = "Connectors";
    public static final String SWITCHES = "Switches & relays";
    public static final String OTHER = "Other";

    /** Every bucket, in the order shown in pickers. */
    public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            RESISTORS, CAPACITORS, INDUCTORS, DIODES, LEDS, TRANSISTORS, POWER, MCU, MEMORY, INTERFACE,
            AMPLIFIERS, LOGIC, SENSORS, OTHER_ICS, CRYSTALS, CONNECTORS, SWITCHES, PROTECTION, OTHER));

    private static final class Rule {
        final String bucket;
        final Pattern p;
        Rule(String bucket, String regex) {
            this.bucket = bucket;
            this.p = Pattern.compile(regex);
        }
    }

    private static final List<Rule> RULES = new ArrayList<>();
    static {
        add(POWER, "VOLTAGE REG|REGULATOR|\\bLDO\\b|\\bBUCK|\\bBOOST|\\bSMPS|DC[-/ ]?DC|SWITCHING CONV|"
                + "SWITCHING VOLT|\\bPMIC|POWER MANAGEMENT|POWER SWITCH|GATE DRIVER|VOLTAGE REF|SUPERVISOR|"
                + "BATTERY (CHARG|MANAGE)|LED DRIVER|HOT.?SWAP");
        add(MCU, "MICROCONTROLLER|\\bMCU|MICROPROCESSOR|\\bMPU\\b|\\bDSP\\b|\\bFPGA|\\bCPLD|SYSTEM ON");
        add(MEMORY, "EEPROM|\\bFLASH\\b|\\bNOR\\b|\\bNAND\\b|\\bSRAM|\\bDRAM|\\bFRAM|MEMORY");
        add(INTERFACE, "TRANSCEIVER|INTERFACE|\\bUART|RS-?485|RS-?232|\\bCAN\\b|\\bUSB|ETHERNET|\\bPHY\\b|"
                + "ISOLATOR|\\bI2C|\\bSPI\\b|LEVEL (SHIFT|TRANSLAT)|ADC\\b|DAC\\b|ANALOG TO DIGITAL|DIGITAL TO ANALOG|"
                + "DATA CONVERTER|CODEC");
        add(AMPLIFIERS, "OP ?AMP|OPERATIONAL|AMPLIFIER|COMPARATOR|INSTRUMENTATION");
        add(SENSORS, "SENSOR|ACCELEROMETER|GYRO|HALL EFFECT|THERMISTOR|THERMOCOUPLE");
        add(CRYSTALS, "CRYSTAL|\\bXTAL|OSCILLATOR|RESONATOR|\\bXO\\b|\\bTCXO");
        add(PROTECTION, "\\bFUSE|\\bPTC|POLYFUSE|RESETTABLE|\\bTVS|\\bESD\\b|VARISTOR|\\bMOV\\b|SUPPRESS|"
                + "GAS DISCHARGE|SURGE");
        add(LEDS, "\\bLEDS?\\b|OPTO|PHOTO|INFRARED|\\bIR (EMIT|RECEIV)|DISPLAY");
        add(TRANSISTORS, "TRANSISTOR|MOSFET|\\bFET\\b|\\bJFET|\\bBJT|\\bIGBT|\\bNPN|\\bPNP|N-?CH(ANNEL)?\\b|"
                + "P-?CH(ANNEL)?\\b|DARLINGTON");
        add(DIODES, "DIODE|RECTIFIER|ZENER|SCHOTTKY|BRIDGE");
        add(LOGIC, "\\bLOGIC|\\bGATES?\\b|BUFFER|FLIP.?FLOP|MULTIPLEXER|\\bMUX|SHIFT REG|INVERTER|LATCH|"
                + "COUNTER|DECODER|ENCODER|\\b74[A-Z]*\\d");
        add(RESISTORS, "RESISTOR|\\bRES\\b|\\bRES[ ,]|POTENTIOMETER|TRIMMER|RHEOSTAT|\\bOHMS?\\b|\\d\\s*[KM]?Ω|"
                + "THICK FILM|THIN FILM|SHUNT");
        add(CAPACITORS, "CAPACITOR|\\bCAP\\b|\\bCAP[ ,]|CERAMIC|TANTALUM|ELECTROLYTIC|\\bMLCC|SUPERCAP|"
                + "\\d\\s*[PNUµ]F\\b");
        add(INDUCTORS, "INDUCTOR|\\bIND\\b|FERRITE|CHOKE|\\bBEAD|\\d\\s*[UNMµ]H\\b|TRANSFORMER");
        add(CONNECTORS, "CONNECTOR|\\bCONN\\b|HEADER|SOCKET|TERMINAL|RECEPTACLE|\\bPLUG|\\bJACK|"
                + "\\bPINS?\\b.*\\bPOS|\\bPOS(ITION)?\\b|CRIMP|HOUSING");
        add(SWITCHES, "\\bSWITCH(ES)?\\b|\\bSW\\b|RELAY|PUSHBUTTON|TACTILE|\\bDIP SW|\\bTACT\\b");
        add(OTHER_ICS, "\\bIC\\b|\\bICS\\b|INTEGRATED|\\bTIMER|\\bPLL");
    }

    private static void add(String bucket, String regex) {
        RULES.add(new Rule(bucket, regex));
    }

    /** First text that matches a rule decides; pass the most reliable text first (Mouser category). */
    public static String classify(String... texts) {
        for (String t : texts) {
            if (t == null || t.trim().isEmpty()) continue;
            String u = t.toUpperCase(Locale.US);
            for (Rule r : RULES) if (r.p.matcher(u).find()) return r.bucket;
        }
        return OTHER;
    }
}
