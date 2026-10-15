package ma.mystix.connector;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Rows of {@code as2_message} (V26): the AS2 journal of each channel, scoped by company. */
@Component
class As2MessageStore {

    /** A received message's answer, kept to be given again on a duplicate. */
    record StoredMdn(int status, Map<String, String> headers, byte[] body) {
    }

    /** One AS2 exchange, for the API. */
    record Row(UUID id, UUID channelId, UUID listenerId, String direction, String messageId, String as2From,
               String as2To, String payloadSha256, Long payloadSize, String mic, String mdnDisposition,
               Boolean mdnSigned, Boolean micMatched, UUID correlationId, UUID invoiceId, String errorCode,
               Long durationMs, OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    As2MessageStore(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    Optional<StoredMdn> mdnOf(UUID channelId, String messageId) {
        return jdbc.sql("""
                        SELECT mdn_status, mdn_headers, mdn_body FROM as2_message
                        WHERE channel_id = :ch AND direction = 'IN' AND message_id = :m AND mdn_status IS NOT NULL
                        """)
                .param("ch", channelId).param("m", messageId)
                .query((rs, n) -> new StoredMdn(rs.getInt("mdn_status"),
                        json.readValue(rs.getString("mdn_headers"), new TypeReference<Map<String, String>>() { }),
                        rs.getBytes("mdn_body")))
                .optional();
    }

    /** Records an exchange; a second record of the same message on the same channel and direction is ignored. */
    void record(UUID companyId, UUID channelId, UUID listenerId, String direction, String messageId, String from,
                String to, String payloadSha256, Long payloadSize, String mic, String disposition, Boolean mdnSigned,
                Boolean micMatched, StoredMdn mdn, UUID correlation, UUID invoiceId, String errorCode,
                Long durationMs) {
        jdbc.sql("""
                        INSERT INTO as2_message (id, company_id, channel_id, listener_id, direction, message_id, as2_from,
                                                 as2_to, payload_sha256, payload_size, mic, mdn_disposition, mdn_signed,
                                                 mic_matched, mdn_status, mdn_headers, mdn_body, correlation_id,
                                                 invoice_id, error_code, duration_ms, created_at)
                        VALUES (:id, :c, :ch, :l, :dir, :m, :from, :to, :sha, :size, :mic, :disp, :signed, :matched,
                                :status, CAST(:headers AS jsonb), :body, :corr, :invoice, :error, :ms, :now)
                        ON CONFLICT (channel_id, direction, message_id) DO NOTHING
                        """)
                .param("id", UUID.randomUUID()).param("c", companyId).param("ch", channelId).param("l", listenerId)
                .param("dir", direction).param("m", messageId).param("from", from).param("to", to)
                .param("sha", payloadSha256).param("size", payloadSize).param("mic", mic).param("disp", disposition)
                .param("signed", mdnSigned).param("matched", micMatched)
                .param("status", mdn == null ? null : mdn.status())
                .param("headers", mdn == null ? null : json.writeValueAsString(mdn.headers()))
                .param("body", mdn == null ? null : mdn.body()).param("corr", correlation).param("invoice", invoiceId)
                .param("error", errorCode).param("ms", durationMs).param("now", OffsetDateTime.now(clock)).update();
    }

    List<Row> list(UUID companyId, UUID channelId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM as2_message WHERE company_id = :c AND channel_id = :ch
                        ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("c", companyId).param("ch", channelId).param("limit", Math.clamp(limit, 1, 500))
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("channel_id", UUID.class),
                        rs.getObject("listener_id", UUID.class), rs.getString("direction"),
                        rs.getString("message_id"), rs.getString("as2_from"), rs.getString("as2_to"),
                        rs.getString("payload_sha256"), (Long) rs.getObject("payload_size"), rs.getString("mic"),
                        rs.getString("mdn_disposition"), (Boolean) rs.getObject("mdn_signed"),
                        (Boolean) rs.getObject("mic_matched"), rs.getObject("correlation_id", UUID.class),
                        rs.getObject("invoice_id", UUID.class), rs.getString("error_code"),
                        (Long) rs.getObject("duration_ms"), rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }
}
