package org.os.gitbase.exception;

import lombok.extern.slf4j.Slf4j;
import org.os.gitbase.common.ApiResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Centralizes exception → HTTP mapping for every REST controller.
 *
 * <p>Every error is returned in the canonical {@link ApiResponseEntity} envelope so the
 * frontend can rely on a single error shape: {@code {success:false, error, message, httpStatus}}.
 *
 * <p>The Git smart-HTTP endpoints ({@code /api/v1/gitbase/**}) deliberately bypass this
 * handler — they stream raw pkt-line protocol data and manage their own status codes.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** Bean validation failures on {@code @Valid @RequestBody} → 400 with field details. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponseEntity<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.debug("Validation failure: {}", details);
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", details.isBlank() ? "Validation failed" : details);
    }

    /** Invalid arguments / malformed input surfaced from the service layer → 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponseEntity<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.debug("Bad request: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }

    /** Resource lookup miss → 404. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponseEntity<Void>> handleNotFound(ResourceNotFoundException ex) {
        log.debug("Not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    /** Ownership / permission violations → 403. */
    @ExceptionHandler(AccessDeniedDomainException.class)
    public ResponseEntity<ApiResponseEntity<Void>> handleAccessDenied(AccessDeniedDomainException ex) {
        log.debug("Access denied: {}", ex.getMessage());
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage());
    }

    /** Path-traversal / security guard trips → 403, with a generic message (no internals leaked). */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponseEntity<Void>> handleSecurity(SecurityException ex) {
        log.warn("Security exception: {}", ex.getMessage());
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "Operation not permitted");
    }

    /** Catch-all → 500, without exposing the stack trace or internal message to the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponseEntity<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private ResponseEntity<ApiResponseEntity<Void>> build(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(ApiResponseEntity.failure(status, error, message));
    }

    private String formatFieldError(FieldError fe) {
        return fe.getField() + ": " + (fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage());
    }
}
