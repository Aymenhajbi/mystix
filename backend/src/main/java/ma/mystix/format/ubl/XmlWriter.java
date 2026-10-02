package ma.mystix.format.ubl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Minimal deterministic XML writer: two-space indentation, LF line endings, attributes in call order.
 * Byte-for-byte stable output is required for fixture comparison, hashes and signatures.
 */
final class XmlWriter {

    private final StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    private final Deque<String> open = new ArrayDeque<>();
    private boolean startTagOpen;

    XmlWriter start(String name, String... attributes) {
        closeStartTag(true);
        indent();
        out.append('<').append(name);
        for (int i = 0; i < attributes.length; i += 2) {
            if (attributes[i + 1] != null) {
                out.append(' ').append(attributes[i]).append("=\"").append(escape(attributes[i + 1])).append('"');
            }
        }
        open.push(name);
        startTagOpen = true;
        return this;
    }

    XmlWriter end() {
        String name = open.pop();
        if (startTagOpen) {
            out.append("/>\n");
            startTagOpen = false;
        } else {
            indent();
            out.append("</").append(name).append(">\n");
        }
        return this;
    }

    /** Writes {@code <name attrs>text</name>}; does nothing when {@code text} is null. */
    XmlWriter element(String name, String text, String... attributes) {
        if (text == null) {
            return this;
        }
        start(name, attributes);
        out.append('>').append(escape(text)).append("</").append(name).append(">\n");
        open.pop();
        startTagOpen = false;
        return this;
    }

    byte[] toBytes() {
        if (!open.isEmpty()) {
            throw new IllegalStateException("Unclosed elements: " + open);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void closeStartTag(boolean newline) {
        if (startTagOpen) {
            out.append('>');
            if (newline) {
                out.append('\n');
            }
            startTagOpen = false;
        }
    }

    private void indent() {
        out.append("  ".repeat(open.size()));
    }

    private static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
