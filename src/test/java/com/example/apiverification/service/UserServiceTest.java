package com.example.apiverification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.apiverification.dto.UserRequest;
import com.example.apiverification.dto.UserResponse;
import com.example.apiverification.entity.User;
import com.example.apiverification.entity.UserStatus;
import com.example.apiverification.exception.DuplicateEmailException;
import com.example.apiverification.exception.InvalidRequestParameterException;
import com.example.apiverification.exception.InvalidUserIdException;
import com.example.apiverification.exception.UserNotFoundException;
import com.example.apiverification.mapper.UserMapper;
import com.example.apiverification.repository.UserRepository;

/**
 * Unit tests for business rules in {@link UserService}. The repository is mocked; the mapper is real
 * because it is a plain, side-effect-free class and mocking it would hide normalization bugs.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-01-10T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-01-15T10:30:00Z");

    @Mock
    private UserRepository userRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, new UserMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---- create ----

    @Test
    void shouldCreateUserWhenRequestIsValid() {
        UserRequest request = new UserRequest("  Asha Verma  ", "Asha.Verma@Example.com", 30, "ACTIVE");
        when(userRepository.existsByEmail("asha.verma@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> withId(invocation.getArgument(0), 1L));

        UserResponse response = userService.createUser(request);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("Asha Verma");
        assertThat(response.email()).isEqualTo("asha.verma@example.com");
        assertThat(response.age()).isEqualTo(30);
        assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldRejectDuplicateEmail() {
        // Different casing from the stored address must still count as a duplicate.
        UserRequest request = new UserRequest("Asha Verma", "ASHA.VERMA@EXAMPLE.COM", 30, "ACTIVE");
        when(userRepository.existsByEmail("asha.verma@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(DuplicateEmailException.class)
                .hasMessage("A user with email asha.verma@example.com already exists");
        verify(userRepository, never()).save(any());
    }

    // ---- read ----

    @Test
    void shouldReturnUserWhenIdExists() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser(1L)));

        UserResponse response = userService.getUserById(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("asha.verma@example.com");
    }

    @Test
    void shouldThrowNotFoundWhenUserDoesNotExist() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserById(99L))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User with id 99 was not found");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void shouldRejectNonPositiveUserIdWithoutQueryingDatabase(Long id) {
        assertThatThrownBy(() -> userService.getUserById(id))
                .isInstanceOf(InvalidUserIdException.class);
        verifyNoInteractions(userRepository);
    }

    // ---- update ----

    @Test
    void shouldUpdateUserWhenRequestIsValid() {
        User existing = existingUser(1L);
        UserRequest request = new UserRequest("Asha V. Rao", "Asha.Rao@Example.com", 31, "INACTIVE");
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(userRepository.existsByEmailAndIdNot("asha.rao@example.com", 1L)).thenReturn(false);
        when(userRepository.save(existing)).thenReturn(existing);

        UserResponse response = userService.updateUser(1L, request);

        assertThat(response.name()).isEqualTo("Asha V. Rao");
        assertThat(response.email()).isEqualTo("asha.rao@example.com");
        assertThat(response.age()).isEqualTo(31);
        assertThat(response.status()).isEqualTo(UserStatus.INACTIVE);
        assertThat(response.createdAt()).as("createdAt must never change").isEqualTo(CREATED_AT);
        assertThat(response.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void shouldRejectUpdateWhenEmailBelongsToAnotherUser() {
        User existing = existingUser(1L);
        UserRequest request = new UserRequest("Asha Verma", "ravi.kulkarni@example.com", 30, "ACTIVE");
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(userRepository.existsByEmailAndIdNot("ravi.kulkarni@example.com", 1L)).thenReturn(true);

        assertThatThrownBy(() -> userService.updateUser(1L, request))
                .isInstanceOf(DuplicateEmailException.class);
        verify(userRepository, never()).save(any());
        assertThat(existing.getEmail()).as("entity must not be modified on rejection")
                .isEqualTo("asha.verma@example.com");
    }

    @Test
    void shouldThrowNotFoundWhenUpdatingMissingUser() {
        UserRequest request = new UserRequest("Asha Verma", "asha.verma@example.com", 30, "ACTIVE");
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateUser(42L, request))
                .isInstanceOf(UserNotFoundException.class);
        verify(userRepository, never()).save(any());
    }

    // ---- delete ----

    @Test
    void shouldDeleteUserWhenUserExists() {
        User existing = existingUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));

        userService.deleteUser(1L);

        verify(userRepository).delete(existing);
    }

    @Test
    void shouldThrowNotFoundWhenDeletingMissingUser() {
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteUser(42L))
                .isInstanceOf(UserNotFoundException.class);
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    void shouldRejectNegativeUserIdOnDelete() {
        assertThatThrownBy(() -> userService.deleteUser(-5L))
                .isInstanceOf(InvalidUserIdException.class)
                .hasMessage("User id must be a positive number but was -5");
        verifyNoInteractions(userRepository);
    }

    // ---- search ----

    @Test
    void shouldFindUserByEmailIgnoringCaseAndSurroundingSpaces() {
        when(userRepository.findByEmail("asha.verma@example.com")).thenReturn(Optional.of(existingUser(1L)));

        UserResponse response = userService.findUserByEmail("  ASHA.Verma@example.com ");

        assertThat(response.id()).isEqualTo(1L);
    }

    @Test
    void shouldThrowNotFoundWhenNoUserMatchesEmail() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findUserByEmail("nobody@example.com"))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User with email nobody@example.com was not found");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void shouldRejectBlankSearchEmail(String email) {
        assertThatThrownBy(() -> userService.findUserByEmail(email))
                .isInstanceOf(InvalidRequestParameterException.class);
        verifyNoInteractions(userRepository);
    }

    // ---- helpers ----

    private static User existingUser(Long id) {
        User user = new User("Asha Verma", "asha.verma@example.com", 30, UserStatus.ACTIVE);
        user.setCreatedAt(CREATED_AT);
        user.setUpdatedAt(CREATED_AT);
        return withId(user, id);
    }

    /** The id is generated by the database, so tests set it reflectively instead of adding a public setter. */
    private static User withId(User user, Long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
