package ma.mystix.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import ma.mystix.shared.Sha256;
import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.tenant.Company;
import ma.mystix.tenant.CompanyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounts, server-side sessions and the environments a user may work in (ADR-0013).
 * A session token is 32 random bytes given once to the client; only its SHA-256 is stored.
 */
@Service
public class AuthService {

    static final int MIN_PASSWORD_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final CompanyService companies;
    private final Clock clock;
    private final int maxFailedAttempts;
    private final Duration lockDuration;
    private final Duration sessionLifetime;
    private final Duration idleTimeout;

    AuthService(JdbcClient jdbc, CompanyService companies, Clock clock,
                @Value("${mystix.auth.max-failed-attempts:5}") int maxFailedAttempts,
                @Value("${mystix.auth.lock-duration:PT15M}") Duration lockDuration,
                @Value("${mystix.auth.session-lifetime:PT12H}") Duration sessionLifetime,
                @Value("${mystix.auth.idle-timeout:PT2H}") Duration idleTimeout) {
        this.jdbc = jdbc;
        this.companies = companies;
        this.clock = clock;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockDuration = lockDuration;
        this.sessionLifetime = sessionLifetime;
        this.idleTimeout = idleTimeout;
    }

    /** A new session: the token is returned once and never stored. */
    public record LoginResult(String token, Principal principal) {
    }

    private record UserRow(UUID id, String email, String displayName, String passwordHash, boolean operator,
                           int failedAttempts, OffsetDateTime lockedUntil) {
    }

    /** True until the first account exists: the portal then offers to create the first administrator. */
    @Transactional(readOnly = true)
    public boolean setupNeeded() {
        return jdbc.sql("SELECT COUNT(*) FROM app_user").query(Long.class).single() == 0;
    }

    /** First administrator (platform operator). Refused once any account exists. */
    @Transactional
    public LoginResult setup(String email, String displayName, char[] password, String userAgent) {
        jdbc.sql("LOCK TABLE app_user IN EXCLUSIVE MODE").update();
        if (!setupNeeded()) {
            throw new MystixException(ErrorCode.AUTH_SETUP_DONE, "Setup already done");
        }
        UUID id = insertUser(email, displayName, password, true);
        return openSession(id, userAgent);
    }

    /** A user created by a platform operator, member of one environment. */
    @Transactional
    public UUID createUser(String email, String displayName, char[] password, UUID companyId, String role) {
        companies.get(companyId);
        UUID id = insertUser(email, displayName, password, false);
        jdbc.sql("INSERT INTO company_membership (user_id, company_id, role, created_at) VALUES (:u, :c, :r, :at)")
                .param("u", id).param("c", companyId).param("r", role).param("at", OffsetDateTime.now(clock))
                .update();
        return id;
    }

    /**
     * Checks the password and opens a session. Unknown e-mail and wrong password give the same error, after the
     * same work (no account enumeration); repeated failures lock the account for a while.
     */
    @Transactional(noRollbackFor = MystixException.class)
    public LoginResult login(String email, char[] password, String userAgent) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<UserRow> found = user(normalize(email));
        if (found.isEmpty()) {
            PasswordHasher.verify(password, PasswordHasher.DUMMY);
            throw invalidCredentials();
        }
        UserRow user = found.get();
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
            PasswordHasher.verify(password, PasswordHasher.DUMMY);
            throw new MystixException(ErrorCode.AUTH_LOCKED, "Account " + user.id() + " locked until " + user.lockedUntil());
        }
        if (!PasswordHasher.verify(password, user.passwordHash())) {
            int attempts = user.failedAttempts() + 1;
            boolean lock = attempts >= maxFailedAttempts;
            jdbc.sql("UPDATE app_user SET failed_attempts = :n, locked_until = :until WHERE id = :id")
                    .param("n", lock ? 0 : attempts).param("until", lock ? now.plus(lockDuration) : null)
                    .param("id", user.id()).update();
            if (lock) {
                throw new MystixException(ErrorCode.AUTH_LOCKED, "Account " + user.id() + " locked after "
                        + attempts + " failed attempts");
            }
            throw invalidCredentials();
        }
        jdbc.sql("UPDATE app_user SET failed_attempts = 0, locked_until = NULL, last_login_at = :now WHERE id = :id")
                .param("now", now).param("id", user.id()).update();
        return openSession(user.id(), userAgent);
    }

    /** The user of a valid session, or empty when the token is unknown, revoked, expired or idle too long. */
    @Transactional
    public Optional<Principal> authenticate(String token) {
        if (token == null || token.isBlank() || token.length() > 100) {
            return Optional.empty();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        record SessionRow(UUID id, UUID userId, UUID activeCompanyId, OffsetDateTime lastSeen, OffsetDateTime expires) {
        }
        Optional<SessionRow> session = jdbc.sql("""
                        SELECT id, user_id, active_company_id, last_seen_at, expires_at FROM auth_session
                        WHERE token_sha256 = :sha AND revoked_at IS NULL
                        """)
                .param("sha", Sha256.hex(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                .query((rs, n) -> new SessionRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("active_company_id", UUID.class),
                        rs.getObject("last_seen_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .optional();
        if (session.isEmpty() || !session.get().expires().isAfter(now)
                || session.get().lastSeen().plus(idleTimeout).isBefore(now)) {
            return Optional.empty();
        }
        SessionRow s = session.get();
        if (s.lastSeen().plusMinutes(1).isBefore(now)) {
            jdbc.sql("UPDATE auth_session SET last_seen_at = :now WHERE id = :id").param("now", now).param("id", s.id())
                    .update();
        }
        return Optional.of(principal(s.id(), s.userId(), s.activeCompanyId(), s.expires()));
    }

    @Transactional
    public void logout(UUID sessionId) {
        jdbc.sql("UPDATE auth_session SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
                .param("now", OffsetDateTime.now(clock)).param("id", sessionId).update();
    }

    /** Switches the environment of a session; only one the user belongs to (any for an operator). */
    @Transactional
    public Principal switchCompany(Principal principal, UUID companyId) {
        companies.get(companyId);
        if (!principal.canAccess(companyId)) {
            throw new MystixException(ErrorCode.AUTH_FORBIDDEN, "User " + principal.userId() + " is not a member of "
                    + companyId);
        }
        jdbc.sql("UPDATE auth_session SET active_company_id = :c WHERE id = :id")
                .param("c", companyId).param("id", principal.sessionId()).update();
        return principal(principal.sessionId(), principal.userId(), companyId, principal.expiresAt());
    }

    /** Environments a user may open, with their names, in display order. */
    @Transactional(readOnly = true)
    public List<Company> companiesOf(Principal principal) {
        if (principal.operator()) {
            return companies.list();
        }
        return principal.companies().stream().map(companies::get)
                .sorted(java.util.Comparator.comparing(Company::legalName)).toList();
    }

    private LoginResult openSession(UUID userId, String userAgent) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        OffsetDateTime now = OffsetDateTime.now(clock);
        UUID sessionId = UUID.randomUUID();
        Principal draft = principal(sessionId, userId, null, now.plus(sessionLifetime));
        UUID active = draft.operator()
                ? companies.list().stream().map(Company::id).findFirst().orElse(null)
                : draft.companies().stream().findFirst().orElse(null);
        jdbc.sql("""
                        INSERT INTO auth_session (id, token_sha256, user_id, active_company_id, created_at, last_seen_at,
                                                  expires_at, user_agent)
                        VALUES (:id, :sha, :user, :company, :now, :now, :expires, :agent)
                        """)
                .param("id", sessionId).param("sha", Sha256.hex(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                .param("user", userId).param("company", active).param("now", now)
                .param("expires", now.plus(sessionLifetime))
                .param("agent", userAgent == null ? null : userAgent.substring(0, Math.min(200, userAgent.length())))
                .update();
        return new LoginResult(token, principal(sessionId, userId, active, now.plus(sessionLifetime)));
    }

    private Principal principal(UUID sessionId, UUID userId, UUID activeCompanyId, OffsetDateTime expiresAt) {
        UserRow user = jdbc.sql("SELECT * FROM app_user WHERE id = :id").param("id", userId).query((rs, n) -> row(rs))
                .single();
        Set<UUID> memberships = new LinkedHashSet<>(jdbc.sql(
                        "SELECT company_id FROM company_membership WHERE user_id = :u ORDER BY created_at")
                .param("u", userId).query(UUID.class).list());
        return new Principal(sessionId, userId, user.email(), user.displayName(), user.operator(), memberships,
                activeCompanyId, expiresAt);
    }

    private UUID insertUser(String email, String displayName, char[] password, boolean operator) {
        String normalized = normalize(email);
        List<ApiError.FieldViolation> violations = new java.util.ArrayList<>();
        if (normalized == null || !normalized.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || normalized.length() > 254) {
            violations.add(new ApiError.FieldViolation("email", "must be a valid e-mail address"));
        }
        if (displayName == null || displayName.isBlank() || displayName.strip().length() > 120) {
            violations.add(new ApiError.FieldViolation("displayName", "must not be blank, 120 characters at most"));
        }
        if (password == null || password.length < MIN_PASSWORD_LENGTH || password.length > 200) {
            violations.add(new ApiError.FieldViolation("password",
                    "must have at least " + MIN_PASSWORD_LENGTH + " characters"));
        }
        if (!violations.isEmpty()) {
            throw new MystixException(ErrorCode.VALIDATION_FAILED, "Invalid account", violations, List.of(), null);
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                            INSERT INTO app_user (id, email, display_name, password_hash, platform_role, created_at)
                            VALUES (:id, :email, :name, :hash, :role, :at)
                            """)
                    .param("id", id).param("email", normalized).param("name", displayName.strip())
                    .param("hash", PasswordHasher.hash(password)).param("role", operator ? "OPERATOR" : "NONE")
                    .param("at", OffsetDateTime.now(clock))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new MystixException(ErrorCode.AUTH_EMAIL_TAKEN, "E-mail already used", e);
        }
        return id;
    }

    private Optional<UserRow> user(String email) {
        if (email == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT * FROM app_user WHERE email = :email").param("email", email)
                .query((rs, n) -> row(rs)).optional();
    }

    private static UserRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserRow(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"),
                rs.getString("password_hash"), "OPERATOR".equals(rs.getString("platform_role")),
                rs.getInt("failed_attempts"), rs.getObject("locked_until", OffsetDateTime.class));
    }

    private static String normalize(String email) {
        return email == null || email.isBlank() ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    private static MystixException invalidCredentials() {
        return new MystixException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid credentials");
    }
}
