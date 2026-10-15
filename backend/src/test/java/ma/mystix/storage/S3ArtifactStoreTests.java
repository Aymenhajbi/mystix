package ma.mystix.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import ma.mystix.TestcontainersConfiguration;
import ma.mystix.shared.Sha256;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.tenant.CompanyService;
import ma.mystix.tenant.Ice;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Artefacts in a real S3-compatible store (ADR-0017): MinIO in a container (the dev image, risk R-07), bucket created
 * with object lock as in docker-compose. An accepted invoice keeps its RAW, CANONICAL and OUT in the bucket, read back
 * through the API and checked; an object is never replaced; a later version written behind Mystix's back is not
 * served in place of the original.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings({"unchecked", "rawtypes"})
class S3ArtifactStoreTests {

    private static final String BUCKET = "mystix-artifacts-test";
    private static final String USER = "mystix-test";
    private static final String SECRET = "mystix-test-secret-only";

    static final GenericContainer<?> MINIO = new GenericContainer<>(
            "cgr.dev/chainguard/minio@sha256:2136d593beacf4461187035eca949fe49abd589d5079735025c711ca58fee0c8")
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", USER)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    static String endpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        MINIO.start();
        StorageProperties p = new StorageProperties("s3", endpoint(), null, BUCKET, USER, SECRET, 1, "GOVERNANCE");
        try (S3Client s3 = S3ArtifactStore.client(p)) {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).objectLockEnabledForBucket(true).build());
        }
        registry.add("mystix.storage.mode", () -> "s3");
        registry.add("mystix.storage.endpoint", S3ArtifactStoreTests::endpoint);
        registry.add("mystix.storage.bucket", () -> BUCKET);
        registry.add("mystix.storage.access-key", () -> USER);
        registry.add("mystix.storage.secret-key", () -> SECRET);
        registry.add("mystix.storage.retention-days", () -> "1");
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    CompanyService companies;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ArtifactStore store;

    RestClient http;
    UUID company;
    String request;
    S3Client s3;

    @BeforeAll
    void setUp() throws IOException {
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (r, response) -> { }).build();
        company = companies.list().stream().filter(c -> c.ice().equals(new Ice("000000001000011"))).findFirst()
                .orElseGet(() -> companies.register(new Ice("000000001000011"), "Fournisseur File Synthetique SARL",
                        null)).id();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("fixtures/api/invoice-basic.request.json")) {
            request = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        s3 = S3ArtifactStore.client(new StorageProperties("s3", endpoint(), null, BUCKET, USER, SECRET, 0, null));
    }

    @Test
    void anAcceptedInvoiceKeepsItsArtefactsInTheBucket() {
        byte[] body = request.replace("FA-2026-000123", "FA-S3-0001").getBytes(StandardCharsets.UTF_8);
        ResponseEntity<byte[]> created = http.post().uri("/api/v1/invoices").header("X-Mystix-Company-Id", company.toString())
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_XML).body(body)
                .retrieve().toEntity(byte[].class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = created.getHeaders().getFirst("X-Mystix-Invoice-Id");

        // In the bucket, not in the table; the table keeps key, version, size and SHA-256.
        Map<String, Object> row = jdbc.sql("""
                        SELECT content IS NULL AS external, storage_key, storage_version, size_bytes, sha256
                        FROM invoice_artifact WHERE message_id = :id AND kind = 'RAW'
                        """).param("id", UUID.fromString(id)).query().singleRow();
        assertThat(row).containsEntry("external", true).containsEntry("size_bytes", (long) body.length)
                .containsEntry("sha256", Sha256.hex(body));
        String key = (String) row.get("storage_key");
        assertThat(key).startsWith(company + "/invoices/").endsWith("/" + id + "/RAW");
        assertThat(row.get("storage_version")).isNotNull();
        HeadObjectResponse head = s3.headObject(b -> b.bucket(BUCKET).key(key));
        assertThat(head.metadata()).containsEntry("sha256", Sha256.hex(body));
        assertThat(head.objectLockModeAsString()).isEqualTo("GOVERNANCE");
        assertThat(head.objectLockRetainUntilDate()).isNotNull();

        // Read back through the API, byte for byte.
        ResponseEntity<byte[]> raw = http.get().uri("/api/v1/invoices/{id}/artifacts/RAW", id)
                .header("X-Mystix-Company-Id", company.toString()).retrieve().toEntity(byte[].class);
        assertThat(raw.getBody()).isEqualTo(body);
        ResponseEntity<byte[]> out = http.get().uri("/api/v1/invoices/{id}/artifacts/OUT", id)
                .header("X-Mystix-Company-Id", company.toString()).retrieve().toEntity(byte[].class);
        assertThat(out.getBody()).isEqualTo(created.getBody());

        // A new version written behind Mystix's back is not what Mystix serves: it reads the version it wrote.
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET).key(key).build(), RequestBody.fromString("tampered"));
        assertThat(http.get().uri("/api/v1/invoices/{id}/artifacts/RAW", id)
                .header("X-Mystix-Company-Id", company.toString()).retrieve().toEntity(byte[].class).getBody())
                .isEqualTo(body);
        assertThat(s3.getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET).key(key).build()).asUtf8String())
                .isEqualTo("tampered");
    }

    @Test
    void anObjectIsNeverReplaced() {
        String key = company + "/tests/" + UUID.randomUUID();
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        ArtifactStore.StoredObject stored = store.put(key, first, "text/plain", Sha256.hex(first));
        // The same bytes again: a replay, same object.
        assertThat(store.put(key, first, "text/plain", Sha256.hex(first)).versionId()).isEqualTo(stored.versionId());
        // Other bytes under the same key: refused.
        byte[] other = "other".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> store.put(key, other, "text/plain", Sha256.hex(other)))
                .isInstanceOfSatisfying(MystixException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.STORAGE_CONFLICT));
        // Bytes that do not match the expected SHA-256 are not served.
        assertThatThrownBy(() -> store.get(key, stored.versionId(), Sha256.hex(other)))
                .isInstanceOfSatisfying(MystixException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.STORAGE_INTEGRITY));
        assertThat(store.get(key, stored.versionId(), Sha256.hex(first))).isEqualTo(first);
    }

    @Test
    void anUnreachableStoreRefusesAndKeepsNothing() {
        StorageProperties down = new StorageProperties("s3", "http://127.0.0.1:1", null, BUCKET, USER, SECRET, 0, null);
        ArtifactStore unreachable = new S3ArtifactStore(down);
        byte[] bytes = "x".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> unreachable.put("k", bytes, "text/plain", Sha256.hex(bytes)))
                .isInstanceOfSatisfying(MystixException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.STORAGE_UNAVAILABLE);
                    assertThat(e.errorCode().retryable()).isTrue();
                });
    }
}
