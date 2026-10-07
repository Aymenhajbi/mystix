package ma.mystix.auth;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import ma.mystix.tenant.Company;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sign-in, first administrator, session and environment of the portal user (ADR-0013). */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    record Credentials(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 200) String password) {
    }

    record SetupRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 120) String displayName,
                        @NotBlank @Size(max = 200) String password) {
    }

    record NewUser(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 120) String displayName,
                   @NotBlank @Size(max = 200) String password, @NotNull UUID companyId,
                   @NotBlank @Pattern(regexp = "^(ADMIN|MEMBER)$") String role) {
    }

    record CompanySwitch(@NotNull UUID companyId) {
    }

    record Environment(UUID id, String ice, String legalName) {
        static Environment of(Company c) {
            return new Environment(c.id(), c.ice().value(), c.legalName());
        }
    }

    /** Who is signed in, the environment the portal works in, and the ones the user may switch to. */
    record Me(UUID userId, String email, String displayName, boolean operator, Environment activeCompany,
              List<Environment> companies, OffsetDateTime expiresAt) {
    }

    /** The token is given once, for the portal to keep in an HttpOnly cookie. */
    record Session(String token, OffsetDateTime expiresAt, Me me) {
    }

    record SetupStatus(boolean setupNeeded) {
    }

    @GetMapping(value = "/setup", produces = MediaType.APPLICATION_JSON_VALUE)
    SetupStatus setupStatus() {
        return new SetupStatus(auth.setupNeeded());
    }

    @PostMapping(value = "/setup", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Session> setup(@Valid @RequestBody SetupRequest r, HttpServletRequest request) {
        AuthService.LoginResult result = auth.setup(r.email(), r.displayName(), r.password().toCharArray(),
                request.getHeader("User-Agent"));
        return ResponseEntity.status(201).body(session(result));
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    Session login(@Valid @RequestBody Credentials c, HttpServletRequest request) {
        return session(auth.login(c.email(), c.password().toCharArray(), request.getHeader("User-Agent")));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        auth.logout(principal(request).sessionId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    Me me(HttpServletRequest request) {
        return me(principal(request));
    }

    @PutMapping(value = "/session/company", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    Me switchCompany(@Valid @RequestBody CompanySwitch s, HttpServletRequest request) {
        return me(auth.switchCompany(principal(request), s.companyId()));
    }

    /** A platform operator adds a user to an environment. TODO: invitations and password reset by e-mail. */
    @PostMapping(value = "/users", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<java.util.Map<String, UUID>> createUser(@Valid @RequestBody NewUser u, HttpServletRequest request) {
        if (!principal(request).operator()) {
            throw new MystixException(ErrorCode.AUTH_FORBIDDEN, "Only a platform operator creates users");
        }
        UUID id = auth.createUser(u.email(), u.displayName(), u.password().toCharArray(), u.companyId(), u.role());
        return ResponseEntity.status(201).body(java.util.Map.of("id", id));
    }

    private Session session(AuthService.LoginResult result) {
        return new Session(result.token(), result.principal().expiresAt(), me(result.principal()));
    }

    private Me me(Principal p) {
        List<Company> companies = auth.companiesOf(p);
        Environment active = companies.stream().filter(c -> c.id().equals(p.activeCompanyId())).findFirst()
                .map(Environment::of).orElse(null);
        return new Me(p.userId(), p.email(), p.displayName(), p.operator(), active,
                companies.stream().map(Environment::of).toList(), p.expiresAt());
    }

    private static Principal principal(HttpServletRequest request) {
        Object p = request.getAttribute(AuthFilter.PRINCIPAL);
        if (p instanceof Principal principal) {
            return principal;
        }
        throw new MystixException(ErrorCode.AUTH_REQUIRED, "No session");
    }
}
