package com.example.apiverification.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for both create (POST) and full update (PUT). All fields are required.
 * <p>
 * Server-managed fields ({@code id}, {@code createdAt}, {@code updatedAt}) are intentionally absent;
 * sending them is rejected because unknown JSON properties are configured to fail.
 */
public record UserRequest(

        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must not exceed 100 characters")
        String name,

        // The default @Email check accepts addresses without a domain dot, such as "user@localhost".
        // The extra pattern requires at least one dot in the domain part, which is what a public-facing API expects.
        @NotBlank(message = "Email is required")
        @Email(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "Email must be a valid email address")
        @Size(max = 254, message = "Email must not exceed 254 characters")
        String email,

        @NotNull(message = "Age is required")
        @Min(value = 18, message = "Age must be at least 18")
        @Max(value = 100, message = "Age must not exceed 100")
        Integer age,

        // Kept as a String (not the enum) so an unsupported value produces a normal field validation
        // error instead of a generic deserialization failure. Matching is case-sensitive.
        @NotNull(message = "Status is required")
        @Pattern(regexp = "ACTIVE|INACTIVE", message = "Status must be ACTIVE or INACTIVE")
        String status
) {
}
