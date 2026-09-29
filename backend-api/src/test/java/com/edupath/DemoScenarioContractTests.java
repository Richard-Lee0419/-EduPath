package com.edupath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"edupath.demo-task-delay-millis=0", "edupath.demo-mode.enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DemoScenarioContractTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void preparationIsManagerOnlyIdempotentAndBuildsCompleteShowcaseData() throws Exception {
        String studentToken = loginToken("demo");
        String teacherToken = loginToken("teacher");

        mockMvc.perform(get("/api/demo/status").header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/demo/prepare")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario_key\":\"software_cup_a3\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/demo/preflight").header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/demo/status").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.scenario_key").value("software_cup_a3"))
                .andExpect(jsonPath("$.data.steps").isArray());

        MvcResult first = mockMvc.perform(post("/api/demo/prepare")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario_key\":\"software_cup_a3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ready"))
                .andExpect(jsonPath("$.data.reset_existing").value(true))
                .andExpect(jsonPath("$.data.summary.ready").value(true))
                .andExpect(jsonPath("$.data.summary.resource_count").value(6))
                .andExpect(jsonPath("$.data.summary.learning_event_count").value(8))
                .andExpect(jsonPath("$.data.summary.mastery_point_count").value(3))
                .andExpect(jsonPath("$.data.next_action").value("logout_and_login_as_demo_student"))
                .andReturn();
        String firstRunId = JsonPath.read(
                first.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.run_id");

        MvcResult second = mockMvc.perform(post("/api/demo/prepare")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.resource_count").value(6))
                .andExpect(jsonPath("$.data.summary.profile_version_count").value(1))
                .andExpect(jsonPath("$.data.summary.learning_path_count").value(1))
                .andExpect(jsonPath("$.data.summary.quiz_attempt_count").value(1))
                .andReturn();
        String secondRunId = JsonPath.read(
                second.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.run_id");
        assertThat(secondRunId).isNotEqualTo(firstRunId);

        mockMvc.perform(get("/api/demo/status").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.latest_run.run_id").value(secondRunId));
        mockMvc.perform(get("/api/demo/preflight").header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overall_status").value("degraded"))
                .andExpect(jsonPath("$.data.recommended_mode").value("prepared_data_only"))
                .andExpect(jsonPath("$.data.prepared_demo_available").value(true))
                .andExpect(jsonPath("$.data.live_ai_available").value(false))
                .andExpect(jsonPath("$.data.checks[?(@.key == 'database')].status").value("ready"))
                .andExpect(jsonPath("$.data.checks[?(@.key == 'demo_data')].status").value("ready"));

        mockMvc.perform(get("/api/profile/current").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.learning_goal").exists())
                .andExpect(jsonPath("$.data.profile.resource_preference").isArray());
        mockMvc.perform(get("/api/path/current").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.daily_plan.length()").value(7))
                .andExpect(jsonPath("$.data.replan_trigger").value("quiz_evaluation"));
        mockMvc.perform(get("/api/evaluation/report").header("Authorization", bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overall_score").value(67))
                .andExpect(jsonPath("$.data.weak_points[0]").value("Cache 映射方式"));
        mockMvc.perform(get("/api/resources")
                        .header("Authorization", bearer(studentToken))
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.resource_id == 'demo_showcase_lecture')]").isNotEmpty())
                .andExpect(jsonPath("$.data.items[?(@.resource_id == 'demo_showcase_animation')]").isNotEmpty());

        Integer managedResources = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resources WHERE source_task_id = 'demo_prepare'", Integer.class);
        Integer managedEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM learning_events WHERE idempotency_key LIKE 'demo_prepare:%'", Integer.class);
        Integer originalSeedResource = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resources WHERE resource_id = 'res_001'", Integer.class);
        assertThat(managedResources).isEqualTo(6);
        assertThat(managedEvents).isEqualTo(8);
        assertThat(originalSeedResource).isEqualTo(1);
    }

    private String loginToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.token");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
