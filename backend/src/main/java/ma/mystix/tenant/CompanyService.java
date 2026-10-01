package ma.mystix.tenant;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanyService {

    private final CompanyRepository repository;
    private final Clock clock;

    CompanyService(CompanyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
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
        return company;
    }

    @Transactional(readOnly = true)
    public Company get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new MystixException(ErrorCode.COMPANY_NOT_FOUND, "No company " + id));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
