package com.example.apiverification.exception;

public class UserNotFoundException extends ApiException {

    private UserNotFoundException(String message) {
        super(ErrorType.USER_NOT_FOUND, message);
    }

    public static UserNotFoundException forId(Long id) {
        return new UserNotFoundException("User with id " + id + " was not found");
    }

    public static UserNotFoundException forEmail(String email) {
        return new UserNotFoundException("User with email " + email + " was not found");
    }
}
