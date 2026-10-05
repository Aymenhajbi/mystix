package ma.mystix.mapping;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** A version of a flow's rules (ADR-0008). */
public record MappingVersion(UUID id, UUID companyId, UUID flowId, int version, Status status,
                             List<MappingRule> rules, String rulesSha256, TestReport testReport, String testedSha256,
                             Boolean testPassed, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                             OffsetDateTime publishedAt) {

    public enum Status { DRAFT, PUBLISHED, RETIRED }

    /** True when the last test passed on exactly these rules. */
    public boolean testedGreen() {
        return Boolean.TRUE.equals(testPassed) && rulesSha256.equals(testedSha256);
    }
}
