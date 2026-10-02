package ma.mystix.invoice;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import ma.mystix.shared.Sha256;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Every query is scoped by {@code company_id}: a company never reads another company's invoices. */
@Repository
class InvoiceRepository {

    private static final RowMapper<StoredInvoice> INVOICE = (rs, n) -> new StoredInvoice(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("invoice_number"),
            rs.getObject("issue_date", LocalDate.class),
            rs.getString("status"),
            rs.getString("canonical_version"),
            rs.getString("canonical_sha256"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    InvoiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the invoice and its artefacts atomically. Throws DuplicateKeyException if the number exists. */
    @Transactional
    void insert(StoredInvoice invoice, Map<ArtifactKind, byte[]> artifacts) {
        jdbc.sql("""
                INSERT INTO invoice_message (id, company_id, invoice_number, issue_date, status,
                                             canonical_version, canonical_sha256, created_at)
                VALUES (:id, :companyId, :number, :issueDate, :status, :canonicalVersion, :canonicalSha256, :createdAt)
                """)
                .param("id", invoice.id())
                .param("companyId", invoice.companyId())
                .param("number", invoice.number())
                .param("issueDate", invoice.issueDate())
                .param("status", invoice.status())
                .param("canonicalVersion", invoice.canonicalVersion())
                .param("canonicalSha256", invoice.canonicalSha256())
                .param("createdAt", invoice.createdAt())
                .update();
        artifacts.forEach((kind, content) -> jdbc.sql("""
                        INSERT INTO invoice_artifact (id, message_id, company_id, kind, media_type, content, sha256,
                                                      created_at)
                        VALUES (:id, :messageId, :companyId, :kind, :mediaType, :content, :sha256, :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("messageId", invoice.id())
                .param("companyId", invoice.companyId())
                .param("kind", kind.name())
                .param("mediaType", kind.mediaType())
                .param("content", content)
                .param("sha256", Sha256.hex(content))
                .param("createdAt", invoice.createdAt())
                .update());
    }

    Optional<StoredInvoice> findByNumber(UUID companyId, String number) {
        return jdbc.sql("SELECT * FROM invoice_message WHERE company_id = :companyId AND invoice_number = :number")
                .param("companyId", companyId)
                .param("number", number)
                .query(INVOICE)
                .optional();
    }

    Optional<StoredInvoice> findById(UUID companyId, UUID id) {
        return jdbc.sql("SELECT * FROM invoice_message WHERE company_id = :companyId AND id = :id")
                .param("companyId", companyId)
                .param("id", id)
                .query(INVOICE)
                .optional();
    }

    List<StoredInvoice.ArtifactInfo> artifacts(UUID companyId, UUID messageId) {
        return jdbc.sql("""
                SELECT kind, media_type, sha256, octet_length(content) AS size
                FROM invoice_artifact
                WHERE company_id = :companyId AND message_id = :messageId
                ORDER BY CASE kind WHEN 'RAW' THEN 1 WHEN 'CANONICAL' THEN 2 ELSE 3 END
                """)
                .param("companyId", companyId)
                .param("messageId", messageId)
                .query((rs, n) -> new StoredInvoice.ArtifactInfo(ArtifactKind.valueOf(rs.getString("kind")),
                        rs.getString("media_type"), rs.getString("sha256"), rs.getLong("size")))
                .list();
    }

    Optional<byte[]> artifactContent(UUID companyId, UUID messageId, ArtifactKind kind) {
        return jdbc.sql("""
                SELECT content FROM invoice_artifact
                WHERE company_id = :companyId AND message_id = :messageId AND kind = :kind
                """)
                .param("companyId", companyId)
                .param("messageId", messageId)
                .param("kind", kind.name())
                .query((rs, n) -> rs.getBytes("content"))
                .optional();
    }
}
