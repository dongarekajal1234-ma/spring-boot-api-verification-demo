package com.example.apiverification.exception;

public class InvalidUserIdException extends ApiException {

    public InvalidUserIdException(Long id) {
        super(ErrorType.INVALID_ID, "User id must be a positive number but was " + id);
    }
}
