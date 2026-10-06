package com.example.apiverification.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.apiverification.dto.UserRequest;
import com.example.apiverification.dto.UserResponse;
import com.example.apiverification.entity.User;
import com.example.apiverification.exception.DuplicateEmailException;
import com.example.apiverification.exception.InvalidRequestParameterException;
import com.example.apiverification.exception.InvalidUserIdException;
import com.example.apiverification.exception.UserNotFoundException;
import com.example.apiverification.mapper.UserMapper;
import com.example.apiverification.repository.UserRepository;

/**
 * Business rules for users: id validation, email uniqueness, existence checks and timestamps.
 * Field-level format rules (blank name, age range, ...) are enforced earlier by bean validation on the DTO.
 */
@Service
@Transactional(readOnly = true)
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final Clock clock;

    public UserService(UserRepository userRepository, UserMapper userMapper, Clock clock) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.clock = clock;
    }

    @Transactional
    public UserResponse createUser(UserRequest request) {
        String email = UserMapper.normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }

        User user = userMapper.toNewEntity(request);
        Instant now = now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        User saved = userRepository.save(user);
        log.info("Created user id={}", saved.getId());
        return userMapper.toResponse(saved);
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findAll(Sort.by("id")).stream()
                .map(userMapper::toResponse)
                .toList();
    }

    public UserResponse getUserById(Long id) {
        return userMapper.toResponse(findExistingUser(id));
    }

    @Transactional
    public UserResponse updateUser(Long id, UserRequest request) {
        User user = findExistingUser(id);

        // Keeping your own email is allowed; taking someone else's is not.
        String email = UserMapper.normalizeEmail(request.email());
        if (userRepository.existsByEmailAndIdNot(email, id)) {
            throw new DuplicateEmailException(email);
        }

        userMapper.applyUpdate(user, request);
        user.setUpdatedAt(now());

        User saved = userRepository.save(user);
        log.info("Updated user id={}", saved.getId());
        return userMapper.toResponse(saved);
    }

    @Transactional
    public void deleteUser(Long id) {
        User user = findExistingUser(id);
        userRepository.delete(user);
        log.info("Deleted user id={}", id);
    }

    public UserResponse findUserByEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new InvalidRequestParameterException("Query parameter 'email' must not be blank");
        }
        return userRepository.findByEmail(UserMapper.normalizeEmail(email))
                .map(userMapper::toResponse)
                .orElseThrow(() -> UserNotFoundException.forEmail(email.trim()));
    }

    private User findExistingUser(Long id) {
        if (id == null || id <= 0) {
            throw new InvalidUserIdException(id);
        }
        return userRepository.findById(id)
                .orElseThrow(() -> UserNotFoundException.forId(id));
    }

    /**
     * Truncated to milliseconds so the value returned right after a write is identical to the value
     * read back from the database later (DB timestamp precision is lower than {@link Instant}'s).
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
