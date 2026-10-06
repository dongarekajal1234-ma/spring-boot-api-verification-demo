package com.example.apiverification.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Unit tests for the field-level rules declared on {@link UserRequest}.
 * Runs the real Bean Validation engine without starting Spring.
 */
class UserRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void shouldAcceptRequestWhenAllFieldsAreValid() {
        assertThat(validator.validate(validRequest())).isEmpty();
    }

    // ---- name ----

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void shouldRejectUserWhenNameIsBlank(String name) {
        UserRequest request = new UserRequest(name, "asha.verma@example.com", 30, "ACTIVE");

        assertThat(messagesFor(request, "name")).contains("Name is required");
    }

    @Test
    void shouldRejectUserWhenNameIsMissing() {
        UserRequest request = new UserRequest(null, "asha.verma@example.com", 30, "ACTIVE");

        assertThat(messagesFor(request, "name")).containsExactly("Name is required");
    }

    @Test
    void shouldAcceptNameAtMaximumLength() {
        UserRequest request = new UserRequest("a".repeat(100), "asha.verma@example.com", 30, "ACTIVE");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void shouldRejectNameLongerThanMaximumLength() {
        UserRequest request = new UserRequest("a".repeat(101), "asha.verma@example.com", 30, "ACTIVE");

        assertThat(messagesFor(request, "name")).containsExactly("Name must not exceed 100 characters");
    }

    // ---- email ----

    @ParameterizedTest
    @ValueSource(strings = {
            "plainaddress",
            "missing-at-sign.example.com",
            "user@",
            "@example.com",
            "user@@example.com",
            "user name@example.com",
            "user@localhost"   // accepted by the default @Email check; rejected by our stricter pattern
    })
    void shouldRejectUserWhenEmailIsInvalid(String email) {
        UserRequest request = new UserRequest("Asha Verma", email, 30, "ACTIVE");

        assertThat(messagesFor(request, "email")).contains("Email must be a valid email address");
    }

    @Test
    void shouldRejectUserWhenEmailIsMissing() {
        UserRequest request = new UserRequest("Asha Verma", null, 30, "ACTIVE");

        assertThat(messagesFor(request, "email")).containsExactly("Email is required");
    }

    @Test
    void shouldRejectEmailLongerThanMaximumLength() {
        // 64-char local part + 4 domain labels of 60 chars + ".com" = 313 characters
        String longEmail = "a".repeat(64) + "@" + ("b".repeat(60) + ".").repeat(4) + "com";
        UserRequest request = new UserRequest("Asha Verma", longEmail, 30, "ACTIVE");

        assertThat(messagesFor(request, "email")).contains("Email must not exceed 254 characters");
    }

    // ---- age ----

    @ParameterizedTest
    @ValueSource(ints = {17, 0, -1, Integer.MIN_VALUE})
    void shouldRejectAgeBelowMinimum(int age) {
        UserRequest request = new UserRequest("Asha Verma", "asha.verma@example.com", age, "ACTIVE");

        assertThat(messagesFor(request, "age")).containsExactly("Age must be at least 18");
    }

    @ParameterizedTest
    @ValueSource(ints = {101, 150, Integer.MAX_VALUE})
    void shouldRejectAgeAboveMaximum(int age) {
        UserRequest request = new UserRequest("Asha Verma", "asha.verma@example.com", age, "ACTIVE");

        assertThat(messagesFor(request, "age")).containsExactly("Age must not exceed 100");
    }

    @ParameterizedTest
    @ValueSource(ints = {18, 100})
    void shouldAcceptAgeAtInclusiveBoundaries(int age) {
        UserRequest request = new UserRequest("Asha Verma", "asha.verma@example.com", age, "ACTIVE");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void shouldRejectUserWhenAgeIsMissing() {
        UserRequest request = new UserRequest("Asha Verma", "asha.verma@example.com", null, "ACTIVE");

        assertThat(messagesFor(request, "age")).containsExactly("Age is required");
    }

    // ---- status ----

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "INACTIVE"})
    void shouldAcceptSupportedStatuses(String status) {
        UserRequest request = new UserRequest("Asha Verma", "asha.verma@example.com", 30, status);

        assertThat(validator.validate(request)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "active", "Active", " ACTIVE", ""})
    void shouldRejectUnsupportedStatus(String status) {
        UserRequest request = new UserRequest("Asha Verma", "asha.verma@example.com", 30, status);

        assertThat(messagesFor(request, "status")).containsExactly("Status must be ACTIVE or INACTIVE");
    }

    @Test
    void shouldRejectUserWhenStatusIsMissing() {
        UserRequest request = new UserRequest("Asha Verma", "asha.verma@example.com", 30, null);

        assertThat(messagesFor(request, "status")).containsExactly("Status is required");
    }

    @Test
    void shouldReportEveryInvalidFieldInOnePass() {
        UserRequest request = new UserRequest(" ", "not-an-email", 12, "UNKNOWN");

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("name", "email", "age", "status");
    }

    private static UserRequest validRequest() {
        return new UserRequest("Asha Verma", "asha.verma@example.com", 30, "ACTIVE");
    }

    private static List<String> messagesFor(UserRequest request, String field) {
        return validator.validate(request).stream()
                .filter(violation -> violation.getPropertyPath().toString().equals(field))
                .map(ConstraintViolation::getMessage)
                .toList();
    }
}
