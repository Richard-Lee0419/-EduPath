package com.edupath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {
    "edupath.review-access.enabled=true",
    "edupath.demo-mode.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:account_access_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountAccessContractTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void onlyAdminCanIssueAnInviteAndTeacherRegistrationConsumesIt() throws Exception {
        request("/api/admin/access/teacher-invites", Map.of(), login("demo"))
                .andExpect(status().isForbidden());
        String admin = login("admin");
        String email = address();
        JsonNode invite = data(request("/api/admin/access/teacher-invites",
                Map.of("email", email.toUpperCase(), "expires_in_hours", 2), admin));
        String code = invite.path("code").asText();

        register(address(), code).andExpect(status().isBadRequest());
        JsonNode created = data(register(email, code).andExpect(jsonPath("$.data.user.role").value("teacher")));
        mvc.perform(get("/api/classes").header("Authorization", bearer(created.path("token").asText())))
                .andExpect(status().isOk());
        register(address(), code).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM teacher_invites WHERE code_hash = ?", Integer.class, code)).isZero();
        request("/api/auth/register", Map.of("email", address(), "password", "reg-test", "role", "teacher"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.user.role").value("student"));
    }

    @Test
    void oneUseIsAtomicAndAnExpiredInviteCannotCreateATeacher() throws Exception {
        String admin = login("admin");
        String code = data(request("/api/admin/access/teacher-invites", Map.of(), admin)).path("code").asText();
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> attempt = () -> register(address(), code).andReturn().getResponse().getStatus();
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            assertThat(new int[] {first.get(), second.get()}).containsExactlyInAnyOrder(200, 400);
        } finally {
            executor.shutdownNow();
        }

        String expired = data(request("/api/admin/access/teacher-invites", Map.of("email", "expired@example.test"), admin))
                .path("code").asText();
        jdbc.update("UPDATE teacher_invites SET expires_at = ? WHERE email = 'expired@example.test'",
                Timestamp.from(Instant.now().minusSeconds(60)));
        register("expired@example.test", expired).andExpect(status().isBadRequest());
    }

    @Test
    void reviewLinkIssuesTwoBoundRolesAndRevokesExistingSessions() throws Exception {
        String admin = login("admin");
        JsonNode grant = data(request("/api/admin/access/review-links", Map.of("expires_in_hours", 1), admin));
        String code = grant.path("code").asText();
        long classId = grant.path("class_id").asLong();
        long linkId = grant.path("id").asLong();
        request("/api/auth/review", Map.of("code", code, "role", "admin"), null).andExpect(status().isBadRequest());
        request("/api/auth/review", Map.of("code", "invalid", "role", "teacher"), null).andExpect(status().isUnauthorized());

        JsonNode teacher = data(request("/api/auth/review", Map.of("code", code, "role", "teacher"), null)
                .andExpect(jsonPath("$.data.user.role").value("teacher"))
                .andExpect(jsonPath("$.data.user.review").value(true))
                .andExpect(jsonPath("$.data.refresh_token").doesNotExist()));
        JsonNode student = data(request("/api/auth/review", Map.of("code", code, "role", "student"), null)
                .andExpect(jsonPath("$.data.user.role").value("student")));
        String teacherToken = teacher.path("token").asText();
        String studentToken = student.path("token").asText();
        assertThat(OffsetDateTime.parse(teacher.path("expires_at").asText()).toInstant())
                .isBeforeOrEqualTo(Instant.parse(grant.path("expires_at").asText()));

        mvc.perform(get("/api/classes/{id}/members", classId).header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].user_id").value(student.path("user").path("id").asInt()));
        mvc.perform(get("/api/classes/{id}/members", classId).header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());
        request("/api/classes/" + classId + "/members", Map.of("username", "demo", "role", "student"), teacherToken)
                .andExpect(status().isForbidden());
        request("/api/auth/verify", Map.of(), studentToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.review").value(true));
        request("/api/admin/access/review-links", Map.of(), teacherToken).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/access/review-links").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.links[0].code").doesNotExist());

        mvc.perform(delete("/api/admin/access/review-links/{id}", linkId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        request("/api/auth/verify", Map.of(), teacherToken).andExpect(status().isUnauthorized());
        request("/api/auth/verify", Map.of(), studentToken).andExpect(status().isUnauthorized());
        request("/api/auth/review", Map.of("code", code, "role", "teacher"), null).andExpect(status().isUnauthorized());
    }

    @Test
    void expirationAlsoInvalidatesPreviouslyIssuedReviewerTokens() throws Exception {
        JsonNode grant = data(request("/api/admin/access/review-links", Map.of(), login("admin")));
        String code = grant.path("code").asText();
        String token = data(request("/api/auth/review", Map.of("code", code, "role", "student"), null)).path("token").asText();
        jdbc.update("UPDATE review_links SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), grant.path("id").asLong());
        request("/api/auth/verify", Map.of(), token).andExpect(status().isUnauthorized());
        request("/api/auth/review", Map.of("code", code, "role", "student"), null).andExpect(status().isUnauthorized());
    }

    private ResultActions register(String email, String code) throws Exception {
        return request("/api/auth/register", Map.of("email", email, "password", "reg-test", "teacher_invite_code", code), null);
    }

    private String login(String username) throws Exception {
        return data(request("/api/auth/login", Map.of("username", username, "password", "123456"), null)).path("token").asText();
    }

    private ResultActions request(String path, Map<String, ?> body, String token) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        if (token != null) request.header("Authorization", bearer(token));
        return mvc.perform(request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
    }

    private String address() {
        return UUID.randomUUID() + "@example.test";
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
