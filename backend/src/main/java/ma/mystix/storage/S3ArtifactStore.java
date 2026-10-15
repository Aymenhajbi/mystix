package ma.mystix.storage;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import ma.mystix.shared.Sha256;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Artefacts in an S3-compatible store (ADR-0017): conditional write ({@code If-None-Match: *}) so an object is never
 * replaced, optional object lock retention, read back by version id and checked against its SHA-256. Written for the
 * S3 API, not for MinIO: any compatible store works (risk R-07).
 */
class S3ArtifactStore implements ArtifactStore {

    private static final Logger LOG = LoggerFactory.getLogger(S3ArtifactStore.class);
    static final String SHA256_METADATA = "sha256";

    private final S3Client s3;
    private final StorageProperties properties;

    S3ArtifactStore(StorageProperties properties) {
        this(client(properties), properties);
    }

    S3ArtifactStore(S3Client s3, StorageProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    static S3Client client(StorageProperties p) {
        if (p.accessKey() == null || p.accessKey().isBlank() || p.secretKey() == null || p.secretKey().isBlank()) {
            throw new IllegalStateException("mystix.storage.mode=s3 needs mystix.storage.access-key and secret-key");
        }
        S3ClientBuilder builder = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(30)))
                .region(Region.of(p.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(p.accessKey(),
                        p.secretKey())))
                .forcePathStyle(true);
        if (p.endpoint() != null && !p.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(p.endpoint()));
        }
        return builder.build();
    }

    /** Fails the start when the bucket is not there: s3 mode was asked for, so no artefact may go elsewhere. */
    void checkBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(properties.bucket()).build());
            LOG.info("Artifact store: S3 bucket {} at {}", properties.bucket(),
                    properties.endpoint() == null || properties.endpoint().isBlank() ? "AWS" : properties.endpoint());
        } catch (SdkException e) {
            throw new IllegalStateException("S3 bucket " + properties.bucket() + " unreachable: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean external() {
        return true;
    }

    @Override
    public StoredObject put(String key, byte[] content, String mediaType, String sha256) {
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(properties.bucket()).key(key).contentType(mediaType)
                .ifNoneMatch("*")
                .metadata(Map.of(SHA256_METADATA, sha256));
        if (properties.retentionDays() > 0) {
            request.objectLockMode(ObjectLockMode.fromValue(properties.retentionMode()))
                    .objectLockRetainUntilDate(Instant.now().plus(Duration.ofDays(properties.retentionDays())));
        }
        try {
            PutObjectResponse response = s3.putObject(request.build(), RequestBody.fromBytes(content));
            return new StoredObject(key, response.versionId(), content.length);
        } catch (S3Exception e) {
            if (e.statusCode() == 412 || e.statusCode() == 409) {
                return existing(key, sha256, e);
            }
            throw unavailable("put " + key, e);
        } catch (SdkException e) {
            throw unavailable("put " + key, e);
        }
    }

    /** The key is taken: same bytes, a replay; other bytes, a refusal. */
    private StoredObject existing(String key, String sha256, S3Exception cause) {
        HeadObjectResponse head;
        try {
            head = s3.headObject(HeadObjectRequest.builder().bucket(properties.bucket()).key(key).build());
        } catch (SdkException e) {
            throw unavailable("head " + key, e);
        }
        if (!sha256.equals(head.metadata().get(SHA256_METADATA))) {
            throw new MystixException(ErrorCode.STORAGE_CONFLICT, "Object " + key + " exists with other bytes", cause);
        }
        return new StoredObject(key, head.versionId(), head.contentLength());
    }

    @Override
    public byte[] get(String key, String versionId, String sha256) {
        byte[] bytes;
        try {
            bytes = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(properties.bucket()).key(key)
                    .versionId(versionId).build()).asByteArray();
        } catch (SdkException e) {
            throw unavailable("get " + key, e);
        }
        if (!Sha256.hex(bytes).equals(sha256)) {
            throw new MystixException(ErrorCode.STORAGE_INTEGRITY, "Object " + key + " does not match its SHA-256");
        }
        return bytes;
    }

    private static MystixException unavailable(String what, SdkException e) {
        LOG.warn("Artifact store: {} failed: {}", what, e.getMessage());
        return new MystixException(ErrorCode.STORAGE_UNAVAILABLE, "S3 " + what + " failed: " + e.getMessage(), e);
    }
}
