package ma.mystix.tenant;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanyService {

    private final CompanyRepository repository;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    CompanyService(CompanyRepository repository, Clock clock, ApplicationEventPublisher events) {
        this.repository = repository;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public Company register(Ice ice, String legalName, String taxIdentifier) {
        Company company = new Company(UUID.randomUUID(), ice, legalName.strip(),
                blankToNull(taxIdentifier), OffsetDateTime.now(clock));
        try {
            repository.insert(company);
        } catch (DuplicateKeyException e) {
            throw new MystixException(ErrorCode.COMPANY_ICE_ALREADY_EXISTS, "ICE already registered", e);
        }
        events.publishEvent(new CompanyRegistered(company));
        return company;
    }

    @Transactional(readOnly = true)
    public Company get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new MystixException(ErrorCode.COMPANY_NOT_FOUND, "No company " + id));
    }

    @Transactional(readOnly = true)
    public CompanySettings settings(UUID id) {
        return repository.settings(id)
                .orElseThrow(() -> new MystixException(ErrorCode.COMPANY_NOT_FOUND, "No company " + id));
    }

    @Transactional
    public CompanySettings updateSettings(UUID id, CompanySettings settings) {
        if (repository.updateSettings(id, settings) == 0) {
            throw new MystixException(ErrorCode.COMPANY_NOT_FOUND, "No company " + id);
        }
        return settings;
    }

    /** The ICE every invoice of this company must carry as seller ICE, or empty when the rule is off. */
    @Transactional(readOnly = true)
    public Optional<String> enforcedSellerIce(UUID id) {
        Company company = get(id);
        return settings(id).enforceSellerIce() ? Optional.of(company.ice().value()) : Optional.empty();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
