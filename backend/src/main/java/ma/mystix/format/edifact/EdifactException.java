package ma.mystix.format.edifact;

/** A syntax error in an interchange, with the position (1-based) and tag of the faulty segment. */
public class EdifactException extends RuntimeException {

    private final int segmentPosition;
    private final String segmentTag;

    public EdifactException(String message, int segmentPosition, String segmentTag) {
        super(message);
        this.segmentPosition = segmentPosition;
        this.segmentTag = segmentTag;
    }

    public int segmentPosition() {
        return segmentPosition;
    }

    public String segmentTag() {
        return segmentTag;
    }
}
