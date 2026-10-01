package ma.mystix.shared.error;

/** Processing stage where an error happened. */
public enum Stage {
    /** Request decoding and field validation. */
    API,
    /** Input to canonical model. */
    MAPPING,
    /** Business rules on the produced document (EN 16931). */
    VALIDATION,
    /** Output generation and schema validation. */
    GENERATION
}
