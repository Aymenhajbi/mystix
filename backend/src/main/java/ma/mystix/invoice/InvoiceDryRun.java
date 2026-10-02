package ma.mystix.invoice;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import ma.mystix.canonical.Invoice;
import ma.mystix.format.ubl.UblMappingSpec;
import ma.mystix.format.ubl.UblXPath;
import ma.mystix.mapping.DryRunner;
import ma.mystix.mapping.MappingRule;
import ma.mystix.mapping.TestReport;
import ma.mystix.shared.error.MystixException;
import org.springframework.stereotype.Component;

/**
 * Dry run of flow rules (ADR-0008): the reference invoice and the flow's latest requests go through the real
 * intake and generation (rules, field checks, canonical model, UBL 2.1, XSD, EN 16931) without storage, clearance
 * or logs. Each output is compared field by field with what the published rules produce.
 */
@Component
class InvoiceDryRun implements DryRunner {

    static final int SAMPLE_SIZE = 20;
    private static final String REFERENCE = "samples/invoice-reference.request.json";

    private final InvoiceIntake intake;
    private final InvoiceService invoices;
    private final InvoiceRepository repository;

    InvoiceDryRun(InvoiceIntake intake, InvoiceService invoices, InvoiceRepository repository) {
        this.intake = intake;
        this.invoices = invoices;
        this.repository = repository;
    }

    @Override
    public List<TestReport.Sample> run(UUID companyId, UUID flowId, List<MappingRule> candidate,
                                       List<MappingRule> published) {
        List<Map.Entry<String, byte[]>> samples = new ArrayList<>();
        samples.add(Map.entry("reference", reference()));
        samples.addAll(repository.latestRaw(companyId, flowId, SAMPLE_SIZE));
        return samples.stream().map(s -> runOne(s.getKey(), s.getValue(), candidate, published)).toList();
    }

    private TestReport.Sample runOne(String label, byte[] body, List<MappingRule> candidate,
                                     List<MappingRule> published) {
        byte[] before = generateOrNull(body, published);
        byte[] after;
        Invoice invoice;
        try {
            invoice = intake.read(body, candidate);
            after = invoices.checkedUbl(invoice);
        } catch (MystixException e) {
            return new TestReport.Sample(label, false, describe(e), List.of());
        }
        return new TestReport.Sample(label, true, List.of(), changes(before, after, invoice.lines().size()));
    }

    private byte[] generateOrNull(byte[] body, List<MappingRule> rules) {
        try {
            return invoices.checkedUbl(intake.read(body, rules));
        } catch (MystixException e) {
            return null;
        }
    }

    private static List<TestReport.Change> changes(byte[] before, byte[] after, int lines) {
        UblXPath b = before == null ? null : new UblXPath(before);
        UblXPath a = new UblXPath(after);
        List<TestReport.Change> changes = new ArrayList<>();
        for (UblMappingSpec.Field f : UblMappingSpec.FIELDS) {
            if (f.target() == null) {
                continue;
            }
            int count = "line".equals(f.group()) ? lines : 1;
            for (int i = 0; i < count; i++) {
                String path = f.target().replace("{n}", String.valueOf(i + 1));
                String was = b == null ? null : b.text(path);
                String now = a.text(path);
                if (!Objects.equals(was, now)) {
                    changes.add(new TestReport.Change(f.term(), f.label(), "line".equals(f.group()) ? i : null, was, now));
                }
            }
        }
        return changes;
    }

    private static List<String> describe(MystixException e) {
        List<String> errors = new ArrayList<>();
        errors.add(e.errorCode().name());
        e.fieldErrors().stream().limit(5).forEach(f -> errors.add(f.field() + " " + f.reason()));
        e.ruleViolations().stream().filter(v -> "FATAL".equals(v.severity())).limit(5)
                .forEach(v -> errors.add(v.ruleId() + " " + v.message()));
        return List.copyOf(errors);
    }

    private static byte[] reference() {
        try (InputStream in = InvoiceDryRun.class.getClassLoader().getResourceAsStream(REFERENCE)) {
            return Objects.requireNonNull(in, REFERENCE).readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + REFERENCE, e);
        }
    }
}
