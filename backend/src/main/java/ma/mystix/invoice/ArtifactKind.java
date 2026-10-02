package ma.mystix.invoice;

import org.springframework.http.MediaType;

/** Processing artefacts kept for every accepted invoice (ADR-0002). */
public enum ArtifactKind {
    /** Request body exactly as received. */
    RAW(MediaType.APPLICATION_JSON_VALUE),
    /** Canonical model serialized as JSON, with its version. */
    CANONICAL(MediaType.APPLICATION_JSON_VALUE),
    /** UBL 2.1 output. */
    OUT(MediaType.APPLICATION_XML_VALUE);

    private final String mediaType;

    ArtifactKind(String mediaType) {
        this.mediaType = mediaType;
    }

    public String mediaType() {
        return mediaType;
    }
}
