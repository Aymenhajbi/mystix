package ma.mystix.shared.error;

import java.util.List;

/** Business error mapped to a structured {@link ApiError}. The message is for logs only, never shown to users. */
public class MystixException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<ApiError.FieldViolation> fieldErrors;
    private final List<ApiError.RuleViolation> ruleViolations;

    public MystixException(ErrorCode errorCode, String logMessage) {
        this(errorCode, logMessage, List.of(), List.of(), null);
    }

    public MystixException(ErrorCode errorCode, String logMessage, Throwable cause) {
        this(errorCode, logMessage, List.of(), List.of(), cause);
    }

    public MystixException(ErrorCode errorCode, String logMessage, List<ApiError.FieldViolation> fieldErrors,
                           List<ApiError.RuleViolation> ruleViolations, Throwable cause) {
        super(logMessage, cause);
        this.errorCode = errorCode;
        this.fieldErrors = List.copyOf(fieldErrors);
        this.ruleViolations = List.copyOf(ruleViolations);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<ApiError.FieldViolation> fieldErrors() {
        return fieldErrors;
    }

    public List<ApiError.RuleViolation> ruleViolations() {
        return ruleViolations;
    }
}
