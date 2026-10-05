package ma.mystix.format.edifact;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a UN/EDIFACT interchange (ISO 9735 syntax 3/4): optional UNA service string advice, release character,
 * character set from the UNB syntax identifier, UNH…UNT messages, UNT and UNZ control counts. Pure Java, no Spring.
 * Each message keeps its exact source bytes (line breaks included), so its SHA-256 matches what was received.
 */
public final class EdifactParser {

    /** Syntax identifier (UNB 0001) to character set; others are refused rather than guessed. */
    private static final Map<String, Charset> CHARSETS = Map.of(
            "UNOA", StandardCharsets.US_ASCII,
            "UNOB", StandardCharsets.US_ASCII,
            "UNOC", StandardCharsets.ISO_8859_1,
            "UNOY", StandardCharsets.UTF_8);

    private EdifactParser() {
    }

    /** One message, UNH to UNT inclusive, with its exact bytes in the interchange character set. */
    public record Message(String reference, String type, String version, String release, String agency,
                          String association, List<Segment> segments, byte[] raw) {
    }

    /**
     * @param decimalMark decimal mark of the interchange (UNA, else "."); "," is also accepted in numbers
     */
    public record Interchange(String syntax, Charset charset, char decimalMark, String sender, String recipient,
                              String controlReference, List<Message> messages) {
    }

    private record Delimiters(char component, char element, char decimal, char release, char terminator) {
    }

    public static Interchange parse(byte[] bytes) {
        // ISO-8859-1 maps every byte to one char: enough to read UNA and the syntax identifier safely.
        String probe = new String(bytes, StandardCharsets.ISO_8859_1);
        int start = 0;
        while (start < probe.length() && Character.isWhitespace(probe.charAt(start))) {
            start++;
        }
        Delimiters d = new Delimiters(':', '+', '.', '?', '\'');
        int bodyStart = start;
        if (probe.startsWith("UNA", start)) {
            if (probe.length() < start + 9) {
                throw new EdifactException("UNA service string advice is incomplete", 1, "UNA");
            }
            d = new Delimiters(probe.charAt(start + 3), probe.charAt(start + 4), probe.charAt(start + 5),
                    probe.charAt(start + 6), probe.charAt(start + 8));
            bodyStart = start + 9;
        }
        int unb = probe.indexOf("UNB" + d.element(), bodyStart);
        if (unb < 0) {
            throw new EdifactException("No UNB interchange header", 1, "UNB");
        }
        int afterTag = unb + 4;
        int stop = afterTag;
        while (stop < probe.length() && probe.charAt(stop) != d.component() && probe.charAt(stop) != d.element()
                && probe.charAt(stop) != d.terminator()) {
            stop++;
        }
        String syntax = probe.substring(afterTag, stop);
        Charset charset = CHARSETS.get(syntax);
        if (charset == null) {
            throw new EdifactException("Unsupported syntax identifier " + syntax + " (UNOA, UNOB, UNOC, UNOY)", 1, "UNB");
        }
        // The prefix (leading whitespace, UNA) is ASCII: its char count is its byte count in every supported charset.
        String text = new String(bytes, charset);
        List<Segment> segments = split(text, bodyStart, d);
        return assemble(text, segments, syntax, charset, d.decimal());
    }

    /** Splits into segments, honouring the release character; line breaks between segments are skipped. */
    static List<Segment> split(String text, int from, Delimiters d) {
        List<Segment> segments = new ArrayList<>();
        int segmentStart = -1;
        boolean released = false;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (segmentStart < 0) {
                if (c == '\r' || c == '\n' || c == ' ' || c == '\t') {
                    continue;
                }
                segmentStart = i;
            }
            if (released) {
                released = false;
            } else if (c == d.release()) {
                released = true;
            } else if (c == d.terminator()) {
                segments.add(segment(text.substring(segmentStart, i), segments.size() + 1, segmentStart, i + 1, d));
                segmentStart = -1;
            }
        }
        if (segmentStart >= 0) {
            String rest = text.substring(segmentStart);
            throw new EdifactException("Segment not terminated", segments.size() + 1,
                    rest.length() >= 3 ? rest.substring(0, 3) : rest);
        }
        return segments;
    }

    private static Segment segment(String body, int position, int start, int end, Delimiters d) {
        List<List<String>> elements = new ArrayList<>();
        List<String> components = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean released = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (released) {
                value.append(c);
                released = false;
            } else if (c == d.release()) {
                released = true;
            } else if (c == d.element()) {
                components.add(value.toString());
                elements.add(List.copyOf(components));
                components = new ArrayList<>();
                value.setLength(0);
            } else if (c == d.component()) {
                components.add(value.toString());
                value.setLength(0);
            } else {
                value.append(c);
            }
        }
        components.add(value.toString());
        elements.add(List.copyOf(components));
        String tag = elements.getFirst().getFirst();
        return new Segment(tag, List.copyOf(elements.subList(1, elements.size())), position, start, end);
    }

    private static Interchange assemble(String text, List<Segment> segments, String syntax, Charset charset,
                                        char decimal) {
        if (segments.isEmpty() || !"UNB".equals(segments.getFirst().tag())) {
            throw new EdifactException("The interchange must start with UNB", 1,
                    segments.isEmpty() ? "" : segments.getFirst().tag());
        }
        Segment unb = segments.getFirst();
        Segment unz = segments.getLast();
        if (segments.size() < 2 || !"UNZ".equals(unz.tag())) {
            throw new EdifactException("The interchange must end with UNZ", unz.position(), unz.tag());
        }
        String controlReference = unb.value(4, 0);
        List<Message> messages = new ArrayList<>();
        List<Segment> current = null;
        for (Segment s : segments.subList(1, segments.size() - 1)) {
            switch (s.tag()) {
                case "UNH" -> {
                    if (current != null) {
                        throw new EdifactException("UNH before the UNT of the previous message", s.position(), "UNH");
                    }
                    current = new ArrayList<>(List.of(s));
                }
                case "UNT" -> {
                    if (current == null) {
                        throw new EdifactException("UNT without UNH", s.position(), "UNT");
                    }
                    current.add(s);
                    messages.add(message(text, current, charset));
                    current = null;
                }
                case "UNG", "UNE" ->
                        throw new EdifactException("Functional groups (UNG/UNE) are not supported", s.position(), s.tag());
                default -> {
                    if (current == null) {
                        throw new EdifactException("Segment outside a message", s.position(), s.tag());
                    }
                    current.add(s);
                }
            }
        }
        if (current != null) {
            throw new EdifactException("Message without UNT", current.getFirst().position(), "UNH");
        }
        int declared = count(unz.value(0, 0), unz);
        if (declared != messages.size()) {
            throw new EdifactException("UNZ declares " + declared + " message(s), the interchange has "
                    + messages.size(), unz.position(), "UNZ");
        }
        if (!controlReference.equals(unz.value(1, 0))) {
            throw new EdifactException("UNZ control reference " + unz.value(1, 0) + " differs from UNB "
                    + controlReference, unz.position(), "UNZ");
        }
        return new Interchange(syntax, charset, decimal, unb.value(1, 0), unb.value(2, 0), controlReference,
                List.copyOf(messages));
    }

    private static Message message(String text, List<Segment> segments, Charset charset) {
        Segment unh = segments.getFirst();
        Segment unt = segments.getLast();
        int declared = count(unt.value(0, 0), unt);
        if (declared != segments.size()) {
            throw new EdifactException("UNT declares " + declared + " segment(s), the message has " + segments.size(),
                    unt.position(), "UNT");
        }
        if (!unh.value(0, 0).equals(unt.value(1, 0))) {
            throw new EdifactException("UNT reference " + unt.value(1, 0) + " differs from UNH " + unh.value(0, 0),
                    unt.position(), "UNT");
        }
        byte[] raw = text.substring(unh.start(), unt.end()).getBytes(charset);
        return new Message(unh.value(0, 0), unh.value(1, 0), unh.value(1, 1), unh.value(1, 2), unh.value(1, 3),
                unh.value(1, 4), List.copyOf(segments), raw);
    }

    private static int count(String value, Segment segment) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new EdifactException("Count is not a number: " + value, segment.position(), segment.tag());
        }
    }
}
