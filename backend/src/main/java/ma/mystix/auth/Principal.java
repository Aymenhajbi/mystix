package ma.mystix.auth;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated user of a request.
 *
 * @param companies       environments the user belongs to (all of them for a platform operator)
 * @param activeCompanyId environment the portal works in, {@code null} when the user has none
 */
public record Principal(UUID sessionId, UUID userId, String email, String displayName, boolean operator,
                        Set<UUID> companies, UUID activeCompanyId, OffsetDateTime expiresAt) {

    public boolean canAccess(UUID companyId) {
        return operator || companies.contains(companyId);
    }
}
