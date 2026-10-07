package ma.mystix.stock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ma.mystix.format.edifact.EdifactParser;
import ma.mystix.format.edifact.Segment;
import ma.mystix.shared.time.TimeConfig;

/**
 * One UN/EDIFACT D96A stock message to one canonical stock event (ADR-0012). Every qualifier below was checked
 * on the UNTDID D96A code lists (6063, 3227, 3035, 1153, 2005, 1001, 4501, 7491); none is guessed.
 */
final class StockEdifactMapper {

    /** A message that cannot become a stock event: what is wrong and where. */
    static final class MappingException extends RuntimeException {
        final String segment;

        MappingException(String segment, String message) {
            super(message);
            this.segment = segment;
        }
    }

    private StockEdifactMapper() {
    }

    // 6063 Quantity qualifier
    private static final String DESPATCH = "12";
    private static final String ORDERED = "21";
    private static final String RECEIVED = "48";
    private static final String RECEIVED_ACCEPTED = "194";
    private static final List<String> NOT_ACCEPTED = List.of("195", "196", "124"); // returned, destroyed, damaged
    private static final List<String> SHORT = List.of("119", "122");              // short shipped, short-landed
    private static final String ACTUAL_STOCK = "145";
    private static final String ON_HAND = "17";
    private static final String INVENTORY_ADJUSTMENT = "191";
    // 3227 Place/location qualifier: 18 Warehouse, 14 Location of goods
    private static final List<String> LOCATION_QUALIFIERS = List.of("18", "14");

    private record Qty(String qualifier, BigDecimal value, String direction4501, String type7491,
                       Map<String, String> locations, Segment segment) {
    }

    private static final class Line {
        String sku = "";
        final Map<String, String> locations = new LinkedHashMap<>();
        final List<Qty> quantities = new ArrayList<>();
        Segment segment;
    }

    private static final class Header {
        String documentNumber;
        final Map<String, String[]> dates = new LinkedHashMap<>();
        final Map<String, String> references = new LinkedHashMap<>();
        final Map<String, String> parties = new LinkedHashMap<>();
        final Map<String, String> locations = new LinkedHashMap<>();
    }

    /**
     * @param direction       direction of the client's flow (IN or OUT), needed for a DESADV only (the same structure
     *                        serves both ways)
     * @param defaultLocation used when the message names no location
     */
    static StockEventRequest map(EdifactParser.Interchange interchange, EdifactParser.Message message,
                                 String direction, String defaultLocation) {
        Header header = new Header();
        List<Line> lines = new ArrayList<>();
        Line line = null;
        Qty qty = null;
        for (Segment s : message.segments()) {
            switch (s.tag()) {
                case "BGM" -> header.documentNumber = blank(s.value(1, 0));
                case "DTM" -> {
                    if (line == null) {
                        header.dates.putIfAbsent(s.value(0, 0), new String[] {s.value(0, 1), s.value(0, 2)});
                    }
                }
                case "RFF" -> {
                    if (line == null) {
                        header.references.putIfAbsent(s.value(0, 0), s.value(0, 1));
                    }
                }
                case "NAD" -> {
                    if (line == null) {
                        header.parties.putIfAbsent(s.value(0, 0), s.value(1, 0));
                    }
                }
                case "LOC" -> {
                    Map<String, String> target = qty != null ? qty.locations() : line != null ? line.locations : header.locations;
                    target.putIfAbsent(s.value(0, 0), s.value(1, 0));
                }
                case "LIN" -> {
                    line = new Line();
                    line.sku = s.value(2, 0);
                    line.segment = s;
                    lines.add(line);
                    qty = null;
                }
                case "PIA" -> {
                    if (line != null && line.sku.isBlank()) {
                        line.sku = s.value(1, 0);
                    }
                }
                case "QTY" -> {
                    if (line != null) {
                        qty = new Qty(s.value(0, 0), quantity(s, interchange.decimalMark()), null, null,
                                new LinkedHashMap<>(), s);
                        line.quantities.add(qty);
                    }
                }
                case "INV" -> {
                    if (qty != null) {
                        Qty withInv = new Qty(qty.qualifier(), qty.value(), blank(s.value(0, 0)), blank(s.value(1, 0)),
                                qty.locations(), qty.segment());
                        line.quantities.set(line.quantities.size() - 1, withInv);
                        qty = withInv;
                    }
                }
                default -> { }
            }
        }
        for (Line l : lines) {
            if (l.sku.isBlank()) {
                throw new MappingException(at(l.segment), "line without an item number (LIN C212 7140 or PIA C212 7140)");
            }
        }
        String key = "EDI:" + interchange.sender() + ":" + interchange.controlReference() + ":" + message.reference();
        return switch (message.type()) {
            case "DESADV" -> desadv(header, lines, key, direction, defaultLocation);
            case "RECADV" -> recadv(header, lines, key, defaultLocation);
            case "ORDERS" -> orders(header, lines, key, defaultLocation);
            case "INVRPT" -> invrpt(header, lines, key, defaultLocation);
            default -> throw new MappingException("UNH", "message type " + message.type()
                    + " is not a stock message (DESADV, RECADV, ORDERS, INVRPT)");
        };
    }

    private static StockEventRequest desadv(Header h, List<Line> lines, String key, String direction,
                                            String defaultLocation) {
        if (!"IN".equals(direction) && !"OUT".equals(direction)) {
            throw new MappingException("UNH", "a DESADV needs the client's IN or OUT flow (header X-Mystix-Flow-Id): "
                    + "IN for a despatch advice from a supplier, OUT for our despatch to a customer");
        }
        boolean in = "IN".equals(direction);
        List<StockEventRequest.Line> out = new ArrayList<>();
        for (Line l : lines) {
            BigDecimal despatched = sum(l, List.of(DESPATCH));
            if (despatched == null) {
                throw new MappingException(at(l.segment), "no despatch quantity (QTY+12) for item " + l.sku);
            }
            out.add(simple(l, location(h, l, null, in ? List.of("DP", "ST") : List.of("SF"), defaultLocation), despatched));
        }
        return new StockEventRequest(in ? "SHIPMENT_NOTICE_IN" : "SHIPMENT_OUT", key, h.documentNumber, null,
                when(h, List.of("11", "137")), out);
    }

    private static StockEventRequest recadv(Header h, List<Line> lines, String key, String defaultLocation) {
        // 1153: AAK Despatch advice number, DQ Delivery note number
        String despatchAdvice = blank(h.references.getOrDefault("AAK", h.references.get("DQ")));
        if (despatchAdvice == null) {
            throw new MappingException("RFF", "a RECADV must reference its despatch advice (RFF+AAK, or RFF+DQ)");
        }
        List<StockEventRequest.Line> out = new ArrayList<>();
        for (Line l : lines) {
            BigDecimal refused = zero(sum(l, NOT_ACCEPTED));
            BigDecimal missing = zero(sum(l, SHORT));
            BigDecimal accepted = sum(l, List.of(RECEIVED_ACCEPTED));
            if (accepted == null) {
                BigDecimal received = sum(l, List.of(RECEIVED));
                accepted = received == null ? BigDecimal.ZERO : received.subtract(refused);
                if (accepted.signum() < 0) {
                    throw new MappingException(at(l.segment), "refused quantities exceed the received quantity (QTY+48) for item " + l.sku);
                }
            }
            String location = location(h, l, null, List.of("DP", "ST"), defaultLocation);
            out.add(new StockEventRequest.Line(l.sku, location, null, null, null, null, accepted, refused, missing));
        }
        return new StockEventRequest("RECEIPT", key, h.documentNumber, despatchAdvice, when(h, List.of("50", "137")), out);
    }

    private static StockEventRequest orders(Header h, List<Line> lines, String key, String defaultLocation) {
        List<StockEventRequest.Line> out = new ArrayList<>();
        for (Line l : lines) {
            BigDecimal ordered = sum(l, List.of(ORDERED));
            if (ordered == null) {
                throw new MappingException(at(l.segment), "no ordered quantity (QTY+21) for item " + l.sku);
            }
            out.add(simple(l, location(h, l, null, List.of("SF"), defaultLocation), ordered));
        }
        return new StockEventRequest("ORDER", key, h.documentNumber, null, when(h, List.of("137")), out);
    }

    private static StockEventRequest invrpt(Header h, List<Line> lines, String key, String defaultLocation) {
        boolean adjustments = lines.stream().flatMap(l -> l.quantities.stream())
                .anyMatch(q -> INVENTORY_ADJUSTMENT.equals(q.qualifier()));
        boolean stock = lines.stream().flatMap(l -> l.quantities.stream())
                .anyMatch(q -> ACTUAL_STOCK.equals(q.qualifier()) || ON_HAND.equals(q.qualifier()));
        if (adjustments && stock) {
            throw new MappingException("QTY", "an INVRPT mixes adjustments (QTY+191) and stock levels (QTY+145/17): send them separately");
        }
        List<StockEventRequest.Line> out = new ArrayList<>();
        for (Line l : lines) {
            for (Qty q : l.quantities) {
                if (adjustments && INVENTORY_ADJUSTMENT.equals(q.qualifier())) {
                    // 4501: 1 Movement out of inventory, 2 Movement into inventory
                    if (!"1".equals(q.direction4501()) && !"2".equals(q.direction4501())) {
                        throw new MappingException(at(q.segment()), "an adjustment (QTY+191) needs INV 4501 = 1 (out) or 2 (in)");
                    }
                    BigDecimal signed = "1".equals(q.direction4501()) ? q.value().negate() : q.value();
                    out.add(new StockEventRequest.Line(l.sku, location(h, l, q, List.of("WH"), defaultLocation), signed,
                            state(q, true), null, null, null, null, null));
                } else if (!adjustments && (ACTUAL_STOCK.equals(q.qualifier()) || ON_HAND.equals(q.qualifier()))) {
                    if (ON_HAND.equals(q.qualifier()) && q.type7491() == null) {
                        throw new MappingException(at(q.segment()), "a quantity on hand (QTY+17) needs INV 7491 to know its stock state");
                    }
                    out.add(new StockEventRequest.Line(l.sku, location(h, l, q, List.of("WH"), defaultLocation), q.value(),
                            state(q, ACTUAL_STOCK.equals(q.qualifier())), null, null, null, null, null));
                }
            }
        }
        if (out.isEmpty()) {
            throw new MappingException("QTY", "no stock level (QTY+145 or QTY+17) and no adjustment (QTY+191) in the INVRPT");
        }
        return new StockEventRequest(adjustments ? "ADJUSTMENT" : "SNAPSHOT", key, h.documentNumber, null,
                when(h, List.of("137")), out);
    }

    /** 7491: 1 Accepted product, 2 Damaged product, 3 Bonded, 4 Reserved inventory. */
    private static StockState state(Qty q, boolean availableWhenAbsent) {
        String type = q.type7491();
        if (type == null) {
            if (availableWhenAbsent) {
                return StockState.AVAILABLE;
            }
            throw new MappingException(at(q.segment()), "INV 7491 is needed to know which stock state is affected");
        }
        return switch (type) {
            case "1" -> StockState.AVAILABLE;
            case "2" -> StockState.QUARANTINE;
            case "4" -> StockState.RESERVED;
            case "3" -> throw new MappingException(at(q.segment()), "bonded inventory (INV 7491 = 3) is not handled by the stock module");
            default -> throw new MappingException(at(q.segment()), "unknown type of inventory INV 7491 = " + type);
        };
    }

    private static StockEventRequest.Line simple(Line l, String location, BigDecimal quantity) {
        return new StockEventRequest.Line(l.sku, location, quantity, null, null, null, null, null, null);
    }

    /** LOC+18 then LOC+14 on the quantity, the line, the header; then the GLN of a party; then the default. */
    private static String location(Header h, Line l, Qty q, List<String> parties, String defaultLocation) {
        List<Map<String, String>> levels = new ArrayList<>();
        if (q != null) {
            levels.add(q.locations());
        }
        levels.add(l.locations);
        levels.add(h.locations);
        for (Map<String, String> level : levels) {
            for (String qualifier : LOCATION_QUALIFIERS) {
                String value = blank(level.get(qualifier));
                if (value != null) {
                    return value;
                }
            }
        }
        for (String party : parties) {
            String gln = blank(h.parties.get(party));
            if (gln != null) {
                return gln;
            }
        }
        if (defaultLocation != null && !defaultLocation.isBlank()) {
            return defaultLocation.strip();
        }
        throw new MappingException(at(l.segment), "no stock location for item " + l.sku
                + " (LOC+18/14, NAD+" + String.join("/", parties) + ", or a default location)");
    }

    private static BigDecimal sum(Line l, List<String> qualifiers) {
        BigDecimal total = null;
        for (Qty q : l.quantities) {
            if (qualifiers.contains(q.qualifier())) {
                total = total == null ? q.value() : total.add(q.value());
            }
        }
        return total;
    }

    private static BigDecimal quantity(Segment s, char decimalMark) {
        String raw = s.value(0, 1).replace(decimalMark, '.').replace(',', '.');
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new MappingException(at(s), "quantity is not a number: " + s.value(0, 1));
        }
    }

    /** First date found among the qualifiers (2005), read in the business time zone (2379: 102, 203, 204). */
    private static OffsetDateTime when(Header h, List<String> qualifiers) {
        for (String qualifier : qualifiers) {
            String[] date = h.dates.get(qualifier);
            if (date != null && !date[0].isBlank()) {
                return parseDate(date[0], date[1], qualifier);
            }
        }
        throw new MappingException("DTM", "no date (DTM+" + String.join(" or DTM+", qualifiers) + ")");
    }

    private static OffsetDateTime parseDate(String value, String format, String qualifier) {
        try {
            LocalDateTime local = switch (format.isBlank() ? "102" : format) {
                case "102" -> LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay();
                case "203" -> LocalDateTime.parse(value, DateTimeFormatter.ofPattern("uuuuMMddHHmm"));
                case "204" -> LocalDateTime.parse(value, DateTimeFormatter.ofPattern("uuuuMMddHHmmss"));
                default -> throw new MappingException("DTM", "date format " + format + " is not supported (102, 203, 204)");
            };
            return local.atZone(TimeConfig.BUSINESS_ZONE).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            throw new MappingException("DTM", "DTM+" + qualifier + " is not a valid " + format + " date: " + value);
        }
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String at(Segment s) {
        return "segment " + s.position() + " " + s.tag();
    }
}
