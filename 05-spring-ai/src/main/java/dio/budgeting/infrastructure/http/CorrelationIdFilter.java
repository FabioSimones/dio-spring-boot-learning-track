package dio.budgeting.infrastructure.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Resolves a per-request correlation ID (TASK-011), so every log line and
 * response can be tied back to one HTTP call without exposing anything
 * business-sensitive - this is a purely technical, client-supplied-or-generated
 * token, never the {@code Idempotency-Key}.
 *
 * <p>A client-supplied {@value #HEADER} is reused only if it looks safe
 * (bounded length, no whitespace/control characters); anything else - missing,
 * blank, too long, or containing unsafe characters - is silently replaced with
 * a fresh random UUID rather than rejecting the request. Operational
 * robustness wins here: a malformed correlation header is a client mistake,
 * not a reason to fail the call.
 *
 * <p>Registered automatically by Spring Boot as a {@code Filter} bean (no
 * {@code FilterRegistrationBean} needed). Runs for every request, including
 * ones that end in an error response, since the header is written to the
 * response and the MDC key is set before {@code chain.doFilter} runs.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    private static final int MAX_LENGTH = 64;
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = resolve(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        }
        finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolve(String incoming) {
        return isSafe(incoming) ? incoming : UUID.randomUUID().toString();
    }

    private static boolean isSafe(String value) {
        return value != null && value.length() <= MAX_LENGTH && SAFE_VALUE.matcher(value).matches();
    }
}
