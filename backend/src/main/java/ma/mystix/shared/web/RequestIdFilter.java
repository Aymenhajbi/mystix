package ma.mystix.shared.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an identifier, returned in {@value #HEADER} and in error bodies, and put in the logging
 * context so each server log line of the request carries it. A caller-supplied identifier is kept when it is safe.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    public static final String MDC_COMPANY = "companyId";

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String requestId = supplied != null && SAFE.matcher(supplied).matches() ? supplied : newId();
        String company = request.getHeader("X-Mystix-Company-Id");
        MDC.put(MDC_KEY, requestId);
        if (company != null && company.length() <= 36) {
            MDC.put(MDC_COMPANY, company);
        }
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove(MDC_COMPANY);
        }
    }

    /** Current request identifier, or {@code null} outside a request. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    private static String newId() {
        return "req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
