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
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getString("clearance_reference"),
            rs.getObject("clearance_simulated", Boolean.class),
            rs.getObject("clearance_at", OffsetDateTime.class),
            rs.getString("buyer_name"),
            rs.getString("currency"),
            rs.getBigDecimal("payable_amount"),
            rs.getObject("flow_id", UUID.class));

    private final JdbcClient jdbc;

    InvoiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the invoice and its artefacts atomically. Throws DuplicateKeyException if the number exists. */
    @Transactional
    void insert(StoredInvoice invoice, Map<ArtifactKind, byte[]> artifacts) {
        jdbc.sql("""
                INSERT INTO invoice_message (id, company_id, invoice_number, issue_date, status,
                                             canonical_version, canonical_sha256, created_at,
                                             buyer_name, currency, payable_amount, flow_id)
                VALUES (:id, :companyId, :number, :issueDate, :status, :canonicalVersion, :canonicalSha256, :createdAt,
                        :buyerName, :currency, :payableAmount, :flowId)
                """)
                .param("buyerName", invoice.buyerName())
                .param("currency", invoice.currency())
                .param("payableAmount", invoice.payableAmount())
                .param("flowId", invoice.flowId())
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
        appendEvent(invoice.companyId(), invoice.id(), invoice.status(), null, invoice.createdAt());
    }

    /** Records the clearance answer and its history entry atomically. */
    @Transactional
    void recordClearance(UUID companyId, UUID id, String status, String reference, boolean simulated,
                         OffsetDateTime at, String detail) {
        int updated = jdbc.sql("""
                UPDATE invoice_message
                SET status = :status, clearance_reference = :reference, clearance_simulated = :simulated,
                    clearance_at = :at
                WHERE company_id = :companyId AND id = :id AND status = 'VALIDATED'
                """)
                .param("status", status)
                .param("reference", reference)
                .param("simulated", simulated)
                .param("at", at)
                .param("companyId", companyId)
                .param("id", id)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Invoice " + id + " is not awaiting clearance");
        }
        appendEvent(companyId, id, status, detail, at);
    }

    /** Approves (VALIDATED) or rejects a pending invoice with its history entry; false if it was not pending. */
    @Transactional
    boolean decideValidation(UUID companyId, UUID id, String status, String detail, OffsetDateTime at) {
        int updated = jdbc.sql("""
                        UPDATE invoice_message SET status = :status
                        WHERE company_id = :companyId AND id = :id AND status = 'PENDING_VALIDATION'
                        """)
                .param("status", status).param("companyId", companyId).param("id", id)
                .update();
        if (updated == 1) {
            appendEvent(companyId, id, status, detail, at);
        }
        return updated == 1;
    }

    void appendEvent(UUID companyId, UUID messageId, String status, String detail, OffsetDateTime at) {
        jdbc.sql("""
                INSERT INTO invoice_status_event (id, message_id, company_id, status, detail, occurred_at)
                VALUES (:id, :messageId, :companyId, :status, :detail, :at)
                """)
                .param("id", UUID.randomUUID())
                .param("messageId", messageId)
                .param("companyId", companyId)
                .param("status", status)
                .param("detail", detail)
                .param("at", at)
                .update();
    }

    List<StoredInvoice.StatusEvent> history(UUID companyId, UUID messageId) {
        return jdbc.sql("""
                SELECT status, detail, occurred_at FROM invoice_status_event
                WHERE company_id = :companyId AND message_id = :messageId
                ORDER BY seq
                """)
                .param("companyId", companyId)
                .param("messageId", messageId)
                .query((rs, n) -> new StoredInvoice.StatusEvent(rs.getString("status"), rs.getString("detail"),
                        rs.getObject("occurred_at", OffsetDateTime.class)))
                .list();
    }

    Optional<StoredInvoice> findByNumber(UUID companyId, String number) {
        return jdbc.sql("SELECT * FROM invoice_message WHERE company_id = :companyId AND invoice_number = :number")
                .param("companyId", companyId)
                .param("number", number)
                .query(INVOICE)
                .optional();
    }

    List<StoredInvoice> list(UUID companyId, UUID flowId, int limit) {
        return jdbc.sql("""
                SELECT * FROM invoice_message
                WHERE company_id = :companyId
                  AND (CAST(:flowId AS uuid) IS NULL OR flow_id = :flowId)
                ORDER BY created_at DESC, id
                LIMIT :limit
                """)
                .param("companyId", companyId)
                .param("flowId", flowId)
                .param("limit", limit)
                .query(INVOICE)
                .list();
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

    /** RAW requests of the latest invoices of a flow, newest first, with their numbers. */
    List<Map.Entry<String, byte[]>> latestRaw(UUID companyId, UUID flowId, int limit) {
        return jdbc.sql("""
                SELECT m.invoice_number, a.content
                FROM invoice_message m
                JOIN invoice_artifact a ON a.message_id = m.id AND a.company_id = m.company_id AND a.kind = 'RAW'
                WHERE m.company_id = :companyId AND m.flow_id = :flowId
                ORDER BY m.created_at DESC
                LIMIT :limit
                """)
                .param("companyId", companyId)
                .param("flowId", flowId)
                .param("limit", limit)
                .query((rs, n) -> Map.entry(rs.getString("invoice_number"), rs.getBytes("content")))
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
