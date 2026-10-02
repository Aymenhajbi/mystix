package ma.mystix.mapping;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import ma.mystix.flow.FlowService;
import ma.mystix.shared.Sha256;
import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Draft, test, publish and roll back the rules of a flow (ADR-0008). */
@Service
public class MappingVersionService {

    private final MappingVersionRepository repository;
    private final FlowService flows;
    private final DryRunner dryRunner;
    private final Clock clock;

    MappingVersionService(MappingVersionRepository repository, FlowService flows, @Lazy DryRunner dryRunner,
                          Clock clock) {
        this.repository = repository;
        this.flows = flows;
        this.dryRunner = dryRunner;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<MappingVersion> versions(UUID companyId, UUID flowId) {
        flows.get(companyId, flowId);
        return repository.list(companyId, flowId);
    }

    /** Rules the flow runs today; empty when nothing is published (base mapping only). */
    @Transactional(readOnly = true)
    public List<MappingRule> publishedRules(UUID companyId, UUID flowId) {
        return repository.published(companyId, flowId).map(MappingVersion::rules).orElse(List.of());
    }

    /** New draft from the given rules, or from the published (else latest) version when none are given. */
    @Transactional
    public MappingVersion createDraft(UUID companyId, UUID flowId, List<MappingRule> rules) {
        List<MappingVersion> existing = versions(companyId, flowId);
        List<MappingRule> start = rules != null ? rules
                : existing.stream().filter(v -> v.status() == MappingVersion.Status.PUBLISHED).findFirst()
                        .or(() -> existing.stream().findFirst())
                        .map(MappingVersion::rules)
                        .orElse(List.of());
        checkRules(start);
        int version = repository.nextVersion(companyId, flowId);
        String rulesJson = repository.serialize(start);
        repository.insert(companyId, flowId, version, rulesJson, sha(rulesJson), OffsetDateTime.now(clock));
        return get(companyId, flowId, version);
    }

    @Transactional
    public MappingVersion updateDraft(UUID companyId, UUID flowId, int version, List<MappingRule> rules) {
        MappingVersion current = get(companyId, flowId, version);
        if (current.status() != MappingVersion.Status.DRAFT) {
            throw new MystixException(ErrorCode.MAPPING_NOT_EDITABLE, "Version " + version + " is " + current.status());
        }
        checkRules(rules);
        String rulesJson = repository.serialize(rules);
        repository.updateRules(companyId, flowId, version, rulesJson, sha(rulesJson), OffsetDateTime.now(clock));
        return get(companyId, flowId, version);
    }

    /** Dry run of a version against the published rules; the report is stored with the version. */
    @Transactional
    public MappingVersion test(UUID companyId, UUID flowId, int version) {
        MappingVersion candidate = get(companyId, flowId, version);
        List<TestReport.Sample> samples = dryRunner.run(companyId, flowId, candidate.rules(),
                publishedRules(companyId, flowId));
        int failures = (int) samples.stream().filter(s -> !s.passed()).count();
        repository.saveReport(companyId, flowId, version, new TestReport(candidate.rulesSha256(),
                OffsetDateTime.now(clock), failures == 0, samples.size(), failures, samples));
        return get(companyId, flowId, version);
    }

    /**
     * Publishes a draft whose last test passed on its current rules, or re-publishes a retired version (roll
     * back). The previously published version is retired.
     */
    @Transactional
    public MappingVersion publish(UUID companyId, UUID flowId, int version) {
        MappingVersion target = get(companyId, flowId, version);
        if (target.status() == MappingVersion.Status.PUBLISHED) {
            return target;
        }
        if (target.status() == MappingVersion.Status.DRAFT) {
            if (target.testReport() == null || !target.rulesSha256().equals(target.testedSha256())) {
                throw new MystixException(ErrorCode.MAPPING_NOT_TESTED, "Version " + version + " not tested as is");
            }
            if (!target.testedGreen()) {
                throw new MystixException(ErrorCode.MAPPING_TEST_FAILED, "Version " + version + " failed its test");
            }
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        repository.published(companyId, flowId).ifPresent(p ->
                repository.setStatus(companyId, flowId, p.version(), MappingVersion.Status.RETIRED, now));
        repository.setStatus(companyId, flowId, version, MappingVersion.Status.PUBLISHED, now);
        return get(companyId, flowId, version);
    }

    public MappingVersion get(UUID companyId, UUID flowId, int version) {
        flows.get(companyId, flowId);
        return repository.find(companyId, flowId, version)
                .orElseThrow(() -> new MystixException(ErrorCode.MAPPING_VERSION_NOT_FOUND,
                        "No version " + version + " for flow " + flowId));
    }

    private static void checkRules(List<MappingRule> rules) {
        List<RuleEngine.Violation> violations = RuleEngine.validate(rules);
        if (!violations.isEmpty()) {
            throw new MystixException(ErrorCode.MAPPING_RULES_INVALID, "Invalid mapping rules",
                    violations.stream().map(v -> new ApiError.FieldViolation(v.path(), v.reason())).toList(),
                    List.of(), null);
        }
    }

    private static String sha(String rulesJson) {
        return Sha256.hex(rulesJson.getBytes(StandardCharsets.UTF_8));
    }
}
