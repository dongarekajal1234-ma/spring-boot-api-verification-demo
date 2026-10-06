package com.example.apiverification.exception;

/**
 * Base class for expected, client-facing business errors. The message must be safe to return to clients.
 */
public abstract class ApiException extends RuntimeException {

    private final ErrorType errorType;

    protected ApiException(ErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public ErrorType getErrorType() {
        return errorType;
    }
}
