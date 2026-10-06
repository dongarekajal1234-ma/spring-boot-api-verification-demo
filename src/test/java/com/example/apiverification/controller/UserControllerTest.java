package com.example.apiverification.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.apiverification.dto.UserRequest;
import com.example.apiverification.dto.UserResponse;
import com.example.apiverification.entity.UserStatus;
import com.example.apiverification.exception.DuplicateEmailException;
import com.example.apiverification.exception.UserNotFoundException;
import com.example.apiverification.service.UserService;

/**
 * Web-layer tests: request binding, validation, status codes and the error contract.
 * The service is mocked, so these tests verify HTTP behaviour only, not business rules.
 */
@WebMvcTest(UserController.class)
class UserControllerTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-01-15T10:30:00Z");

    private static final String VALID_BODY = """
            {"name": "Asha Verma", "email": "asha.verma@example.com", "age": 30, "status": "ACTIVE"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    // ---- success paths ----

    @Test
    void shouldCreateUserWhenRequestIsValid() throws Exception {
        when(userService.createUser(any(UserRequest.class))).thenReturn(sampleUser());

        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/users/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.email").value("asha.verma@example.com"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-15T10:30:00Z"));
    }

    @Test
    void shouldReturnUserWhenIdExists() throws Exception {
        when(userService.getUserById(1L)).thenReturn(sampleUser());

        mockMvc.perform(get("/api/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Asha Verma"));
    }

    @Test
    void shouldUpdateUserWhenRequestIsValid() throws Exception {
        when(userService.updateUser(eq(1L), any(UserRequest.class))).thenReturn(sampleUser());

        mockMvc.perform(put("/api/users/1").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void shouldReturnNoContentWhenUserIsDeleted() throws Exception {
        mockMvc.perform(delete("/api/users/1"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(userService).deleteUser(1L);
    }

    // ---- validation ----

    @Test
    void shouldRejectUserWhenEmailIsInvalid() throws Exception {
        String body = """
                {"name": "Asha Verma", "email": "asha.verma@", "age": 30, "status": "ACTIVE"}
                """;

        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.path").value("/api/users"))
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("email"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Email must be a valid email address"));

        verifyNoInteractions(userService);
    }

    @Test
    void shouldReturnEveryMissingFieldWhenBodyIsEmptyObject() throws Exception {
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors", hasSize(4)))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("age"))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("email"))
                .andExpect(jsonPath("$.fieldErrors[2].field").value("name"))
                .andExpect(jsonPath("$.fieldErrors[3].field").value("status"));
    }

    @Test
    void shouldRejectAgeBelowMinimum() throws Exception {
        String body = """
                {"name": "Asha Verma", "email": "asha.verma@example.com", "age": 17, "status": "ACTIVE"}
                """;

        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("age"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Age must be at least 18"));
    }

    @Test
    void shouldValidateBodyOnUpdateAsWell() throws Exception {
        String body = """
                {"name": "Asha Verma", "email": "asha.verma@example.com", "age": 30, "status": "PENDING"}
                """;

        mockMvc.perform(put("/api/users/1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("status"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Status must be ACTIVE or INACTIVE"));

        verifyNoInteractions(userService);
    }

    // ---- malformed input ----

    @Test
    void shouldRejectMalformedJson() throws Exception {
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Asha\","))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Malformed JSON request body"));
    }

    @Test
    void shouldRejectEmptyRequestBody() throws Exception {
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request body is missing"));
    }

    @Test
    void shouldRejectClientSuppliedId() throws Exception {
        String body = """
                {"id": 999, "name": "Asha Verma", "email": "asha.verma@example.com", "age": 30, "status": "ACTIVE"}
                """;

        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Unknown field 'id'"));
    }

    @Test
    void shouldRejectFractionalAgeInsteadOfTruncatingIt() throws Exception {
        String body = """
                {"name": "Asha Verma", "email": "asha.verma@example.com", "age": 17.9, "status": "ACTIVE"}
                """;

        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Invalid value for field 'age'"));
    }

    @Test
    void shouldRejectAgeSentAsString() throws Exception {
        String body = """
                {"name": "Asha Verma", "email": "asha.verma@example.com", "age": "30", "status": "ACTIVE"}
                """;

        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for field 'age'"));
    }

    @Test
    void shouldRejectJsonArrayAsBody() throws Exception {
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request body must be a JSON object"));
    }

    @Test
    void shouldRejectUnsupportedContentType() throws Exception {
        mockMvc.perform(post("/api/users").contentType(MediaType.TEXT_PLAIN).content("Asha Verma"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    // ---- ids and parameters ----

    @Test
    void shouldRejectNonNumericUserId() throws Exception {
        mockMvc.perform(get("/api/users/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ID"))
                .andExpect(jsonPath("$.path").value("/api/users/abc"));

        verifyNoInteractions(userService);
    }

    @Test
    void shouldRejectUserIdThatOverflowsLong() throws Exception {
        mockMvc.perform(get("/api/users/99999999999999999999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ID"));
    }

    @Test
    void shouldRejectSearchWithoutEmailParameter() throws Exception {
        mockMvc.perform(get("/api/users/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("Required query parameter 'email' is missing"));
    }

    @Test
    void shouldRejectUnsupportedHttpMethod() throws Exception {
        mockMvc.perform(patch("/api/users/1").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
    }

    // ---- service exceptions mapped to HTTP ----

    @Test
    void shouldReturnNotFoundWhenUserDoesNotExist() throws Exception {
        when(userService.getUserById(99L)).thenThrow(UserNotFoundException.forId(99L));

        mockMvc.perform(get("/api/users/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("User with id 99 was not found"))
                .andExpect(jsonPath("$.path").value("/api/users/99"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void shouldReturnConflictWhenEmailAlreadyExists() throws Exception {
        when(userService.createUser(any(UserRequest.class)))
                .thenThrow(new DuplicateEmailException("asha.verma@example.com"));

        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_EMAIL"));
    }

    @Test
    void shouldReturnInternalServerErrorWithoutLeakingDetails() throws Exception {
        when(userService.getUserById(1L))
                .thenThrow(new IllegalStateException("Connection refused: jdbc:h2:mem:usersdb"));

        mockMvc.perform(get("/api/users/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("jdbc"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    private static UserResponse sampleUser() {
        return new UserResponse(1L, "Asha Verma", "asha.verma@example.com", 30, UserStatus.ACTIVE,
                TIMESTAMP, TIMESTAMP);
    }
}
