package com.example.apiverification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.example.apiverification.repository.UserRepository;

/**
 * End-to-end tests through the full stack: HTTP -> controller -> service -> JPA -> H2.
 * These cover behaviour that only exists when the real pieces are wired together
 * (route matching, the email uniqueness queries, timestamps persisted and read back).
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
    }

    @Test
    void shouldCreateReadUpdateSearchAndDeleteUser() throws Exception {
        long id = createUser("Asha Verma", "Asha.Verma@Example.com", 30, "ACTIVE");

        mockMvc.perform(get("/api/users/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("asha.verma@example.com"));

        mockMvc.perform(put("/api/users/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("Asha Rao", "asha.rao@example.com", 31, "INACTIVE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Asha Rao"))
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        mockMvc.perform(get("/api/users/search").param("email", "ASHA.RAO@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        mockMvc.perform(delete("/api/users/{id}", id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    @Test
    void shouldReturnAllUsersOrderedById() throws Exception {
        long first = createUser("Asha Verma", "asha.verma@example.com", 30, "ACTIVE");
        long second = createUser("Ravi Kulkarni", "ravi.kulkarni@example.com", 45, "INACTIVE");

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[1].id").value(second));
    }

    @Test
    void shouldReturnEmptyListWhenNoUsersExist() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void shouldRejectDuplicateEmailRegardlessOfCase() throws Exception {
        createUser("Asha Verma", "asha.verma@example.com", 30, "ACTIVE");

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("Another Asha", "ASHA.VERMA@EXAMPLE.COM", 40, "ACTIVE")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_EMAIL"));

        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldAllowUpdateWhenEmailIsUnchanged() throws Exception {
        long id = createUser("Asha Verma", "asha.verma@example.com", 30, "ACTIVE");

        mockMvc.perform(put("/api/users/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("Asha Verma", "asha.verma@example.com", 32, "ACTIVE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.age").value(32));
    }

    @Test
    void shouldRejectUpdateWhenEmailBelongsToAnotherUser() throws Exception {
        long asha = createUser("Asha Verma", "asha.verma@example.com", 30, "ACTIVE");
        createUser("Ravi Kulkarni", "ravi.kulkarni@example.com", 45, "ACTIVE");

        mockMvc.perform(put("/api/users/{id}", asha)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("Asha Verma", "ravi.kulkarni@example.com", 30, "ACTIVE")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_EMAIL"));

        mockMvc.perform(get("/api/users/{id}", asha))
                .andExpect(jsonPath("$.email").value("asha.verma@example.com"));
    }

    @Test
    void shouldKeepCreatedAtUnchangedAfterUpdate() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("Asha Verma", "asha.verma@example.com", 30, "ACTIVE")))
                .andExpect(status().isCreated())
                .andReturn();
        String body = created.getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();
        String createdAt = JsonPath.read(body, "$.createdAt");

        mockMvc.perform(put("/api/users/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("Asha Verma", "asha.verma@example.com", 31, "ACTIVE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAt").value(createdAt));
    }

    @Test
    void shouldRejectNegativeUserId() throws Exception {
        mockMvc.perform(get("/api/users/-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ID"))
                .andExpect(jsonPath("$.message").value("User id must be a positive number but was -1"));
    }

    @Test
    void shouldRejectZeroUserId() throws Exception {
        mockMvc.perform(delete("/api/users/0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ID"));
    }

    @Test
    void shouldReturnNotFoundWhenUserDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/users/987654"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("User with id 987654 was not found"));
    }

    @Test
    void shouldReturnNotFoundWhenDeletingNonExistentUser() throws Exception {
        mockMvc.perform(delete("/api/users/987654"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnNotFoundWhenSearchedEmailDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/users/search").param("email", "nobody@example.com"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    @Test
    void shouldRejectBlankSearchEmail() throws Exception {
        mockMvc.perform(get("/api/users/search").param("email", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
    }

    @Test
    void shouldReturnNotFoundForUnknownEndpoint() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));
    }

    private long createUser(String name, String email, int age, String status) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson(name, email, age, status)))
                .andExpect(status().isCreated())
                .andReturn();
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    private static String userJson(String name, String email, int age, String status) {
        return """
                {"name": "%s", "email": "%s", "age": %d, "status": "%s"}
                """.formatted(name, email, age, status);
    }
}
