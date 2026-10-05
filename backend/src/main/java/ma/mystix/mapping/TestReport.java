package ma.mystix.mapping;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Result of a dry run (ADR-0008): the candidate rules on the reference invoice and the flow's latest requests,
 * through the whole pipeline without storage or clearance, compared with the published rules.
 */
public record TestReport(String rulesSha256, OffsetDateTime testedAt, boolean passed, int total, int failures,
                         List<Sample> samples) {

    /**
     * @param label   "reference" or the stored invoice number
     * @param passed  mapping, XSD and EN 16931 all succeeded
     * @param errors  error codes and messages when it failed
     * @param changes UBL values that differ from the published rules' output
     */
    public record Sample(String label, boolean passed, List<String> errors, List<Change> changes) {
    }

    public record Change(String term, String label, Integer line, String before, String after) {
    }
}
