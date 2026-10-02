package ma.mystix.shared.error;

import java.util.List;

import ma.mystix.shared.web.RequestIdFilter;

/**
 * Structured error body returned by every API endpoint.
 * {@code requestId} links the error to the server logs and to the company's processing log.
 */
public record ApiError(
        String errorCode,
        Stage stage,
        boolean retryable,
        LocalizedText userMessage,
        LocalizedText suggestedAction,
        List<FieldViolation> fieldErrors,
        List<RuleViolation> ruleViolations,
        String requestId) {

    /** An input field that failed validation. {@code field} is a JSON path such as {@code lines[0].gtin}. */
    public record FieldViolation(String field, String reason) {
    }

    /** A business rule that failed on the produced document, e.g. EN 16931 BR-CO-15. */
    public record RuleViolation(String ruleId, String severity, String location, String message) {
    }

    public static ApiError of(ErrorCode code, List<FieldViolation> fieldErrors) {
        return of(code, fieldErrors, List.of());
    }

    public static ApiError of(ErrorCode code, List<FieldViolation> fieldErrors, List<RuleViolation> ruleViolations) {
        return new ApiError(code.name(), code.stage(), code.retryable(),
                code.userMessage(), code.suggestedAction(), List.copyOf(fieldErrors), List.copyOf(ruleViolations),
                RequestIdFilter.current());
    }
}
