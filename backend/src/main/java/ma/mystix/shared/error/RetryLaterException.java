package ma.mystix.shared.error;

import java.time.Duration;

/** A refusal the caller should retry after a delay: the API answers with a {@code Retry-After} header. */
public class RetryLaterException extends MystixException {

    private final Duration retryAfter;

    public RetryLaterException(ErrorCode errorCode, String logMessage, Duration retryAfter) {
        super(errorCode, logMessage);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
