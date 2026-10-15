package ma.mystix.connector;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import ma.mystix.invoice.InvoiceReception;
import ma.mystix.invoice.InvoiceService;
import ma.mystix.shared.Sha256;
import ma.mystix.shared.error.MystixException;
import org.springframework.stereotype.Component;

/**
 * Hands a message received by a listener to its entry point (ADR-0016), whatever the transport: counts it and
 * journals it with its correlation id. A business refusal (the pipeline said no) is recorded as MESSAGE_REJECTED
 * and rethrown; the transport decides what to answer or where to leave the file.
 */
@Component
class EntryPointDispatcher {

    private final ListenerStore store;
    private final InvoiceReception invoices;

    EntryPointDispatcher(ListenerStore store, InvoiceReception invoices) {
        this.store = store;
        this.invoices = invoices;
    }

    /**
     * @param source what the transport knows of the message (file name, remote path…); no content, no secret
     */
    InvoiceService.Submission dispatch(UUID companyId, UUID listenerId, UUID flowId, byte[] body, UUID correlation,
                                       Map<String, Object> source) {
        InvoiceService.Submission submission;
        try {
            submission = invoices.receive(companyId, flowId, body, true);
        } catch (MystixException e) {
            Map<String, Object> details = new LinkedHashMap<>(source);
            details.put("errorCode", e.errorCode().name());
            details.put("stage", "PIPELINE");
            details.put("bytes", body.length);
            store.received(listenerId, false);
            store.event(companyId, listenerId, "MESSAGE_REJECTED", "WARN", correlation, details, null);
            throw e;
        }
        Map<String, Object> details = new LinkedHashMap<>(source);
        details.put("invoiceId", submission.invoice().id().toString());
        details.put("number", submission.invoice().number());
        details.put("bytes", body.length);
        details.put("sha256", Sha256.hex(body));
        details.put("replayed", submission.replayed());
        store.received(listenerId, true);
        store.event(companyId, listenerId, "MESSAGE_RECEIVED", "INFO", correlation, details, null);
        return submission;
    }

    /** A message refused before the pipeline (key, size, type, file): counted and journaled. */
    void refuse(UUID companyId, UUID listenerId, UUID correlation, String errorCode, String reason,
                Map<String, Object> source) {
        Map<String, Object> details = new LinkedHashMap<>(source);
        details.put("errorCode", errorCode);
        details.put("stage", "RECEPTION");
        details.put("reason", reason);
        store.received(listenerId, false);
        store.event(companyId, listenerId, "MESSAGE_REFUSED", "WARN", correlation, details, null);
    }
}
