package com.example.apiverification.exception;

public class InvalidRequestParameterException extends ApiException {

    public InvalidRequestParameterException(String message) {
        super(ErrorType.INVALID_PARAMETER, message);
    }
}
