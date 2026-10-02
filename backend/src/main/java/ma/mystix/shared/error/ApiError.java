package ma.mystix.shared.error;

import java.util.List;

/** Structured error body returned by every API endpoint. */
public record ApiError(
        String errorCode,
        Stage stage,
        boolean retryable,
        LocalizedText userMessage,
        LocalizedText suggestedAction,
        List<FieldViolation> fieldErrors) {

    public record FieldViolation(String field, String reason) {
    }

    public static ApiError of(ErrorCode code, List<FieldViolation> fieldErrors) {
        return new ApiError(code.name(), code.stage(), code.retryable(),
                code.userMessage(), code.suggestedAction(), List.copyOf(fieldErrors));
    }
}
