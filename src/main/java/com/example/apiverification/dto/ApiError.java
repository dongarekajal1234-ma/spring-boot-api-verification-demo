package com.example.apiverification.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Uniform error body returned for every failed request.
 *
 * @param timestamp   when the error was produced (UTC)
 * @param status      numeric HTTP status code
 * @param error       stable, machine-readable error type (see {@code ErrorType})
 * @param message     human-readable explanation, safe to show to API clients
 * @param path        request path that produced the error
 * @param fieldErrors per-field validation failures; omitted when empty
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldErrorDetail> fieldErrors
) {

    public record FieldErrorDetail(String field, String message) {
    }
}
