package com.edupath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {"edupath.demo-task-delay-millis=0", "edupath.ai-service-base-url=http://127.0.0.1:9"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeacherAssignmentContractTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void teacherCanPublishAssignmentAndStudentCompletionReturnsToProgress() throws Exception {
        String teacherToken = loginToken("teacher");
        String demoToken = loginToken("demo");
        long classId = jdbcTemplate.queryForObject(
                "SELECT id FROM classes WHERE name = '测试1班'", Long.class);
        String title = "闭环验收-" + UUID.randomUUID();

        MvcResult draft = mockMvc.perform(post("/api/classes/{classId}/assignments", classId)
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"course_id\":1,\"title\":\"" + title + "\",\"instructions\":\"完成一次诊断练习\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("draft"))
                .andReturn();
        Number assignmentIdValue = JsonPath.read(draft.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.id");
        long assignmentId = assignmentIdValue.longValue();

        mockMvc.perform(get("/api/assignments").header("Authorization", bearer(demoToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + assignmentId + ")]").doesNotExist());

        mockMvc.perform(post("/api/classes/{classId}/assignments/{assignmentId}/publish", classId, assignmentId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("published"))
                .andExpect(jsonPath("$.data.assigned_count").value(1));

        mockMvc.perform(get("/api/assignments").header("Authorization", bearer(demoToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + assignmentId + ")].target_status").value("assigned"));

        mockMvc.perform(post("/api/assignments/{assignmentId}/complete", assignmentId)
                        .header("Authorization", bearer(demoToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":88}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.target_status").value("completed"))
                .andExpect(jsonPath("$.data.score").value(88.0));

        mockMvc.perform(get("/api/classes/{classId}/assignments/{assignmentId}/progress", classId, assignmentId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].username").value("demo"))
                .andExpect(jsonPath("$.data[0].status").value("completed"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM teacher_assignment_targets WHERE assignment_id = ?", Integer.class, assignmentId))
                .isEqualTo(1);
    }

    private String loginToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.token");
    }

    private String bearer(String token) { return "Bearer " + token; }
}
