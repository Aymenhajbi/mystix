package ma.mystix.invoice;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import ma.mystix.canonical.Invoice;
import ma.mystix.canonical.VatCategoryCode;
import ma.mystix.flow.ExchangeFlow;
import ma.mystix.flow.FlowService;
import ma.mystix.logs.ProcessingLog;
import ma.mystix.mapping.MappingVersionService;
import ma.mystix.referential.VatReferential;
import ma.mystix.shared.error.MystixException;
import ma.mystix.shared.time.TimeConfig;
import ma.mystix.tenant.CompanyService;
import org.springframework.stereotype.Component;

/**
 * Receives one JSON invoice for a company: flow rules (ADR-0008), intake checks, storage and UBL 2.1, then clearance
 * in the request or through the queue. Shared by the invoice API and the listeners of the Connectivity Manager
 * (ADR-0016), so an invoice is handled the same way whatever door it came through.
 */
@Component
public class InvoiceReception {

    private final InvoiceService service;
    private final ProcessingLog logs;
    private final InvoiceIntake intake;
    private final FlowService flows;
    private final MappingVersionService mappings;
    private final CompanyService companies;
    private final VatReferential vatRates;
    private final Clock clock;

    InvoiceReception(InvoiceService service, ProcessingLog logs, InvoiceIntake intake, FlowService flows,
                     MappingVersionService mappings, CompanyService companies, VatReferential vatRates, Clock clock) {
        this.service = service;
        this.logs = logs;
        this.intake = intake;
        this.flows = flows;
        this.mappings = mappings;
        this.companies = companies;
        this.vatRates = vatRates;
        this.clock = clock;
    }

    /**
     * Accepts the invoice, or throws the structured refusal (also written to the company logs).
     *
     * @param flowId the flow named by the caller, or null for the company's default invoice flow
     * @param async  clearance through the queue and an immediate answer
     */
    public InvoiceService.Submission receive(UUID companyId, UUID flowId, byte[] body, boolean async) {
        try {
            // Flow first: its published rules (ADR-0008) apply before any field check.
            ExchangeFlow flow = flows.forSubmission(companyId, flowId);
            LocalDate receivedOn = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
            boolean checkVatRates = companies.settings(companyId).enforceVatRates();
            Invoice canonical = intake.read(body, mappings.publishedRules(companyId, flow.id()),
                    new InvoiceIntake.Policy(companies.enforcedSellerIce(companyId).orElse(null), checkVatRates,
                            receivedOn));
            InvoiceService.Submission submission = service.submit(companyId, flow, body, canonical, async);
            if (!submission.replayed()) {
                warnings(companyId, submission.invoice(), canonical, checkVatRates, receivedOn);
            }
            return submission;
        } catch (MystixException e) {
            logs.submissionRejected(companyId, intake.numberOf(body), e, body);
            throw e;
        } catch (RuntimeException e) {
            logs.submissionFailed(companyId, intake.numberOf(body), e, body);
            throw e;
        }
    }

    /**
     * Warnings on an accepted invoice: backdated (with the rates that applied) and VAT rates that could not be
     * checked because the referential has no rate for the issue date. Neither blocks the invoice.
     */
    private void warnings(UUID companyId, StoredInvoice stored, Invoice invoice, boolean checkVatRates,
                          LocalDate receivedOn) {
        String country = invoice.seller().address().countryCode();
        Optional<List<BigDecimal>> rates = vatRates.standardRates(country, invoice.issueDate());
        boolean standardLines = invoice.lines().stream().anyMatch(l -> l.vat().code() == VatCategoryCode.S);
        if (invoice.issueDate().isBefore(receivedOn)) {
            String vat = !checkVatRates ? "VAT rate check disabled for this environment"
                    : rates.map(r -> "VAT rates in force on the issue date applied ("
                            + r.stream().map(x -> x.stripTrailingZeros().toPlainString() + " %")
                            .collect(Collectors.joining(", ")) + ")")
                    .orElse("no VAT rate in the referential for the issue date");
            logs.invoiceBackdated(companyId, stored.id(), stored.number(), "Backdated invoice held for "
                    + "administrator validation: issued " + invoice.issueDate() + ", received " + receivedOn + "; "
                    + vat);
        }
        if (checkVatRates && standardLines && rates.isEmpty()) {
            logs.vatRatesUnchecked(companyId, stored.id(), stored.number(), "VAT rates not checked: the referential"
                    + " has no standard rate in force on " + invoice.issueDate() + " in " + country);
        }
    }
}
