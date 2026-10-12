package com.driftwatch.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps exceptions to the JSON error contract {@code {error, message, timestamp}}. Framework
 * exceptions that carry their own status (404 unknown resource, 405, 406, 415, 400 for missing
 * parameters, ...) keep that status instead of being flattened into 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return jsonError(HttpStatus.BAD_REQUEST, "validation_failed", message);
    }

    /** Batch items are validated through the container, which reports a method-level failure. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleMethodValidation(HandlerMethodValidationException ex) {
        return jsonError(HttpStatus.BAD_REQUEST, "validation_failed", validationMessage(ex));
    }

    /**
     * Explicit status codes raised by the ingest contract (400 invalid key or payload, 409
     * idempotency conflict, 503 unconfirmed publish) must reach the caller instead of being
     * flattened into 500.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        HttpStatusCode status = ex.getStatusCode();
        return jsonError(status, statusLabel(status),
                ex.getReason() == null ? "request rejected" : ex.getReason());
    }

    /** Malformed or unparsable bodies are client errors, not server errors. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return jsonError(HttpStatus.BAD_REQUEST, "invalid_payload",
                "Request body could not be parsed as JSON");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return jsonError(HttpStatus.BAD_REQUEST, "bad_request", ex.getMessage());
    }

    /** Unparsable enum or number query parameters are client errors; never echo the raw value. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return jsonError(HttpStatus.BAD_REQUEST, "bad_request",
                "invalid value for parameter '" + ex.getName() + "'");
    }

    /**
     * Any framework exception that implements {@link ErrorResponse} (unknown static resource 404,
     * unsupported method 405, unacceptable media type 406, unsupported media type 415, missing
     * parameter 400, ...) answers with its own status. Anything else is an unexpected failure.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneral(Exception ex) {
        if (ex instanceof ErrorResponse response) {
            return frameworkError(response);
        }
        log.error("Unhandled exception", ex);
        return jsonError(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "An unexpected error occurred");
    }

    private static ResponseEntity<?> frameworkError(ErrorResponse response) {
        HttpStatusCode status = response.getStatusCode();
        if (status.value() == HttpStatus.NOT_ACCEPTABLE.value()) {
            // The Accept header cannot be satisfied, so no body can be negotiated either.
            return ResponseEntity.status(status).build();
        }
        String detail = response.getBody().getDetail();
        return jsonError(status, statusLabel(status), detail);
    }

    /** Stable machine-readable label for an HTTP status. */
    static String statusLabel(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "bad_request";
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 406 -> "not_acceptable";
            case 409 -> "idempotency_conflict";
            case 415 -> "unsupported_media_type";
            case 503 -> "unconfirmed";
            default -> status.is5xxServerError() ? "internal_error" : "request_rejected";
        };
    }

    /** Names each failing field, with its batch index where the failure is inside a list item. */
    private static String validationMessage(HandlerMethodValidationException ex) {
        List<String> parts = new ArrayList<>();
        for (ParameterValidationResult result : ex.getAllValidationResults()) {
            String name = result.getMethodParameter() == null ? null : result.getMethodParameter().getParameterName();
            String prefix = (name == null ? "body" : name)
                    + (result.getContainerIndex() == null ? "" : "[" + result.getContainerIndex() + "]");
            if (result instanceof ParameterErrors errors && errors.hasFieldErrors()) {
                for (FieldError fieldError : errors.getFieldErrors()) {
                    parts.add(prefix + "." + fieldError.getField() + ": " + fieldError.getDefaultMessage());
                }
            } else {
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    parts.add(prefix + ": " + error.getDefaultMessage());
                }
            }
        }
        return parts.isEmpty() ? "Validation failed" : String.join("; ", parts);
    }

    private static ResponseEntity<Map<String, Object>> jsonError(HttpStatusCode status, String error, String message) {
        // An explicit Content-Type keeps the error body from being re-negotiated against Accept.
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "error", error,
                        "message", message == null ? "request rejected" : message,
                        "timestamp", Instant.now().toString()));
    }
}
