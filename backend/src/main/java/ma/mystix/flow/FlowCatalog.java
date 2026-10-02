package ma.mystix.flow;

import java.util.List;
import java.util.Optional;

/**
 * What a flow can be made of, per direction. OUT: the client sends (to customers, the tax authority...).
 * IN: the client receives (from suppliers...). {@code available} options are implemented end to end; planned ones
 * carry the lot where they arrive. A flow may be declared with planned options, but only runs once every option is
 * available and a mapping exists (ADR-0007, amended).
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

    public static final List<Mapping> MAPPINGS = List.of(
            new Mapping("OUT", "JSON_CANONICAL", "UBL_2_1", "ubl-invoice", "1.0"));

    private FlowCatalog() {
    }

    static DirectionCatalog of(String direction) {
        return "IN".equals(direction) ? IN : "OUT".equals(direction) ? OUT : null;
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
        DirectionCatalog c = of(f.direction());
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
