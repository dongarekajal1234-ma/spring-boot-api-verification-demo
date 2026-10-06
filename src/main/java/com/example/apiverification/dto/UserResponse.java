package com.example.apiverification.dto;

import java.time.Instant;

import com.example.apiverification.entity.UserStatus;

public record UserResponse(
        Long id,
        String name,
        String email,
        Integer age,
        UserStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
