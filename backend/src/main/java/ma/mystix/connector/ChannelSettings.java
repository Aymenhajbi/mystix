package ma.mystix.connector;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import ma.mystix.shared.error.ApiError;

/**
 * The parameters each protocol accepts (ADR-0015). Only known keys are kept: an unknown key is rejected, so a password
 * or a private key can never slip into {@code settings}; secrets go to the secret store, named by credentialRef.
 * Defaults are written into the stored settings, so what is shown is what will be used.
 */
final class ChannelSettings {

    enum Kind { TEXT, HOST, PORT, SECONDS, FLAG, URL, HTTPS_URL, AS2_ID, PATH, CHOICE }

    /** Where a parameter sits in the configuration screens: what an integrator fills in, in that order. */
    enum Group { CONNECTION, FILES, SECURITY, ADVANCED }

    private static final Map<String, Group> GROUPS = Map.ofEntries(
            Map.entry("host", Group.CONNECTION), Map.entry("port", Group.CONNECTION),
            Map.entry("username", Group.CONNECTION), Map.entry("url", Group.CONNECTION),
            Map.entry("localAs2Id", Group.CONNECTION), Map.entry("partnerAs2Id", Group.CONNECTION),
            Map.entry("method", Group.CONNECTION), Map.entry("events", Group.CONNECTION),
            Map.entry("tlsMode", Group.CONNECTION),
            Map.entry("inboundDirectory", Group.FILES), Map.entry("outboundDirectory", Group.FILES),
            Map.entry("filePattern", Group.FILES), Map.entry("pollSeconds", Group.FILES),
            Map.entry("afterDownload", Group.FILES),
            Map.entry("authMethod", Group.SECURITY), Map.entry("hostKeyFingerprint", Group.SECURITY),
            Map.entry("signing", Group.SECURITY), Map.entry("encryption", Group.SECURITY),
            Map.entry("mdn", Group.SECURITY), Map.entry("mdnSigned", Group.SECURITY),
            Map.entry("asyncMdnUrl", Group.SECURITY), Map.entry("authType", Group.SECURITY));

    /** Rarely changed parameters (passive mode, archive directory...) are folded under "advanced". */
    static Group groupOf(String key) {
        return GROUPS.getOrDefault(key, Group.ADVANCED);
    }

    record Field(String key, Kind kind, boolean required, Object defaultValue, List<String> choices) {

        static Field of(String key, Kind kind, boolean required) {
            return new Field(key, kind, required, null, List.of());
        }

        static Field withDefault(String key, Kind kind, Object defaultValue) {
            return new Field(key, kind, false, defaultValue, List.of());
        }

        static Field choice(String key, String defaultValue, String... choices) {
            return new Field(key, Kind.CHOICE, false, defaultValue, List.of(choices));
        }
    }

    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$|^\\[[0-9A-Fa-f:.]+]$");
    // RFC 4130 section 6.2: AS2-From and AS2-To are 1 to 128 printable ASCII characters.
    private static final Pattern AS2_ID = Pattern.compile("^[\\x21-\\x7E]([\\x20-\\x7E]{0,126}[\\x21-\\x7E])?$");
    private static final Pattern PATH = Pattern.compile("^[^\\x00-\\x1F]{1,255}$");

    private static final List<Field> FILE_COMMON = List.of(
            Field.of("host", Kind.HOST, true),
            Field.of("username", Kind.TEXT, true),
            Field.of("inboundDirectory", Kind.PATH, false),
            Field.of("outboundDirectory", Kind.PATH, false),
            Field.withDefault("filePattern", Kind.TEXT, "*"),
            Field.withDefault("pollSeconds", Kind.SECONDS, 300),
            Field.choice("afterDownload", "MOVE", "MOVE", "DELETE", "KEEP"),
            Field.of("archiveDirectory", Kind.PATH, false));

    static final Map<Channel.Protocol, List<Field>> FIELDS = Map.of(
            Channel.Protocol.SFTP, concat(FILE_COMMON, List.of(
                    Field.withDefault("port", Kind.PORT, 22),
                    Field.choice("authMethod", "PRIVATE_KEY", "PRIVATE_KEY", "PASSWORD"),
                    Field.of("hostKeyFingerprint", Kind.TEXT, false))),
            Channel.Protocol.FTP, concat(FILE_COMMON, List.of(
                    Field.withDefault("port", Kind.PORT, 21),
                    Field.withDefault("passiveMode", Kind.FLAG, true),
                    // EDI files are moved byte for byte: ASCII mode would rewrite line endings.
                    Field.choice("transferMode", "BINARY", "BINARY", "ASCII"))),
            Channel.Protocol.FTPS, concat(FILE_COMMON, List.of(
                    Field.withDefault("port", Kind.PORT, 21),
                    Field.choice("tlsMode", "EXPLICIT", "EXPLICIT", "IMPLICIT"),
                    Field.withDefault("passiveMode", Kind.FLAG, true),
                    Field.choice("transferMode", "BINARY", "BINARY", "ASCII"))),
            Channel.Protocol.AS2, List.of(
                    Field.of("url", Kind.URL, false),
                    Field.of("localAs2Id", Kind.AS2_ID, true),
                    Field.of("partnerAs2Id", Kind.AS2_ID, true),
                    Field.choice("signing", "SHA-256", "SHA-256", "SHA-384", "SHA-512", "SHA-1", "NONE"),
                    Field.choice("encryption", "AES256_CBC", "AES128_CBC", "AES192_CBC", "AES256_CBC", "3DES",
                            "NONE"),
                    Field.withDefault("compression", Kind.FLAG, false),
                    Field.choice("mdn", "SYNC", "SYNC", "ASYNC", "NONE"),
                    Field.withDefault("mdnSigned", Kind.FLAG, true),
                    Field.of("asyncMdnUrl", Kind.URL, false),
                    Field.of("partnerCertificateRef", Kind.TEXT, false)),
            Channel.Protocol.HTTPS, List.of(
                    Field.of("url", Kind.HTTPS_URL, true),
                    Field.choice("method", "POST", "POST", "PUT"),
                    Field.choice("authType", "NONE", "NONE", "BASIC", "BEARER", "MTLS"),
                    Field.of("contentType", Kind.TEXT, false)),
            Channel.Protocol.WEBHOOK, List.of(
                    Field.of("url", Kind.HTTPS_URL, true),
                    Field.of("events", Kind.TEXT, false)));

    private ChannelSettings() {
    }

    /** Checked and completed settings, or the list of violations (field names are prefixed with "settings."). */
    record Result(Map<String, Object> settings, List<ApiError.FieldViolation> violations) {
    }

    static Result check(Channel.Protocol protocol, Channel.Direction direction, Map<String, Object> input) {
        Map<String, Object> raw = input == null ? Map.of() : input;
        List<Field> fields = FIELDS.get(protocol);
        List<ApiError.FieldViolation> violations = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : raw.keySet()) {
            if (fields.stream().noneMatch(f -> f.key().equals(key))) {
                violations.add(violation(key, "unknown parameter for " + protocol
                        + "; secrets go to the secret store, named by credentialRef"));
            }
        }
        for (Field f : fields) {
            Object value = raw.get(f.key());
            if (value instanceof String s) {
                value = s.isBlank() ? null : s.strip();
            }
            if (value == null) {
                if (f.required()) {
                    violations.add(violation(f.key(), "required for " + protocol));
                } else if (f.defaultValue() != null) {
                    out.put(f.key(), f.defaultValue());
                }
                continue;
            }
            String problem = problem(f, value);
            if (problem != null) {
                violations.add(violation(f.key(), problem));
            } else {
                out.put(f.key(), normalize(f, value));
            }
        }
        if (protocol == Channel.Protocol.FTPS && "IMPLICIT".equals(out.get("tlsMode")) && raw.get("port") == null) {
            out.put("port", 990);
        }
        if (protocol == Channel.Protocol.AS2) {
            if (direction != Channel.Direction.IN && !out.containsKey("url")) {
                violations.add(violation("url", "the partner AS2 endpoint is required to send"));
            }
            if ("ASYNC".equals(out.get("mdn")) && !out.containsKey("asyncMdnUrl")) {
                violations.add(violation("asyncMdnUrl", "required when the MDN is asynchronous"));
            }
        }
        return new Result(out, violations);
    }

    private static String problem(Field f, Object value) {
        return switch (f.kind()) {
            case TEXT -> value instanceof String s && s.length() <= 255 ? null : "text of at most 255 characters";
            case PATH -> value instanceof String s && PATH.matcher(s).matches() ? null : "a path of 1 to 255 characters";
            case HOST -> value instanceof String s && HOST.matcher(s).matches() ? null : "a host name or an IP address";
            case AS2_ID -> value instanceof String s && AS2_ID.matcher(s).matches()
                    ? null : "1 to 128 printable ASCII characters (RFC 4130)";
            case PORT -> integer(value) instanceof Integer i && i >= 1 && i <= 65535 ? null : "a port from 1 to 65535";
            case SECONDS -> integer(value) instanceof Integer i && i >= 30 && i <= 86400
                    ? null : "seconds from 30 to 86400";
            case FLAG -> value instanceof Boolean ? null : "true or false";
            case URL -> url(value, false) ? null : "an http or https URL";
            case HTTPS_URL -> url(value, true) ? null : "an https URL";
            case CHOICE -> f.choices().contains(String.valueOf(value)) ? null : "one of " + f.choices();
        };
    }

    private static Object normalize(Field f, Object value) {
        return switch (f.kind()) {
            case PORT, SECONDS -> integer(value);
            case CHOICE -> String.valueOf(value);
            default -> value;
        };
    }

    private static Integer integer(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                && Math.abs(n.doubleValue()) < Integer.MAX_VALUE) {
            return n.intValue();
        }
        if (value instanceof String s && s.matches("^[0-9]{1,9}$")) {
            return Integer.parseInt(s);
        }
        return null;
    }

    private static boolean url(Object value, boolean httpsOnly) {
        if (!(value instanceof String s) || s.length() > 2048) {
            return false;
        }
        try {
            URI uri = new URI(s);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            boolean schemeOk = httpsOnly ? scheme.equals("https") : scheme.equals("https") || scheme.equals("http");
            return schemeOk && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static ApiError.FieldViolation violation(String key, String reason) {
        return new ApiError.FieldViolation("settings." + key, reason);
    }

    private static List<Field> concat(List<Field> a, List<Field> b) {
        List<Field> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }
}
