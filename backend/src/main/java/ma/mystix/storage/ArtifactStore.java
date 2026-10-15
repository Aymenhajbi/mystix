package ma.mystix.storage;

/**
 * Where artefact bytes live (ADR-0017). In {@code database} mode they stay in their table and this store is not
 * used; in {@code s3} mode they go to an S3-compatible object store, written once and never overwritten, and the
 * table keeps the key, the version and the SHA-256.
 */
public interface ArtifactStore {

    /** True when artefacts are written to this store instead of the database. */
    boolean external();

    /**
     * Writes an object once. Writing again the same key with the same bytes is a no-op (a replay); with other bytes it
     * is refused ({@code STORAGE_CONFLICT}): an artefact never changes.
     */
    StoredObject put(String key, byte[] content, String mediaType, String sha256);

    /** Reads the version written by {@link #put}; refuses bytes whose SHA-256 differs ({@code STORAGE_INTEGRITY}). */
    byte[] get(String key, String versionId, String sha256);

    /** @param versionId the store's version of the object, null when the store keeps none */
    record StoredObject(String key, String versionId, long size) {
    }
}
