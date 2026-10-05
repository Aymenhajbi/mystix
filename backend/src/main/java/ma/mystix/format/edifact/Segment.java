package ma.mystix.format.edifact;

import java.util.List;

/**
 * One EDIFACT segment: its tag, its data elements (each a list of components, release characters resolved),
 * its position in the interchange (1 = first segment after UNA), and where it starts and ends in the source text
 * (end exclusive, after the terminator).
 */
public record Segment(String tag, List<List<String>> elements, int position, int start, int end) {

    /** Component {@code component} of data element {@code element} (both 0-based), or "" when absent. */
    public String value(int element, int component) {
        if (element >= elements.size()) {
            return "";
        }
        List<String> components = elements.get(element);
        return component < components.size() ? components.get(component) : "";
    }
}
