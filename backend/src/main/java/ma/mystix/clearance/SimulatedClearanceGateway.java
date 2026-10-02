package ma.mystix.clearance;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Simulated clearance. Its answers are fictitious and never presented as DGI answers.
 * <ul>
 *   <li>Accepts by default, with reference {@code SIMULATED-<first 20 hex of the document SHA-256>}: deterministic,
 *       so a replay gets the same reference.</li>
 *   <li>Rejects invoice numbers matching {@code rejectNumbersMatching} (configurable, empty = never), to exercise
 *       the rejection path in tests and demos.</li>
 * </ul>
 */
public final class SimulatedClearanceGateway implements ClearanceGateway {

    public static final String REFERENCE_PREFIX = "SIMULATED-";

    private final Clock clock;
    private final Pattern rejectNumbers;

    public SimulatedClearanceGateway(Clock clock, String rejectNumbersMatching) {
        this.clock = clock;
        this.rejectNumbers = rejectNumbersMatching == null || rejectNumbersMatching.isBlank()
                ? null
                : Pattern.compile(rejectNumbersMatching);
    }

    @Override
    public ClearanceResult submit(ClearanceRequest request) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (rejectNumbers != null && rejectNumbers.matcher(request.invoiceNumber()).matches()) {
            return new ClearanceResult(ClearanceResult.Outcome.REJECTED, null,
                    "Simulated rejection (number matches the configured rejection pattern)", true, now);
        }
        String reference = REFERENCE_PREFIX + request.documentSha256().substring(0, 20).toUpperCase(Locale.ROOT);
        return new ClearanceResult(ClearanceResult.Outcome.CLEARED, reference, null, true, now);
    }

    @Override
    public boolean simulated() {
        return true;
    }
}
