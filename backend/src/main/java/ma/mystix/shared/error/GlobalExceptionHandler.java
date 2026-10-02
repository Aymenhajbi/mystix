package ma.mystix.shared.error;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MystixException.class)
    ResponseEntity<ApiError> handleMystix(MystixException e) {
        log.info("Business error {}: {}", e.errorCode(), e.getMessage());
        return ResponseEntity.status(e.errorCode().status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.of(e.errorCode(), e.fieldErrors(), e.ruleViolations()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleInvalidBody(MethodArgumentNotValidException e) {
        List<ApiError.FieldViolation> violations = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldViolation(f.getField(), f.getDefaultMessage()))
                .toList();
        return respond(ErrorCode.VALIDATION_FAILED, violations);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException e) {
        return respond(ErrorCode.VALIDATION_FAILED,
                List.of(new ApiError.FieldViolation(e.getHeaderName(), "required header is missing")));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> handleUnreadable(Exception e) {
        log.info("Unreadable request: {}", e.getMessage());
        return respond(ErrorCode.VALIDATION_FAILED, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNoResource(NoResourceFoundException e) {
        return respond(ErrorCode.RESOURCE_NOT_FOUND, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException e) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return respond(ErrorCode.INTERNAL_ERROR, List.of());
    }

    private static ResponseEntity<ApiError> respond(ErrorCode code, List<ApiError.FieldViolation> violations) {
        // Errors are always JSON, even when the client asked for the XML output of a successful call.
        return ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.of(code, violations));
    }
}
