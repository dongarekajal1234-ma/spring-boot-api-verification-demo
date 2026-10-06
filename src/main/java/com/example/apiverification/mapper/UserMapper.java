package com.example.apiverification.mapper;

import java.util.Locale;

import org.springframework.stereotype.Component;

import com.example.apiverification.dto.UserRequest;
import com.example.apiverification.dto.UserResponse;
import com.example.apiverification.entity.User;
import com.example.apiverification.entity.UserStatus;

/**
 * Converts between API DTOs and the JPA entity, and applies input normalization.
 * Assumes the request has already passed bean validation.
 */
@Component
public class UserMapper {

    public User toNewEntity(UserRequest request) {
        return new User(
                request.name().trim(),
                normalizeEmail(request.email()),
                request.age(),
                UserStatus.valueOf(request.status()));
    }

    public void applyUpdate(User user, UserRequest request) {
        user.setName(request.name().trim());
        user.setEmail(normalizeEmail(request.email()));
        user.setAge(request.age());
        user.setStatus(UserStatus.valueOf(request.status()));
    }

    public UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getAge(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }

    /**
     * Emails are compared case-insensitively: "Asha@Example.com" and "asha@example.com" are the same user.
     */
    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
