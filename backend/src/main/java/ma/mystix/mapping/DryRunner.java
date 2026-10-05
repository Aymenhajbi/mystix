package ma.mystix.mapping;

import java.util.List;
import java.util.UUID;

/** Runs candidate rules through the invoice pipeline without side effects. Implemented by the invoice module. */
public interface DryRunner {

    List<TestReport.Sample> run(UUID companyId, UUID flowId, List<MappingRule> candidate, List<MappingRule> published);
}
