package ma.mystix.flow;

import java.util.List;
import java.util.Optional;

/**
 * What a flow can be made of. {@code available} options are implemented end to end; planned ones are listed so the
 * portal can show the roadmap, but a flow cannot use them yet.
 */
public final class FlowCatalog {

    public record Option(String code, boolean available, String lot) {
    }

    /** An implemented mapping between a source and a target format. */
    public record Mapping(String sourceFormat, String targetFormat, String id, String version) {
    }

    public static final List<Option> SOURCE_CHANNELS = List.of(
            new Option("API", true, null),
            new Option("PORTAL", false, "3"),
            new Option("SFTP", false, "6"),
            new Option("AS2", false, "6"));

    public static final List<Option> SOURCE_FORMATS = List.of(
            new Option("JSON_CANONICAL", true, null),
            new Option("IDOC_INVOIC02", false, "6"),
            new Option("EDIFACT_INVOIC_D96A", false, "6"),
            new Option("CSV", false, "6"));

    public static final List<Option> TARGET_FORMATS = List.of(
            new Option("UBL_2_1", true, null),
            new Option("CII_D16B", false, "2"),
            new Option("EANCOM_INVOIC", false, "6"));

    public static final List<Option> TARGET_CHANNELS = List.of(
            new Option("API_RESPONSE", true, null),
            new Option("AS2", false, "6"),
            new Option("SFTP", false, "6"),
            new Option("WEBHOOK", false, "5"));

    public static final List<Mapping> MAPPINGS = List.of(
            new Mapping("JSON_CANONICAL", "UBL_2_1", "ubl-invoice", "1.0"));

    private FlowCatalog() {
    }

    static boolean isAvailable(List<Option> options, String code) {
        return options.stream().anyMatch(o -> o.code().equals(code) && o.available());
    }

    static Optional<Mapping> mappingFor(String sourceFormat, String targetFormat) {
        return MAPPINGS.stream()
                .filter(m -> m.sourceFormat().equals(sourceFormat) && m.targetFormat().equals(targetFormat))
                .findFirst();
    }
}
