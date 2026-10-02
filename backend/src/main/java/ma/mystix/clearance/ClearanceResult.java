package ma.mystix.clearance;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Answer of the clearance gateway.
 *
 * @param outcome   accepted or rejected
 * @param reference identifier returned on acceptance, {@code null} on rejection.
 *                  TODO(DGI-SPEC): format of the DGI unique identifier is not published.
 * @param reason    rejection reason, {@code null} on acceptance
 * @param simulated true when the answer does not come from the DGI
 * @param at        time of the answer
 */
public record ClearanceResult(Outcome outcome, String reference, String reason, boolean simulated,
                              OffsetDateTime at) {

    public enum Outcome { CLEARED, REJECTED }

    public ClearanceResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(at, "at");
        if (outcome == Outcome.CLEARED && reference == null) {
            throw new IllegalArgumentException("A cleared invoice has a reference");
        }
    }
}
