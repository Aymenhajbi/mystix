package ma.mystix.shared.error;

/** Business error mapped to a structured {@link ApiError}. The message is for logs only, never shown to users. */
public class MystixException extends RuntimeException {

    private final ErrorCode errorCode;

    public MystixException(ErrorCode errorCode, String logMessage) {
        super(logMessage);
        this.errorCode = errorCode;
    }

    public MystixException(ErrorCode errorCode, String logMessage, Throwable cause) {
        super(logMessage, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
