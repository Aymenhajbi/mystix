package ma.mystix.auth;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import ma.mystix.shared.error.ApiError;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.web.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authenticates API calls (ADR-0013). With a {@code Bearer} session token, the environment comes from the session:
 * {@code X-Mystix-Company-Id} is set to the active environment, or, when the caller names one, it must be an
 * environment of the user; operator endpoints require a platform operator. Without a token the call is refused when
 * {@code mystix.auth.required} is on (the default); developer profiles and tests may turn it off.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL = "mystix.principal";
    static final String COMPANY = "X-Mystix-Company-Id";
    private static final List<String> PUBLIC = List.of("/api/v1/auth/login", "/api/v1/auth/setup");

    private final AuthService auth;
    private final JsonMapper json;
    private final boolean required;

    AuthFilter(AuthService auth, JsonMapper json, @Value("${mystix.auth.required:true}") boolean required) {
        this.auth = auth;
        this.json = json;
        this.required = required;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/") || PUBLIC.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            if (required) {
                deny(response, ErrorCode.AUTH_REQUIRED);
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        Optional<Principal> principal = auth.authenticate(header.substring(7).strip());
        if (principal.isEmpty()) {
            deny(response, ErrorCode.AUTH_SESSION_INVALID);
            return;
        }
        Principal p = principal.get();
        if (request.getRequestURI().startsWith("/api/v1/admin/") && !p.operator()) {
            deny(response, ErrorCode.AUTH_FORBIDDEN);
            return;
        }
        String named = request.getHeader(COMPANY);
        UUID company;
        if (named != null && !named.isBlank()) {
            try {
                company = UUID.fromString(named.strip());
            } catch (IllegalArgumentException e) {
                deny(response, ErrorCode.AUTH_FORBIDDEN);
                return;
            }
            if (!p.canAccess(company)) {
                deny(response, ErrorCode.AUTH_FORBIDDEN);
                return;
            }
        } else {
            company = p.activeCompanyId();
        }
        request.setAttribute(PRINCIPAL, p);
        if (company != null) {
            MDC.put(RequestIdFilter.MDC_COMPANY, company.toString());
        }
        chain.doFilter(company == null ? request : new CompanyRequest(request, company.toString()), response);
    }

    private void deny(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(json.writeValueAsBytes(ApiError.of(code, List.of())));
    }

    /** The request seen by the controllers: its environment header is the one the session allows. */
    private static final class CompanyRequest extends HttpServletRequestWrapper {
        private final String company;

        CompanyRequest(HttpServletRequest request, String company) {
            super(request);
            this.company = company;
        }

        @Override
        public String getHeader(String name) {
            return COMPANY.equalsIgnoreCase(name) ? company : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return COMPANY.equalsIgnoreCase(name) ? Collections.enumeration(List.of(company)) : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            if (names.stream().noneMatch(COMPANY::equalsIgnoreCase)) {
                names.add(COMPANY);
            }
            return Collections.enumeration(names);
        }
    }
}
