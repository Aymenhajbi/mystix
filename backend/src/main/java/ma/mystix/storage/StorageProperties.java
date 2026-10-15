package ma.mystix.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Artefact storage ({@code mystix.storage.*}, ADR-0017).
 *
 * @param mode          {@code database} (bytes in PostgreSQL, the default) or {@code s3}
 * @param endpoint      S3 endpoint, e.g. http://127.0.0.1:9000 for the dev MinIO; empty for AWS itself
 * @param region        S3 region; MinIO accepts any
 * @param bucket        bucket created with versioning and object lock (see docker-compose.dev.yml, minio-init)
 * @param accessKey     access key, from the environment, never in a committed file
 * @param secretKey     secret key, from the environment, never in a committed file
 * @param retentionDays object lock retention of each artefact, 0 for none. TODO(DGI-SPEC): the legal retention period
 *                      of e-invoices is not confirmed (probably 10 years); set it when the texts are published
 * @param retentionMode GOVERNANCE (an administrator with the right permission can lift it) or COMPLIANCE (nobody can)
 */
@ConfigurationProperties("mystix.storage")
public record StorageProperties(String mode, String endpoint, String region, String bucket, String accessKey,
                                String secretKey, Integer retentionDays, String retentionMode) {

    public StorageProperties {
        mode = mode == null || mode.isBlank() ? "database" : mode.strip().toLowerCase(java.util.Locale.ROOT);
        region = region == null || region.isBlank() ? "us-east-1" : region;
        bucket = bucket == null || bucket.isBlank() ? "mystix-artifacts" : bucket;
        retentionDays = retentionDays == null ? 0 : retentionDays;
        retentionMode = retentionMode == null || retentionMode.isBlank() ? "GOVERNANCE" : retentionMode;
    }

    boolean s3() {
        return "s3".equals(mode);
    }
}
