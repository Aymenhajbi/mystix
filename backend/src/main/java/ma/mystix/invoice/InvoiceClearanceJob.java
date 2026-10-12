package ma.mystix.invoice;

import ma.mystix.queue.Job;
import ma.mystix.queue.JobHandler;
import org.springframework.stereotype.Component;

/**
 * Runs the clearance of one invoice from the queue (ADR-0003): asynchronous submissions, and retries after a gateway
 * failure. Clearance stays SIMULATED (ADR-0001).
 */
@Component
class InvoiceClearanceJob implements JobHandler {

    private final InvoiceService invoices;

    InvoiceClearanceJob(InvoiceService invoices) {
        this.invoices = invoices;
    }

    @Override
    public String kind() {
        return InvoiceService.CLEARANCE_JOB;
    }

    @Override
    public void handle(Job job) {
        invoices.clearQueued(job.companyId(), job.subjectId());
    }
}
