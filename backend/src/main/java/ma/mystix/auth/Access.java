package ma.mystix.auth;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import ma.mystix.shared.error.ErrorCode;
import ma.mystix.shared.error.MystixException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Who acts, and whether they may perform a sensitive action (ADR-0014): validating a bill of materials, releasing or
 * blocking a lot, approving an inventory, closing a work order. Sensitive actions need a platform operator or an
 * ADMIN of the environment. Without authentication (developer profile, tests) the actor is "dev" and the check passes.
 */
@Component
public class Access {

    private final JdbcClient jdbc;
    private final boolean required;

    Access(JdbcClient jdbc, @Value("${mystix.auth.required:true}") boolean required) {
        this.jdbc = jdbc;
        this.required = required;
    }

    /** The e-mail of the signed-in user, or "dev" when authentication is off. */
    public String actor(HttpServletRequest request) {
        Principal p = principal(request);
        return p == null ? "dev" : p.email();
    }

    public void requireManager(HttpServletRequest request, UUID companyId) {
        Principal p = principal(request);
        if (p == null) {
            if (required) {
                throw new MystixException(ErrorCode.AUTH_REQUIRED, "No principal for a sensitive action");
            }
            return;
        }
        if (p.operator()) {
            return;
        }
        boolean admin = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM company_membership
                                       WHERE user_id = :u AND company_id = :c AND role = 'ADMIN')
                        """)
                .param("u", p.userId()).param("c", companyId).query(Boolean.class).single();
        if (!admin) {
            throw new MystixException(ErrorCode.AUTH_FORBIDDEN,
                    p.email() + " is not an administrator of " + companyId);
        }
    }

    private static Principal principal(HttpServletRequest request) {
        return request.getAttribute(AuthFilter.PRINCIPAL) instanceof Principal p ? p : null;
    }
}
