package com.example.apiverification.exception;

public class DuplicateEmailException extends ApiException {

    public DuplicateEmailException(String email) {
        super(ErrorType.DUPLICATE_EMAIL, "A user with email " + email + " already exists");
    }
}
