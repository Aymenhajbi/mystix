package ma.mystix.flow;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a flow can be made of, per document type and direction. OUT: the client sends (to customers, the tax
 * authority...). IN: the client receives (from suppliers...). {@code available} options are implemented end to end;
 * planned ones carry the lot where they arrive. A flow may be declared with planned options, but only runs once every
 * option is available and a mapping exists (ADR-0007, amended).
 * <p>
 * Two modules: invoicing (INVOICE) and supply chain, whose EDIFACT D96A messages feed the stock ledger (ADR-0012,
 * amendment 2). Stock flows are activated and paused like invoice flows.
 */
public final class FlowCatalog {

    public record Option(String code, boolean available, String lot) {
    }

    /** An implemented mapping between a source and a target format, in one direction. */
    public record Mapping(String direction, String sourceFormat, String targetFormat, String id, String version) {
    }

    public record DirectionCatalog(List<Option> sourceChannels, List<Option> sourceFormats, List<Option> targetFormats,
                                   List<Option> targetChannels) {
    }

    /** A document type and the module it belongs to, with its catalog per direction. */
    public record Document(String code, String module, DirectionCatalog out, DirectionCatalog in) {
    }

    public static final String INVOICE = "INVOICE";
    public static final String INVOICING = "INVOICING";
    public static final String SUPPLY_CHAIN = "SUPPLY_CHAIN";

    /** The stock ledger: the target of every supply chain flow. */
    public static final String STOCK_LEDGER = "STOCK_LEDGER";
    public static final String LEDGER = "LEDGER";
    public static final String STOCK_MAPPING = "stock-edifact-d96a";

    public static final DirectionCatalog OUT = new DirectionCatalog(
            List.of(new Option("API", true, null), new Option("PORTAL", false, "3"),
                    new Option("SFTP", false, "6"), new Option("AS2", false, "6")),
            List.of(new Option("JSON_CANONICAL", true, null), new Option("IDOC_INVOIC02", false, "6"),
                    new Option("EDIFACT_INVOIC_D96A", false, "6"), new Option("CSV", false, "6")),
            List.of(new Option("UBL_2_1", true, null), new Option("CII_D16B", false, "2"),
                    new Option("EANCOM_INVOIC", false, "6")),
            List.of(new Option("API_RESPONSE", true, null), new Option("AS2", false, "6"),
                    new Option("SFTP", false, "6"), new Option("WEBHOOK", false, "5")));

    public static final DirectionCatalog IN = new DirectionCatalog(
            List.of(new Option("AS2", false, "6"), new Option("SFTP", false, "6"),
                    new Option("API", false, "5"), new Option("PORTAL", false, "3")),
            List.of(new Option("UBL_2_1", false, "6"), new Option("CII_D16B", false, "6"),
                    new Option("EDIFACT_INVOIC_D96A", false, "6"), new Option("EANCOM_INVOIC", false, "6")),
            List.of(new Option("IDOC_INVOIC02", false, "6"), new Option("JSON_CANONICAL", false, "5"),
                    new Option("CSV", false, "6")),
            List.of(new Option("SFTP", false, "6"), new Option("WEBHOOK", false, "5"),
                    new Option("API_PULL", false, "5")));

    /** The EDIFACT D96A message types the stock ledger reads (ADR-0012). */
    public static final List<String> STOCK_DOCUMENTS = List.of("DESADV", "RECADV", "ORDERS", "INVRPT");

    public static final List<Document> DOCUMENTS = documents();

    public static final List<Mapping> MAPPINGS = mappings();

    private FlowCatalog() {
    }

    /** Source format of a stock document: one EDIFACT D96A message type. */
    public static String stockFormat(String documentType) {
        return "EDIFACT_" + documentType + "_D96A";
    }

    private static DirectionCatalog stockCatalog(String documentType) {
        return new DirectionCatalog(
                List.of(new Option("API", true, null), new Option("AS2", false, "6"), new Option("SFTP", false, "6")),
                List.of(new Option(stockFormat(documentType), true, null)),
                List.of(new Option(STOCK_LEDGER, true, null)),
                List.of(new Option(LEDGER, true, null)));
    }

    private static List<Document> documents() {
        var list = new java.util.ArrayList<Document>();
        list.add(new Document(INVOICE, INVOICING, OUT, IN));
        for (String type : STOCK_DOCUMENTS) {
            DirectionCatalog c = stockCatalog(type);
            list.add(new Document(type, SUPPLY_CHAIN, c, c));
        }
        return List.copyOf(list);
    }

    private static List<Mapping> mappings() {
        var list = new java.util.ArrayList<Mapping>();
        list.add(new Mapping("OUT", "JSON_CANONICAL", "UBL_2_1", "ubl-invoice", "1.0"));
        for (String type : STOCK_DOCUMENTS) {
            for (String direction : List.of("OUT", "IN")) {
                list.add(new Mapping(direction, stockFormat(type), STOCK_LEDGER, STOCK_MAPPING, "1.0"));
            }
        }
        return List.copyOf(list);
    }

    private static final Map<String, Document> BY_CODE = DOCUMENTS.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(Document::code, d -> d));

    static Optional<Document> document(String documentType) {
        return Optional.ofNullable(documentType).map(BY_CODE::get);
    }

    public static boolean isStock(String documentType) {
        return STOCK_DOCUMENTS.contains(documentType);
    }

    static DirectionCatalog of(String documentType, String direction) {
        return document(documentType)
                .map(d -> "IN".equals(direction) ? d.in() : "OUT".equals(direction) ? d.out() : null)
                .orElse(null);
    }

    static Optional<Option> option(List<Option> options, String code) {
        return options.stream().filter(o -> o.code().equals(code)).findFirst();
    }

    static Optional<Mapping> mappingFor(String direction, String sourceFormat, String targetFormat) {
        return MAPPINGS.stream()
                .filter(m -> m.direction().equals(direction) && m.sourceFormat().equals(sourceFormat)
                        && m.targetFormat().equals(targetFormat))
                .findFirst();
    }

    /** True when every option of the flow is available and a mapping exists: the flow can run. */
    public static boolean executable(ExchangeFlow f) {
        DirectionCatalog c = of(f.documentType(), f.direction());
        return c != null
                && available(c.sourceChannels(), f.sourceChannel())
                && available(c.sourceFormats(), f.sourceFormat())
                && available(c.targetFormats(), f.targetFormat())
                && available(c.targetChannels(), f.targetChannel())
                && mappingFor(f.direction(), f.sourceFormat(), f.targetFormat()).isPresent();
    }

    private static boolean available(List<Option> options, String code) {
        return option(options, code).map(Option::available).orElse(false);
    }
}
