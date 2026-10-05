package ma.mystix.stock;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import ma.mystix.format.edifact.EdifactException;
import ma.mystix.format.edifact.EdifactParser;
import ma.mystix.shared.Sha256;
import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.tenant.CompanyService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Receives a UN/EDIFACT interchange of stock messages (ADR-0012). The interchange is checked as a whole; each
 * message then becomes one stock event in its own transaction, so one rejected message does not block the others.
 * Every message is kept byte for byte with its outcome.
 */
@Service
public class StockEdifactService {

    private final StockService stock;
    private final CompanyService companies;
    private final Validator validator;
    private final JdbcClient jdbc;
    private final Clock clock;

    StockEdifactService(StockService stock, CompanyService companies, Validator validator, JdbcClient jdbc, Clock clock) {
        this.stock = stock;
        this.companies = companies;
        this.validator = validator;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Outcome of one message: APPLIED, REPLAYED (already received, nothing applied twice) or REJECTED (with the
     * structured error).
     */
    public record MessageOutcome(String messageReference, String messageType, String documentNumber, String status,
                                 StockService.Result result, String errorCode, String errorDetail,
                                 List<ApiError.FieldViolation> fieldErrors) {
    }

    public record Receipt(String sender, String interchangeReference, int accepted, int rejected,
                          List<MessageOutcome> messages) {
    }

    public Receipt receive(UUID companyId, byte[] body, String direction, String defaultLocation) {
        companies.get(companyId);
        EdifactParser.Interchange interchange;
        try {
            interchange = EdifactParser.parse(body);
        } catch (EdifactException e) {
            throw new MystixException(ErrorCode.EDIFACT_INVALID, "Invalid EDIFACT interchange: " + e.getMessage(),
                    List.of(new ApiError.FieldViolation("segment " + e.segmentPosition() + " " + e.segmentTag(),
                            e.getMessage())), List.of(), e);
        }
        List<MessageOutcome> outcomes = new ArrayList<>();
        for (EdifactParser.Message message : interchange.messages()) {
            outcomes.add(receive(companyId, interchange, message, direction, defaultLocation));
        }
        int rejected = (int) outcomes.stream().filter(o -> "REJECTED".equals(o.status())).count();
        return new Receipt(interchange.sender(), interchange.controlReference(), outcomes.size() - rejected, rejected,
                outcomes);
    }

    private MessageOutcome receive(UUID companyId, EdifactParser.Interchange interchange, EdifactParser.Message message,
                                   String direction, String defaultLocation) {
        String documentNumber = null;
        MessageOutcome outcome;
        try {
            StockEventRequest request = StockEdifactMapper.map(interchange, message, direction, defaultLocation);
            documentNumber = request.documentNumber();
            validate(request);
            StockService.Result result = stock.apply(companyId, request);
            outcome = new MessageOutcome(message.reference(), message.type(), documentNumber,
                    result.replayed() ? "REPLAYED" : "APPLIED", result, null, null, List.of());
        } catch (StockEdifactMapper.MappingException e) {
            outcome = new MessageOutcome(message.reference(), message.type(), documentNumber, "REJECTED", null,
                    ErrorCode.EDIFACT_MAPPING_FAILED.name(), e.getMessage(),
                    List.of(new ApiError.FieldViolation(e.segment, e.getMessage())));
        } catch (MystixException e) {
            outcome = new MessageOutcome(message.reference(), message.type(), documentNumber, "REJECTED", null,
                    e.errorCode().name(), e.getMessage(), e.fieldErrors());
        }
        store(companyId, interchange, message, outcome);
        return outcome;
    }

    private void validate(StockEventRequest request) {
        Set<ConstraintViolation<StockEventRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Invalid stock event from EDIFACT",
                    violations.stream()
                            .map(v -> new ApiError.FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                            .sorted(Comparator.comparing(ApiError.FieldViolation::field))
                            .toList(),
                    List.of(), null);
        }
    }

    private void store(UUID companyId, EdifactParser.Interchange interchange, EdifactParser.Message message,
                       MessageOutcome outcome) {
        String detail = outcome.errorDetail();
        jdbc.sql("""
                        INSERT INTO stock_edi_message (id, company_id, event_id, message_type, sender, interchange_reference,
                                                       message_reference, document_number, status, error_code,
                                                       error_detail, raw, sha256, received_at)
                        VALUES (:id, :c, :event, :type, :sender, :icRef, :msgRef, :doc, :status, :code, :detail, :raw,
                                :sha, :at)
                        """)
                .param("id", UUID.randomUUID()).param("c", companyId)
                .param("event", outcome.result() == null ? null : outcome.result().eventId())
                .param("type", truncate(message.type(), 6)).param("sender", truncate(interchange.sender(), 35))
                .param("icRef", truncate(interchange.controlReference(), 14))
                .param("msgRef", truncate(message.reference(), 14))
                .param("doc", truncate(outcome.documentNumber(), 100)).param("status", outcome.status())
                .param("code", outcome.errorCode()).param("detail", truncate(detail, 500))
                .param("raw", message.raw()).param("sha", Sha256.hex(message.raw()))
                .param("at", OffsetDateTime.now(clock))
                .update();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
