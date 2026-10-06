package com.example.apiverification.exception;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.example.apiverification.dto.ApiError;
import com.example.apiverification.dto.ApiError.FieldErrorDetail;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Translates every exception into the uniform {@link ApiError} body.
 * <p>
 * Rule of thumb: client errors (4xx) explain what to fix; server errors (5xx) never expose internals
 * such as exception messages, SQL, class names or stack traces.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Business errors raised by the service layer (not found, duplicate email, invalid id, ...). */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        return build(ex.getErrorType(), ex.getMessage(), request);
    }

    /** Bean Validation failures on the request body. All field errors are returned, sorted for stable output. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldErrorDetail> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorDetail(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(FieldErrorDetail::field).thenComparing(FieldErrorDetail::message))
                .toList();
        return build(ErrorType.VALIDATION_FAILED, "Request validation failed", request, fieldErrors);
    }

    /** Body is missing, is not valid JSON, has the wrong shape, or contains unknown/mistyped fields. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(ErrorType.MALFORMED_REQUEST, describeUnreadableBody(ex), request);
    }

    /** Path or query parameter could not be converted, e.g. GET /api/users/abc. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        if ("id".equals(ex.getName())) {
            return build(ErrorType.INVALID_ID, "User id must be a positive number", request);
        }
        return build(ErrorType.INVALID_PARAMETER, "Invalid value for parameter '" + ex.getName() + "'", request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex,
                                                           HttpServletRequest request) {
        return build(ErrorType.INVALID_PARAMETER,
                "Required query parameter '" + ex.getParameterName() + "' is missing", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                             HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            headers.setAllow(supported);
        }
        ApiError body = body(ErrorType.METHOD_NOT_ALLOWED.status(), ErrorType.METHOD_NOT_ALLOWED.name(),
                "HTTP method " + ex.getMethod() + " is not supported for this endpoint", request, List.of());
        return ResponseEntity.status(ErrorType.METHOD_NOT_ALLOWED.status()).headers(headers).body(body);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
                                                                HttpServletRequest request) {
        return build(ErrorType.UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return build(ErrorType.RESOURCE_NOT_FOUND, "No endpoint exists at this path", request);
    }

    /**
     * Safety net for constraint violations that slip past the service-level checks,
     * e.g. two concurrent requests creating the same email. The DB unique constraint is the final guard.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(ErrorType.DATA_CONFLICT, "The request conflicts with existing data", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Other Spring MVC exceptions (e.g. 406 Not Acceptable) already know their status; keep it instead of a 500.
        if (ex instanceof ErrorResponse springError && springError.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = springError.getStatusCode();
            return ResponseEntity.status(status)
                    .body(body(status, "REQUEST_ERROR", "The request could not be processed", request, List.of()));
        }
        log.error("Unexpected error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(ErrorType.INTERNAL_ERROR, "An unexpected error occurred", request);
    }

    private static String describeUnreadableBody(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        if (cause == null) {
            // Spring raises this without a cause when the body is empty or the literal JSON null.
            return "Request body is missing";
        }
        if (cause instanceof UnrecognizedPropertyException unknown) {
            return "Unknown field '" + unknown.getPropertyName() + "'";
        }
        if (cause instanceof MismatchedInputException mismatch) {
            String field = fieldPath(mismatch);
            return field.isEmpty()
                    ? "Request body must be a JSON object"
                    : "Invalid value for field '" + field + "'";
        }
        if (cause instanceof JsonProcessingException) {
            return "Malformed JSON request body";
        }
        return "Request body could not be read";
    }

    private static String fieldPath(MismatchedInputException ex) {
        return ex.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."));
    }

    private static ResponseEntity<ApiError> build(ErrorType type, String message, HttpServletRequest request) {
        return build(type, message, request, List.of());
    }

    private static ResponseEntity<ApiError> build(ErrorType type, String message, HttpServletRequest request,
                                                  List<FieldErrorDetail> fieldErrors) {
        return ResponseEntity.status(type.status())
                .body(body(type.status(), type.name(), message, request, fieldErrors));
    }

    private static ApiError body(HttpStatusCode status, String error, String message, HttpServletRequest request,
                                 List<FieldErrorDetail> fieldErrors) {
        return new ApiError(Instant.now(), status.value(), error, message, request.getRequestURI(), fieldErrors);
    }
}
