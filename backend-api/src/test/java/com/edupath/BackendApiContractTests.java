package com.edupath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edupath.ai.AiAgentClient;
import com.edupath.ai.AiAgentClient.AiEvidence;
import com.edupath.ai.AiAgentClient.AiGeneratedResource;
import com.edupath.ai.AiAgentClient.AiResourceGenerateResult;
import com.edupath.ai.AiAgentClient.AiSafety;
import com.edupath.ai.AiAgentClient.KnowledgeChunk;
import com.edupath.ai.AiAgentClient.KnowledgeDocumentPayload;
import com.edupath.ai.AiAgentClient.KnowledgeIngestResult;
import com.edupath.common.ExternalServiceException;
import com.edupath.kb.KnowledgeBaseService.KnowledgeSearchRequest;
import com.edupath.resource.ResourceTaskRequest;
import com.edupath.storage.ObjectStorageService;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
        properties = {
            "edupath.demo-task-delay-millis=0",
            "edupath.ai-service-base-url=http://127.0.0.1:9",
            "edupath.storage.local-root=target/test-storage",
            "edupath.storage.lifecycle-days=1",
            "edupath.kb.max-upload-bytes=64"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendApiContractTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StubAiAgentClient aiAgentClient;

    @Autowired
    private ObjectStorageService objectStorageService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetAiStub() {
        aiAgentClient.failResourceGeneration = false;
        aiAgentClient.simulateQualityRegression = false;
        aiAgentClient.lastIngestPayload = null;
        aiAgentClient.lastSearchRequest = null;
    }

    @Test
    void knowledgeSearchReturnsRealCorpusStatusAndTraceableEvidence() throws Exception {
        String token = loginToken();

        mockMvc.perform(post("/api/kb/search")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "course_id": 2,
                                  "query": "组相联 Cache 如何计算组号",
                                  "top_k": 3
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.course_id").value(2))
                .andExpect(jsonPath("$.data.query").value("组相联 Cache 如何计算组号"))
                .andExpect(jsonPath("$.data.corpus.mode").value("course_corpus"))
                .andExpect(jsonPath("$.data.corpus.external_documents").value(58))
                .andExpect(jsonPath("$.data.results[0].knowledge_point_id").value(251))
                .andExpect(jsonPath("$.data.results[0].knowledge_point").value("Cache 映射方式"))
                .andExpect(jsonPath("$.data.results[0].source").value("courseware/cache-mapping.pdf"))
                .andExpect(jsonPath("$.data.results[0].license_status").value("pending"))
                .andExpect(jsonPath("$.data.results[0].contains_examples").value(true));

        assertThat(aiAgentClient.lastSearchRequest.courseId()).isEqualTo(2);
        assertThat(aiAgentClient.lastSearchRequest.topK()).isEqualTo(3);
    }

    @Test
    void publicApiSurfaceReturnsDemoData() throws Exception {
        String token = loginToken();
        mockMvc.perform(get("/api/courses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].code").value("data_structures_algorithms"));

        mockMvc.perform(get("/api/resources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].resource_id").exists())
                .andExpect(jsonPath("$.data.items[0].safety").doesNotExist());

        MvcResult quizTask = mockMvc.perform(post("/api/quiz/generate")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "course_id": 1,
                                  "knowledge_point_ids": [131],
                                  "difficulty": "basic",
                                  "question_count": 3
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_id").exists())
                .andReturn();
        String quizTaskJson = waitForTaskSuccess(
                JsonPath.read(quizTask.getResponse().getContentAsString(), "$.data.task_id"), token);
        assertThat(JsonPath.<Number>read(quizTaskJson, "$.data.result.quiz_id").longValue()).isPositive();
        assertThat(JsonPath.<List<?>>read(quizTaskJson, "$.data.result.questions")).hasSize(3);
    }

    @Test
    void quizSubmitAcceptsGeneratedOptionTextAnswers() throws Exception {
        String token = loginToken();
        MvcResult quiz = mockMvc.perform(post("/api/quiz/generate")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "course_id": 1,
                                  "knowledge_point_ids": [131],
                                  "difficulty": "basic",
                                  "question_count": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String quizTaskJson = waitForTaskSuccess(
                JsonPath.read(quiz.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.task_id"), token);
        Number quizId = JsonPath.read(quizTaskJson, "$.data.result.quiz_id");
        Number questionId = JsonPath.read(quizTaskJson, "$.data.result.questions[0].question_id");
        String firstOption = JsonPath.read(quizTaskJson, "$.data.result.questions[0].options[0]");
        String submitPayload = """
                {
                  "quiz_id": %d,
                  "answers": [
                    {"question_id": %d, "answer": "%s"}
                  ]
                }
                """.formatted(quizId.longValue(), questionId.longValue(), firstOption);

        MvcResult submitTask = mockMvc.perform(post("/api/quiz/submit")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8.name())
                        .content(submitPayload.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_id").exists())
                .andReturn();
        String submitTaskJson = waitForTaskSuccess(
                JsonPath.read(submitTask.getResponse().getContentAsString(), "$.data.task_id"), token);
        assertThat(JsonPath.<Integer>read(submitTaskJson, "$.data.result.score")).isEqualTo(100);
        assertThat(JsonPath.<Boolean>read(submitTaskJson, "$.data.result.profile_update.updated")).isTrue();
        assertThat(JsonPath.<Boolean>read(submitTaskJson, "$.data.result.path_update.updated")).isTrue();
        assertThat(JsonPath.<Integer>read(submitTaskJson, "$.data.result.path_update.version")).isEqualTo(1);
    }

    @Test
    void knowledgeUploadReservesObjectStorageKey() throws Exception {
        String token = loginToken("teacher");
        MvcResult result = mockMvc.perform(multipart("/api/kb/upload")
                        .file("file", "二叉树讲义".getBytes(StandardCharsets.UTF_8))
                        .header("Authorization", bearer(token))
                        .param("courseId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.document_id").exists())
                .andExpect(jsonPath("$.data.parse_status").value("parsed"))
                .andExpect(jsonPath("$.data.index_status").value("indexed"))
                .andExpect(jsonPath("$.data.storage_provider").value("local"))
                .andExpect(jsonPath("$.data.object_key").exists())
                .andExpect(jsonPath("$.data.storage_status").value("stored"))
                .andExpect(jsonPath("$.data.signed_download_url.url").exists())
                .andReturn();
        String objectKey = JsonPath.read(result.getResponse().getContentAsString(), "$.data.object_key");
        String signedUrl = JsonPath.read(result.getResponse().getContentAsString(), "$.data.signed_download_url.url");
        Number documentId = JsonPath.read(result.getResponse().getContentAsString(), "$.data.document_id");
        assertThat(Files.exists(Path.of("target/test-storage").resolve(objectKey))).isTrue();
        assertThat(aiAgentClient.lastIngestPayload).isNotNull();
        assertThat(aiAgentClient.lastIngestPayload.content()).contains("二叉树讲义");

        mockMvc.perform(get(signedUrl))
                .andExpect(status().isOk())
                .andExpect(resultMatcher ->
                        assertThat(resultMatcher.getResponse().getContentAsByteArray())
                                .isEqualTo("二叉树讲义".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/api/kb/documents/{documentId}/download-url", documentId.longValue())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.startsWith("/api/storage/signed")));

        mockMvc.perform(multipart("/api/kb/upload")
                        .file(new MockMultipartFile(
                                "file",
                                "recursion-slides.pptx",
                                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                "PPTX 课件文本".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(token))
                        .param("courseId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parse_status").value("parsed"))
                .andExpect(jsonPath("$.data.index_status").value("indexed"));
    }

    @Test
    void knowledgeUploadRejectsUnsafeOrUnsupportedFiles() throws Exception {
        String token = loginToken("teacher");

        mockMvc.perform(multipart("/api/kb/upload")
                        .file(new MockMultipartFile("file", "empty.md", "text/markdown", new byte[0]))
                        .header("Authorization", bearer(token))
                        .param("courseId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("空文件")));

        mockMvc.perform(multipart("/api/kb/upload")
                        .file(new MockMultipartFile(
                                "file",
                                "too-large.md",
                                "text/markdown",
                                "超过上传大小限制的课程讲义内容，需要被拒绝，避免生产环境内存压力。".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(token))
                        .param("courseId", "1"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("文件大小超过限制")));

        mockMvc.perform(multipart("/api/kb/upload")
                        .file(new MockMultipartFile("file", "malware.exe", "application/octet-stream", "binary".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(token))
                        .param("courseId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不支持的文件类型")));

        MvcResult result = mockMvc.perform(multipart("/api/kb/upload")
                        .file(new MockMultipartFile(
                                "file",
                                "../nested/evil.md",
                                "text/markdown",
                                "路径清洗测试".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(token))
                        .param("courseId", "1"))
                .andExpect(status().isOk())
                .andReturn();
        String objectKey = JsonPath.read(result.getResponse().getContentAsString(), "$.data.object_key");
        assertThat(objectKey).doesNotContain("..");
        assertThat(objectKey).doesNotContain("nested/evil");
    }

    @Test
    void localObjectLifecycleDeletesExpiredObjects() throws Exception {
        ObjectStorageService.StoredObject storedObject = objectStorageService.store(
                "lifecycle",
                "old-resource.md",
                "text/markdown",
                "stale".getBytes(StandardCharsets.UTF_8));
        Path path = objectStorageService.localPathForTesting(storedObject.objectKey());
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(2, ChronoUnit.DAYS)));

        assertThat(objectStorageService.sweepExpiredObjects()).isGreaterThanOrEqualTo(1);
        assertThat(Files.exists(path)).isFalse();
    }

    @Test
    void managerEndpointsRequireTeacherOrAdminRole() throws Exception {
        String studentToken = loginToken();
        String teacherToken = loginToken("teacher");

        mockMvc.perform(multipart("/api/kb/upload")
                        .file("file", "学生上传".getBytes(StandardCharsets.UTF_8))
                        .header("Authorization", bearer(studentToken))
                        .param("courseId", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40403));

        mockMvc.perform(patch("/api/resources/res_001/status")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"archived\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40403));

        mockMvc.perform(patch("/api/resources/res_001/status")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"draft\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("draft"));
    }

    @Test
    void authSupportsRefreshLogoutAndAdminUserManagement() throws Exception {
        String adminToken = loginToken("admin");

        MvcResult created = mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "username": "alice",
                                  "password": "123456",
                                  "role": "student"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.role").value("student"))
                .andReturn();
        Number aliceId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", bearer(adminToken))
                        .param("keyword", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].username").value("alice"));

        mockMvc.perform(patch("/api/admin/users/{id}/status", aliceId.longValue())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"disabled\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("disabled"));

        mockMvc.perform(patch("/api/admin/users/{id}/status", aliceId.longValue())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"active\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("active"));

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").exists())
                .andExpect(jsonPath("$.data.refresh_token").exists())
                .andReturn();
        String aliceToken = JsonPath.read(login.getResponse().getContentAsString(), "$.data.token");
        String refreshToken = JsonPath.read(login.getResponse().getContentAsString(), "$.data.refresh_token");

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").exists())
                .andExpect(jsonPath("$.data.refresh_token").exists());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/verify").header("Authorization", bearer(aliceToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicRegistrationCreatesStudentAndReturnsLoginTokens() throws Exception {
        String username = "student_" + System.nanoTime();
        String email = username + "@example.com";

        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "email": "%s",
                                  "password": "123456"
                                }
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").exists())
                .andExpect(jsonPath("$.data.refresh_token").exists())
                .andExpect(jsonPath("$.data.user.username").value(username))
                .andExpect(jsonPath("$.data.user.email").value(email))
                .andExpect(jsonPath("$.data.user.role").value("student"))
                .andExpect(jsonPath("$.data.email_delivery.sent").value(false))
                .andReturn();
        String token = JsonPath.read(registered.getResponse().getContentAsString(), "$.data.token");

        mockMvc.perform(post("/api/auth/verify").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.role").value("student"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + email + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.email").value(email));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("邮箱已注册")));
    }

    @Test
    void newlyRegisteredStudentStartsWithoutDemoLearningData() throws Exception {
        String username = "fresh_" + System.nanoTime();
        String email = username + "@example.com";
        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = JsonPath.read(registered.getResponse().getContentAsString(), "$.data.token");
        Number userId = JsonPath.read(registered.getResponse().getContentAsString(), "$.data.user.id");

        Integer demoClassMembership = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM class_members cm
                JOIN classes c ON c.id = cm.class_id
                WHERE c.name = '测试2班' AND cm.user_id = ?
                  AND cm.role = 'student' AND cm.status = 'active'
                """,
                Integer.class,
                userId.longValue());
        assertThat(demoClassMembership).isEqualTo(1);

        mockMvc.perform(get("/api/profile/current").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.profile.student_id").value(username))
                .andExpect(jsonPath("$.data.profile.weak_points").isEmpty());

        mockMvc.perform(get("/api/path/current").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.student_id").value(username))
                .andExpect(jsonPath("$.data.path_id").value(""))
                .andExpect(jsonPath("$.data.daily_plan").isEmpty())
                .andExpect(jsonPath("$.data.adjustment_signal.path_available").value(false))
                .andExpect(jsonPath("$.data.adjustment_signal.status").value("stable"));

        mockMvc.perform(get("/api/path/adjustment-signal").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.path_available").value(false))
                .andExpect(jsonPath("$.data.should_replan").value(false));

        mockMvc.perform(get("/api/evaluation/report").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.student_id").value(username))
                .andExpect(jsonPath("$.data.report_id").value(""))
                .andExpect(jsonPath("$.data.overall_score").value(0))
                .andExpect(jsonPath("$.data.mastery").isEmpty());

        mockMvc.perform(get("/api/resources").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void behaviorSignalCreatesControlledReplanTaskAndPersistsNextPathVersion() throws Exception {
        String username = "behavior_" + System.nanoTime();
        String adminToken = loginToken("admin");
        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\",\"role\":\"student\"}"))
                .andExpect(status().isOk());
        String token = loginToken(username);

        MvcResult generated = mockMvc.perform(post("/api/path/generate")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"course_ids\":[1],\"target\":\"掌握二叉树递归遍历\",\"days\":3,\"daily_minutes\":40}"))
                .andExpect(status().isOk())
                .andReturn();
        waitForTaskSuccess(JsonPath.read(
                generated.getResponse().getContentAsString(), "$.data.task_id"), token);

        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, username);
        Timestamp threeDaysAgo = Timestamp.from(Instant.now().minus(3, ChronoUnit.DAYS));
        Timestamp sevenHoursAgo = Timestamp.from(Instant.now().minus(7, ChronoUnit.HOURS));
        jdbcTemplate.update(
                "UPDATE learning_paths SET created_at = ?, updated_at = ? WHERE student_id = ?",
                threeDaysAgo,
                sevenHoursAgo,
                username);
        for (int index = 1; index <= 3; index++) {
            String key = username + "-low-" + index;
            jdbcTemplate.update(
                    """
                    INSERT INTO learning_events (
                        event_id, idempotency_key, student_id, user_id, event_type, course_id,
                        knowledge_point, source_type, source_id, action, progress_percent,
                        sequence_no, evidence_weight, evidence_score, metadata, occurred_at
                    ) VALUES (?, ?, ?, ?, 'resource_completion', 1, '二叉树递归遍历',
                              'resource', ?, 'complete', 100, ?, 2, 50, '{}', ?)
                    """,
                    "event-" + key,
                    key,
                    username,
                    userId,
                    "resource-" + index,
                    index,
                    Timestamp.from(Instant.now().minus(index, ChronoUnit.MINUTES)));
        }

        mockMvc.perform(get("/api/path/adjustment-signal").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("replan_recommended"))
                .andExpect(jsonPath("$.data.should_replan").value(true));

        MvcResult created = mockMvc.perform(post("/api/path/replan/behavior")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reused").value(false))
                .andExpect(jsonPath("$.data.trigger_key").value(org.hamcrest.Matchers.startsWith("behavior_replan:")))
                .andReturn();
        String taskId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.task_id");
        String taskJson = waitForTaskSuccess(taskId, token);
        assertThat(JsonPath.<String>read(taskJson, "$.data.result.trigger")).isEqualTo("behavior_signal");
        assertThat(JsonPath.<Integer>read(taskJson, "$.data.result.version")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(taskJson, "$.data.result.previous_version")).isEqualTo(1);
        assertThat(JsonPath.<String>read(taskJson, "$.data.result.behavior_trigger_key"))
                .startsWith("behavior_replan:");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM learning_paths WHERE student_id = ?", Integer.class, username))
                .isEqualTo(2);
    }

    @Test
    void studentOwnedDataIsIsolatedAndOperationalEndpointsAreQueryable() throws Exception {
        String adminToken = loginToken("admin");
        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"123456\",\"role\":\"student\"}"))
                .andExpect(status().isOk());
        String bobToken = loginToken("bob");

        mockMvc.perform(get("/api/profile/current")
                        .header("Authorization", bearer(bobToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.student_id").value("bob"));

        MvcResult quiz = mockMvc.perform(post("/api/quiz/generate")
                        .header("Authorization", bearer(bobToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"course_id\":1,\"knowledge_point_ids\":[131],\"question_count\":1}"))
                .andExpect(status().isOk())
                .andReturn();
        String quizTaskJson = waitForTaskSuccess(
                JsonPath.read(quiz.getResponse().getContentAsString(), "$.data.task_id"), bobToken);
        Number quizId = JsonPath.read(quizTaskJson, "$.data.result.quiz_id");
        Number questionId = JsonPath.read(quizTaskJson, "$.data.result.questions[0].question_id");
        MvcResult submitTask = mockMvc.perform(post("/api/quiz/submit")
                        .header("Authorization", bearer(bobToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quiz_id\":" + quizId + ",\"answers\":[{\"question_id\":" + questionId + ",\"answer\":\"B\"}]}"))
                .andExpect(status().isOk())
                .andReturn();
        String submitTaskJson = waitForTaskSuccess(
                JsonPath.read(submitTask.getResponse().getContentAsString(), "$.data.task_id"), bobToken);
        assertThat(JsonPath.<Integer>read(submitTaskJson, "$.data.result.score")).isZero();
        assertThat(JsonPath.<String>read(submitTaskJson, "$.data.steps[0].agent")).isEqualTo("ScoringEngine");
        assertThat(JsonPath.<String>read(submitTaskJson, "$.data.steps[-1].agent")).isEqualTo("PathAgent");
        assertThat(JsonPath.<String>read(submitTaskJson, "$.data.result.profile_update.source_task_id"))
                .isEqualTo("ai_evaluation_001");
        assertThat(JsonPath.<String>read(submitTaskJson, "$.data.result.path_update.trigger"))
                .isEqualTo("quiz_evaluation");
        assertThat(JsonPath.<String>read(submitTaskJson, "$.data.result.path_update.source_evaluation_task_id"))
                .isEqualTo("ai_evaluation_001");
        mockMvc.perform(get("/api/quiz/history").header("Authorization", bearer(bobToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].student_id").value("bob"));
        mockMvc.perform(get("/api/quiz/wrong-book").header("Authorization", bearer(bobToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].student_id").value("bob"));
        mockMvc.perform(get("/api/profile/current").header("Authorization", bearer(bobToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated_reason").value(org.hamcrest.Matchers.containsString("EvaluationAgent")))
                .andExpect(jsonPath("$.data.source_task_id").value("ai_evaluation_001"))
                .andExpect(jsonPath("$.data.profile.weak_points").value(org.hamcrest.Matchers.hasItem("二叉树递归遍历")));

        MvcResult profileTask = mockMvc.perform(post("/api/profile/chat")
                        .header("Authorization", bearer(bobToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"递归调用栈还不熟\",\"course_ids\":[1]}"))
                .andExpect(status().isOk())
                .andReturn();
        String profileTaskJson = waitForTaskSuccess(
                JsonPath.read(profileTask.getResponse().getContentAsString(), "$.data.task_id"), bobToken);
        assertThat(JsonPath.<String>read(profileTaskJson, "$.data.result.generation_mode"))
                .isEqualTo("real_model");
        assertThat(JsonPath.<Integer>read(profileTaskJson, "$.data.result.model_runtime.call_count"))
                .isEqualTo(2);
        assertThat(JsonPath.<Boolean>read(profileTaskJson, "$.data.result.safety.passed"))
                .isTrue();
        mockMvc.perform(get("/api/profile/current").header("Authorization", bearer(bobToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.student_id").value("bob"))
                .andExpect(jsonPath("$.data.profile.latest_quiz_score").isNumber())
                .andExpect(jsonPath("$.data.profile.weak_points").value(org.hamcrest.Matchers.hasItems(
                        "二叉树递归遍历", "递归调用栈")));
        mockMvc.perform(get("/api/evaluation/report").header("Authorization", bearer(bobToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quiz_id").value(quizId.longValue()))
                .andExpect(jsonPath("$.data.overall_score").value(0))
                .andExpect(jsonPath("$.data.mastery[0].knowledge_point").value("二叉树递归遍历"))
                .andExpect(jsonPath("$.data.next_actions[0]").value(org.hamcrest.Matchers.containsString("补救")));

        MvcResult pathTask = mockMvc.perform(post("/api/path/generate")
                        .header("Authorization", bearer(bobToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"course_ids\":[1],\"target\":\"掌握二叉树\",\"days\":3,\"daily_minutes\":30}"))
                .andExpect(status().isOk())
                .andReturn();
        String pathTaskJson = waitForTaskSuccess(
                JsonPath.read(pathTask.getResponse().getContentAsString(), "$.data.task_id"), bobToken);
        assertThat(JsonPath.<String>read(pathTaskJson, "$.data.result.generation_mode"))
                .isEqualTo("real_model");
        assertThat(JsonPath.<Integer>read(pathTaskJson, "$.data.result.model_runtime.call_count"))
                .isEqualTo(2);
        assertThat(JsonPath.<Boolean>read(pathTaskJson, "$.data.result.safety.passed"))
                .isTrue();
        mockMvc.perform(get("/api/path/current").header("Authorization", bearer(bobToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.student_id").value("bob"))
                .andExpect(jsonPath("$.data.completed_days").isEmpty())
                .andExpect(jsonPath("$.data.adjustment_signal.path_available").value(true))
                .andExpect(jsonPath("$.data.adjustment_signal.risk_score").isNumber())
                .andExpect(jsonPath("$.data.recommended_resources[*].reason")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("二叉树递归遍历"))));

        mockMvc.perform(post("/api/path/nodes/1/complete").header("Authorization", bearer(bobToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completed_days[0]").value(1))
                .andExpect(jsonPath("$.data.learning_update.recorded_events").value(1))
                .andExpect(jsonPath("$.data.learning_update.mastery_updates[0].event_type")
                        .value("path_node_completion"));
        mockMvc.perform(post("/api/path/nodes/1/complete").header("Authorization", bearer(bobToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.learning_update.recorded_events").value(0))
                .andExpect(jsonPath("$.data.learning_update.duplicate_events").value(1));

        MvcResult tutorTask = mockMvc.perform(post("/api/tutor/chat")
                        .header("Authorization", bearer(bobToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"course_id\":1,\"question\":\"什么是二叉树递归遍历？\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String tutorTaskJson = waitForTaskSuccess(
                JsonPath.read(tutorTask.getResponse().getContentAsString(), "$.data.task_id"), bobToken);
        assertThat(JsonPath.<String>read(tutorTaskJson, "$.data.result.generation_mode"))
                .isEqualTo("real_model");
        assertThat(JsonPath.<Integer>read(tutorTaskJson, "$.data.result.model_runtime.call_count"))
                .isEqualTo(2);
        assertThat(JsonPath.<Boolean>read(tutorTaskJson, "$.data.result.safety.passed"))
                .isTrue();
        assertThat(JsonPath.<String>read(tutorTaskJson, "$.data.result.citations[0].evidence_chunk_ids[0]"))
                .isEqualTo("chunk_1");
        assertThat(JsonPath.<Integer>read(tutorTaskJson, "$.data.result.learning_update.recorded_events"))
                .isEqualTo(1);
        assertThat(JsonPath.<List<?>>read(tutorTaskJson, "$.data.result.learning_update.mastery_updates"))
                .isEmpty();
        String tutorSessionId = JsonPath.read(tutorTaskJson, "$.data.result.session_id");
        MvcResult tutorFollowUpTask = mockMvc.perform(post("/api/tutor/chat")
                        .header("Authorization", bearer(bobToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"course_id\":1,\"question\":\"递归出口应该怎么判断？\",\"session_id\":\""
                                + tutorSessionId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String tutorFollowUpJson = waitForTaskSuccess(
                JsonPath.read(tutorFollowUpTask.getResponse().getContentAsString(), "$.data.task_id"), bobToken);
        assertThat(JsonPath.<String>read(tutorFollowUpJson, "$.data.result.session_id"))
                .isEqualTo(tutorSessionId);
        assertThat(JsonPath.<String>read(tutorFollowUpJson, "$.data.result.learning_update.mastery_updates[0].event_type"))
                .isEqualTo("tutor_follow_up");
        mockMvc.perform(get("/api/learning/events").header("Authorization", bearer(bobToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].event_type")
                        .value(org.hamcrest.Matchers.hasItems("path_node_completion", "tutor_question", "tutor_follow_up")));
        mockMvc.perform(get("/api/tutor/sessions").header("Authorization", bearer(bobToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].student_id").value("bob"));

        mockMvc.perform(get("/api/admin/audit-logs")
                        .header("Authorization", bearer(adminToken))
                        .param("actor", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void courseAndResourceOperationsArePersistedWithVersions() throws Exception {
        String adminToken = loginToken("admin");
        String teacherToken = loginToken("teacher");

        MvcResult course = mockMvc.perform(post("/api/courses")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"operating_systems\",\"name\":\"操作系统\",\"description\":\"进程、内存和文件系统\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").exists())
                .andReturn();
        Number courseId = JsonPath.read(course.getResponse().getContentAsString(), "$.data.id");
        mockMvc.perform(post("/api/courses/{courseId}/knowledge-points", courseId.longValue())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"进程调度\",\"difficulty\":\"medium\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("进程调度"));

        mockMvc.perform(patch("/api/resources/res_001")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新版二叉树讲义\",\"content\":\"## 更新后的内容\",\"tags\":[\"tree\",\"recursion\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("新版二叉树讲义"));
        mockMvc.perform(get("/api/resources/res_001/versions")
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].version").exists());
    }

    @Test
    void courseTeamClassAndResourceOwnerDimensionsAreEnforced() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String adminToken = loginToken("admin");

        MvcResult teacher = mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"team_teacher_" + suffix + "\",\"password\":\"123456\",\"role\":\"teacher\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Number teacherId = JsonPath.read(teacher.getResponse().getContentAsString(), "$.data.id");
        String teamTeacherToken = loginToken("team_teacher_" + suffix);

        MvcResult student = mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"team_student_" + suffix + "\",\"password\":\"123456\",\"role\":\"student\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Number studentId = JsonPath.read(student.getResponse().getContentAsString(), "$.data.id");
        String studentToken = loginToken("team_student_" + suffix);

        MvcResult course = mockMvc.perform(post("/api/courses")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"team_course_" + suffix + "\",\"name\":\"团队课程\",\"description\":\"团队权限测试\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Number courseId = JsonPath.read(course.getResponse().getContentAsString(), "$.data.id");

        mockMvc.perform(multipart("/api/kb/upload")
                        .file("file", "未授权上传".getBytes(StandardCharsets.UTF_8))
                        .header("Authorization", bearer(teamTeacherToken))
                        .param("courseId", String.valueOf(courseId.longValue())))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/courses/{courseId}/team-members", courseId.longValue())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":" + teacherId + ",\"role\":\"manager\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].username").value("team_teacher_" + suffix));

        mockMvc.perform(multipart("/api/kb/upload")
                        .file("file", "团队教师上传".getBytes(StandardCharsets.UTF_8))
                        .header("Authorization", bearer(teamTeacherToken))
                        .param("courseId", String.valueOf(courseId.longValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parse_status").value("parsed"));

        MvcResult classResult = mockMvc.perform(post("/api/classes")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A3 测试班 " + suffix + "\",\"course_id\":" + courseId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.courses[0].course_id").value(courseId.longValue()))
                .andReturn();
        Number classId = JsonPath.read(classResult.getResponse().getContentAsString(), "$.data.class_info.id");

        mockMvc.perform(post("/api/classes/{classId}/members", classId.longValue())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":" + studentId + ",\"role\":\"student\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].username").value("team_student_" + suffix));

        MvcResult resourceTask = mockMvc.perform(post("/api/agent/resource-task")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "course_id": %d,
                                  "knowledge_point_ids": [],
                                  "resource_types": ["lecture"],
                                  "difficulty": "basic",
                                  "goal": "生成 owner 资源"
                                }
                                """.formatted(courseId.longValue())))
                .andExpect(status().isOk())
                .andReturn();
        String taskId = JsonPath.read(resourceTask.getResponse().getContentAsString(), "$.data.task_id");
        String taskJson = waitForTaskSuccess(taskId, studentToken);
        String resourceId = JsonPath.read(taskJson, "$.data.result.resources[0].resource_id");

        mockMvc.perform(post("/api/resources/{resourceId}/interactions", resourceId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"complete\",\"rating\":5,\"progress_percent\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.progress_percent").value(100))
                .andExpect(jsonPath("$.data.rating").value(5))
                .andExpect(jsonPath("$.data.learning_update.recorded_events").value(1))
                .andExpect(jsonPath("$.data.learning_update.mastery_updates[0].event_type")
                        .value("resource_reading"));

        mockMvc.perform(post("/api/resources/{resourceId}/interactions", resourceId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"complete\",\"rating\":5,\"progress_percent\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.learning_update.recorded_events").value(0))
                .andExpect(jsonPath("$.data.learning_update.duplicate_events").value(1));

        mockMvc.perform(patch("/api/resources/{resourceId}/status", resourceId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"draft\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("draft"));
    }

    @Test
    void resourceTaskRunsMultiAgentWorkflowAndStoresGeneratedResources() throws Exception {
        String token = loginToken();
        MvcResult created = mockMvc.perform(post("/api/agent/resource-task")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "course_id": 1,
                                  "knowledge_point_ids": [131],
                                  "resource_types": ["lecture", "quiz"],
                                  "difficulty": "basic",
                                  "goal": "理解二叉树递归遍历"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_id").exists())
                .andReturn();

        String taskId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.task_id");
        String taskJson = waitForTaskSuccess(taskId, token);

        assertThat(JsonPath.<String>read(taskJson, "$.data.status")).isEqualTo("success");
        assertThat(JsonPath.<String>read(taskJson, "$.data.steps[0].agent")).isEqualTo("ProfileAgent");
        assertThat(JsonPath.<String>read(taskJson, "$.data.steps[-1].agent")).isEqualTo("SafetyAgent");
        assertThat(JsonPath.<String>read(taskJson, "$.data.result.resources[0].resource_id")).startsWith("res_");
        assertThat(JsonPath.<String>read(taskJson, "$.data.result.generation_mode"))
                .isEqualTo("deterministic_fallback");
        assertThat(JsonPath.<String>read(taskJson, "$.data.result.resources[0].object_key")).contains("resources");
        assertThat(JsonPath.<String>read(taskJson, "$.data.result.resources[0].personalized_reason")).isEqualTo("针对当前画像生成");
        assertThat(JsonPath.<Integer>read(taskJson, "$.data.result.resources[0].estimated_minutes")).isEqualTo(20);
        assertThat(JsonPath.<String>read(taskJson, "$.data.result.resources[0].profile_fingerprint")).isEqualTo("test-profile");

        String resourceId = JsonPath.read(taskJson, "$.data.result.resources[0].resource_id");
        mockMvc.perform(get("/api/resources/{resourceId}", resourceId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.personalized_reason").value("针对当前画像生成"))
                .andExpect(jsonPath("$.data.estimated_minutes").value(20))
                .andExpect(jsonPath("$.data.profile_fingerprint").value("test-profile"))
                .andExpect(jsonPath("$.data.quality_evaluation.total_score").value(100))
                .andExpect(jsonPath("$.data.quality_evaluation.grade").value("A"))
                .andExpect(jsonPath("$.data.quality_evaluation.gate_passed").value(true))
                .andExpect(jsonPath("$.data.generation_mode").value("deterministic_fallback"));

        mockMvc.perform(get("/api/resources/quality-metrics")
                        .header("Authorization", bearer(token))
                        .param("course_id", "1")
                        .param("generation_mode", "deterministic_fallback")
                        .param("window_days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source.source_table").value("resources.quality_evaluation"))
                .andExpect(jsonPath("$.data.source.grain").exists())
                .andExpect(jsonPath("$.data.summary.evaluated_resources").isNumber())
                .andExpect(jsonPath("$.data.summary.average_score").value(100.0))
                .andExpect(jsonPath("$.data.trend[0].date").exists())
                .andExpect(jsonPath("$.data.by_resource_type[0].key").exists())
                .andExpect(jsonPath("$.data.by_generation_mode[0].key").value("deterministic_fallback"))
                .andExpect(jsonPath("$.data.dimensions").isArray())
                .andExpect(jsonPath("$.data.metric_definitions[0].definition").exists());
    }

    @Test
    void qualityRegressionTaskReevaluatesSmallBatchWithoutMutatingResources() throws Exception {
        String studentToken = loginToken();
        mockMvc.perform(post("/api/resources/quality-regression-tasks")
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"course_id\":1,\"window_days\":30,\"max_resources\":1,\"force\":true}"))
                .andExpect(status().isForbidden());

        String teacherToken = loginToken("teacher");
        MvcResult generated = mockMvc.perform(post("/api/agent/resource-task")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "course_id": 1,
                                  "knowledge_point_ids": [131],
                                  "resource_types": ["lecture"],
                                  "difficulty": "basic",
                                  "goal": "质量回归固定样本"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        waitForTaskSuccess(
                JsonPath.read(generated.getResponse().getContentAsString(), "$.data.task_id"),
                teacherToken);

        MvcResult created = mockMvc.perform(post("/api/resources/quality-regression-tasks")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "course_id": 1,
                                  "generation_mode": "deterministic_fallback",
                                  "window_days": 30,
                                  "max_resources": 1,
                                  "force": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_id").exists())
                .andReturn();
        String taskJson = waitForTaskSuccess(
                JsonPath.read(created.getResponse().getContentAsString(), "$.data.task_id"),
                teacherToken);

        assertThat(JsonPath.<List<String>>read(taskJson, "$.data.steps[*].agent"))
                .containsExactly("QualityMonitorAgent", "QualityEvaluator", "RegressionGuardAgent");
        assertThat(JsonPath.<Integer>read(taskJson, "$.data.result.inspected_resources")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(taskJson, "$.data.result.regression_count")).isZero();
        assertThat(JsonPath.<String>read(taskJson, "$.data.result.automation_policy")).isEqualTo("audit_only");
        assertThat(JsonPath.<String>read(taskJson, "$.data.result.results[0].resource_id")).startsWith("res_");
    }

    @Test
    void regressedResourceEntersDeduplicatedQueueAndRequiresManagerDecision() throws Exception {
        String teacherToken = loginToken("teacher");
        MvcResult generated = mockMvc.perform(post("/api/agent/resource-task")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "course_id": 1,
                                  "knowledge_point_ids": [131],
                                  "resource_types": ["lecture"],
                                  "difficulty": "basic",
                                  "goal": "受控修复队列固定样本"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        waitForTaskSuccess(
                JsonPath.read(generated.getResponse().getContentAsString(), "$.data.task_id"),
                teacherToken);

        aiAgentClient.simulateQualityRegression = true;
        MvcResult regression = mockMvc.perform(post("/api/resources/quality-regression-tasks")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "course_id": 1,
                                  "generation_mode": "deterministic_fallback",
                                  "window_days": 30,
                                  "max_resources": 1,
                                  "force": true
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String taskJson = waitForTaskSuccess(
                JsonPath.read(regression.getResponse().getContentAsString(), "$.data.task_id"),
                teacherToken);
        String repairId = JsonPath.read(taskJson, "$.data.result.queued_repair_ids[0]");
        String resourceId = JsonPath.read(taskJson, "$.data.result.regressed_resource_ids[0]");
        assertThat(JsonPath.<Integer>read(taskJson, "$.data.result.regression_count")).isEqualTo(1);

        MvcResult repeatedRegression = mockMvc.perform(post("/api/resources/quality-regression-tasks")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"course_id\":1,\"generation_mode\":\"deterministic_fallback\","
                                + "\"window_days\":30,\"max_resources\":1,\"force\":true}"))
                .andExpect(status().isOk())
                .andReturn();
        String repeatedTaskJson = waitForTaskSuccess(
                JsonPath.read(repeatedRegression.getResponse().getContentAsString(), "$.data.task_id"),
                teacherToken);
        assertThat(JsonPath.<String>read(repeatedTaskJson, "$.data.result.queued_repair_ids[0]"))
                .isEqualTo(repairId);

        MvcResult resourceBefore = mockMvc.perform(get("/api/resources/{resourceId}", resourceId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andReturn();
        String resourceBeforeJson = resourceBefore.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String contentBefore = JsonPath.read(resourceBeforeJson, "$.data.content");
        String statusBefore = JsonPath.read(resourceBeforeJson, "$.data.status");
        String resourceTypeBefore = JsonPath.read(resourceBeforeJson, "$.data.resource_type");
        Number qualityBefore = JsonPath.read(
                resourceBeforeJson, "$.data.quality_evaluation.total_score");

        mockMvc.perform(get("/api/resources/quality-repairs")
                        .header("Authorization", bearer(teacherToken))
                        .param("course_id", "1")
                        .param("status", "pending_review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.repair_id == '%s')]", repairId).isNotEmpty())
                .andExpect(jsonPath("$.data.items[?(@.repair_id == '%s')].can_review", repairId).isNotEmpty());

        String studentToken = loginToken();
        mockMvc.perform(patch("/api/resources/quality-repairs/{repairId}/decision", repairId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approve\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/resources/quality-repairs/{repairId}/decision", repairId)
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approve\",\"note\":\"确认后进入受控修复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.status").value("approved"))
                .andExpect(jsonPath("$.data.item.can_review").value(false))
                .andExpect(jsonPath("$.data.next_action").value("ready_for_controlled_repair"))
                .andExpect(jsonPath("$.data.safety_notice").exists());

        mockMvc.perform(patch("/api/resources/quality-repairs/{repairId}/decision", repairId)
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"reject\",\"note\":\"重复审核\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/resources/quality-repairs/{repairId}/execute", repairId)
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());

        MvcResult execution = mockMvc.perform(post("/api/resources/quality-repairs/{repairId}/execute", repairId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.repair_id").value(repairId))
                .andExpect(jsonPath("$.data.task_id").exists())
                .andExpect(jsonPath("$.data.status").value("in_progress"))
                .andReturn();
        String executionTaskJson = waitForTaskSuccess(
                JsonPath.read(execution.getResponse().getContentAsString(), "$.data.task_id"),
                teacherToken);
        String expectedResourceAgent = switch (resourceTypeBefore) {
            case "mindmap", "flowchart" -> "MindmapAgent";
            case "quiz" -> "QuizAgent";
            case "codelab" -> "CodelabAgent";
            case "animation_script" -> "AnimationScriptAgent";
            default -> "LectureAgent";
        };
        assertThat(JsonPath.<List<String>>read(executionTaskJson, "$.data.steps[*].agent"))
                .containsExactly("RepairPlannerAgent", expectedResourceAgent, "SafetyAgent", "QualityEvaluator");
        assertThat(JsonPath.<Boolean>read(executionTaskJson, "$.data.result.publish_ready")).isTrue();
        assertThat(JsonPath.<Boolean>read(executionTaskJson, "$.data.result.original_resource_unchanged")).isTrue();
        assertThat(JsonPath.<String>read(executionTaskJson, "$.data.result.next_action"))
                .isEqualTo("manual_publish_review");

        mockMvc.perform(post("/api/resources/quality-repairs/{repairId}/execute", repairId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/resources/quality-repairs")
                        .header("Authorization", bearer(teacherToken))
                        .param("course_id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.repair_id == '%s' && @.status == 'completed')]", repairId)
                        .isNotEmpty())
                .andExpect(jsonPath("$.data.items[?(@.repair_id == '%s' && @.publish_ready == true)]", repairId)
                        .isNotEmpty());

        mockMvc.perform(get("/api/resources/{resourceId}", resourceId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(contentBefore))
                .andExpect(jsonPath("$.data.status").value(statusBefore))
                .andExpect(jsonPath("$.data.quality_evaluation.total_score").value(qualityBefore.doubleValue()));

        mockMvc.perform(get("/api/resources/quality-repairs/{repairId}/comparison", repairId)
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isForbidden());

        MvcResult comparison = mockMvc.perform(get(
                                "/api/resources/quality-repairs/{repairId}/comparison", repairId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stale").value(false))
                .andExpect(jsonPath("$.data.can_publish").value(true))
                .andExpect(jsonPath("$.data.changed_fields").isArray())
                .andExpect(jsonPath("$.data.candidate_resource.content").value(org.hamcrest.Matchers.containsString("定向修复")))
                .andReturn();
        int baseVersion = JsonPath.read(
                comparison.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.data.candidate_base_version");

        mockMvc.perform(post("/api/resources/quality-repairs/{repairId}/publish", repairId)
                        .header("Authorization", bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_resource_version\":" + baseVersion + "}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/resources/quality-repairs/{repairId}/publish", repairId)
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_resource_version\":" + baseVersion
                                + ",\"note\":\"已完成原文与候选版本人工对比\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("published"))
                .andExpect(jsonPath("$.data.from_version").value(baseVersion))
                .andExpect(jsonPath("$.data.to_version").value(baseVersion + 1))
                .andExpect(jsonPath("$.data.atomic").value(true));

        mockMvc.perform(get("/api/resources/{resourceId}", resourceId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString("定向修复")))
                .andExpect(jsonPath("$.data.status").value("published"))
                .andExpect(jsonPath("$.data.quality_evaluation.gate_passed").value(true));

        mockMvc.perform(post("/api/resources/quality-repairs/{repairId}/publish", repairId)
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_resource_version\":" + baseVersion + "}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/resources/quality-repairs/{repairId}/rollback", repairId)
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"演示版本回滚验证\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("rolled_back"))
                .andExpect(jsonPath("$.data.from_version").value(baseVersion + 1))
                .andExpect(jsonPath("$.data.to_version").value(baseVersion + 2))
                .andExpect(jsonPath("$.data.atomic").value(true));

        mockMvc.perform(get("/api/resources/{resourceId}", resourceId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(contentBefore))
                .andExpect(jsonPath("$.data.status").value(statusBefore))
                .andExpect(jsonPath("$.data.quality_evaluation.total_score").value(qualityBefore.doubleValue()));

        mockMvc.perform(post("/api/resources/quality-repairs/{repairId}/rollback", repairId)
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void teacherClassInsightsAggregateRealLearningEvidenceWithRiskThresholds() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String username = "insight_student_" + suffix;
        String adminToken = loginToken("admin");
        String teacherToken = loginToken("teacher");
        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username
                                + "\",\"password\":\"123456\",\"role\":\"student\"}"))
                .andExpect(status().isOk());
        Long studentId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, username);

        MvcResult classResult = mockMvc.perform(post("/api/classes")
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"洞察测试班 " + suffix + "\",\"course_id\":1}"))
                .andExpect(status().isOk())
                .andReturn();
        Number classId = JsonPath.read(
                classResult.getResponse().getContentAsString(StandardCharsets.UTF_8),
                "$.data.class_info.id");
        mockMvc.perform(post("/api/classes/{classId}/members", classId.longValue())
                        .header("Authorization", bearer(teacherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":" + studentId + ",\"role\":\"student\"}"))
                .andExpect(status().isOk());

        jdbcTemplate.update(
                """
                INSERT INTO knowledge_mastery (
                    student_id, knowledge_point, attempts, correct_count, mastery_score,
                    behavior_event_count, behavior_weight, behavior_score_sum, updated_at
                ) VALUES (?, '二叉树递归遍历', 4, 2, 58, 2, 4, 116, CURRENT_TIMESTAMP)
                """,
                username);
        jdbcTemplate.update(
                """
                INSERT INTO learning_events (
                    event_id, idempotency_key, student_id, user_id, event_type, course_id,
                    knowledge_point, source_type, source_id, action, progress_percent,
                    evidence_weight, evidence_score, metadata, occurred_at
                ) VALUES (?, ?, ?, ?, 'resource_reading', 1, '二叉树递归遍历',
                          'resource', 'insight-resource', 'complete', 100, 2, 58, '{}', CURRENT_TIMESTAMP)
                """,
                "insight-event-" + suffix,
                "insight-key-" + suffix,
                username,
                studentId);

        String studentToken = loginToken(username);
        mockMvc.perform(get("/api/classes/{classId}/insights", classId.longValue())
                        .header("Authorization", bearer(studentToken))
                        .param("course_id", "1"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/classes/{classId}/insights", classId.longValue())
                        .header("Authorization", bearer(teacherToken))
                        .param("course_id", "1")
                        .param("window_days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source.window_days").value(30))
                .andExpect(jsonPath("$.data.class_info.id").value(classId.longValue()))
                .andExpect(jsonPath("$.data.selected_course.course_id").value(1))
                .andExpect(jsonPath("$.data.summary.student_count").value(1))
                .andExpect(jsonPath("$.data.summary.active_students").value(1))
                .andExpect(jsonPath("$.data.summary.average_mastery").value(58.0))
                .andExpect(jsonPath("$.data.summary.critical_students").value(1))
                .andExpect(jsonPath("$.data.activity_trend[0].event_count").value(1))
                .andExpect(jsonPath("$.data.weak_knowledge_points[0].knowledge_point")
                        .value("二叉树递归遍历"))
                .andExpect(jsonPath("$.data.student_risks[0].risk_level").value("critical"))
                .andExpect(jsonPath("$.data.recommended_interventions").isNotEmpty());

        mockMvc.perform(get("/api/classes/{classId}/insights", classId.longValue())
                        .header("Authorization", bearer(teacherToken))
                        .param("course_id", "2"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resourceTaskFailsWhenAiServiceIsUnavailable() throws Exception {
        aiAgentClient.failResourceGeneration = true;
        String token = loginToken();

        MvcResult created = mockMvc.perform(post("/api/agent/resource-task")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "course_id": 1,
                                  "knowledge_point_ids": [131],
                                  "resource_types": ["lecture"],
                                  "difficulty": "basic"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_id").exists())
                .andReturn();

        String taskId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.task_id");
        String taskJson = waitForTerminalTask(taskId, token);

        assertThat(JsonPath.<String>read(taskJson, "$.data.status")).isEqualTo("failed");
        assertThat(JsonPath.<String>read(taskJson, "$.data.error_message")).contains("/resource/generate 不可用");
        assertThat(JsonPath.<List<String>>read(taskJson, "$.data.steps[*].status")).contains("failed");

        mockMvc.perform(get("/api/agent/tasks")
                        .header("Authorization", bearer(token))
                        .param("status", "failed")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].task_id").value(org.hamcrest.Matchers.hasItem(taskId)))
                .andExpect(jsonPath("$.data.items[?(@.task_id == '" + taskId + "')].retryable")
                        .value(org.hamcrest.Matchers.hasItem(true)));

        String teacherToken = loginToken("teacher");
        mockMvc.perform(get("/api/agent/tasks/{taskId}", taskId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/agent/tasks/{taskId}/retry", taskId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/agent/tasks/{taskId}/cancel", taskId)
                        .header("Authorization", bearer(teacherToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/agent/tasks/{taskId}/retry", taskId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_id").exists());

        mockMvc.perform(get("/api/agent/tasks")
                        .header("Authorization", bearer(token))
                        .param("status", "unknown"))
                .andExpect(status().isBadRequest());
    }

    private String waitForTaskSuccess(String taskId, String token) throws Exception {
        String lastResponse = waitForTerminalTask(taskId, token);
        assertThat(JsonPath.<String>read(lastResponse, "$.data.status")).isEqualTo("success");
        return lastResponse;
    }

    private String waitForTerminalTask(String taskId, String token) throws Exception {
        String lastResponse = "";
        for (int i = 0; i < 30; i++) {
            MvcResult result = mockMvc.perform(get("/api/agent/tasks/{taskId}", taskId)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andReturn();
            lastResponse = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            String status = JsonPath.read(lastResponse, "$.data.status");
            if ("success".equals(status) || "failed".equals(status)) {
                return lastResponse;
            }
            Thread.sleep(50);
        }
        return lastResponse;
    }

    private String loginToken() throws Exception {
        return loginToken("demo");
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

    private static AiResourceGenerateResult aiResourceResult() {
        return new AiResourceGenerateResult(
                "ai_resource_001",
                "success",
                List.of("ProfileAgent", "KnowledgeAgent", "LectureAgent", "QuizAgent", "SafetyAgent"),
                "passed",
                List.of(
                        new AiGeneratedResource(
                                "二叉树递归遍历个性化讲义",
                                "lecture",
                                "markdown",
                                "## 二叉树递归遍历\n\n由 AI 服务返回的结构化讲义。",
                                "基于 RAG 证据生成的讲义。",
                                "basic",
                                List.of("二叉树递归遍历"),
                                "针对当前画像生成",
                                20,
                                "test-profile"),
                        new AiGeneratedResource(
                                "二叉树递归遍历分层练习",
                                "quiz",
                                "markdown",
                                "## 练习\n\n1. 前序遍历顺序是什么？",
                                "基于 RAG 证据生成的练习。",
                                "basic",
                                List.of("二叉树递归遍历"),
                                "针对当前画像生成",
                                15,
                                "test-profile")),
                List.of(new AiEvidence(
                        "ai_chunk_1",
                        "二叉树递归遍历核心证据",
                        "二叉树遍历包括前序、中序、后序。",
                        0.91,
                        "data-structures/rag-evidence-1.md")),
                new AiSafety(true, "low", List.of(), List.of("已完成 SafetyAgent 审查"), 0.91));
    }

    @TestConfiguration
    static class AiStubConfiguration {

        @Bean
        @Primary
        StubAiAgentClient stubAiAgentClient() {
            return new StubAiAgentClient();
        }
    }

    static class StubAiAgentClient extends AiAgentClient {

        private boolean failResourceGeneration;
        private boolean simulateQualityRegression;
        private KnowledgeDocumentPayload lastIngestPayload;
        private KnowledgeSearchRequest lastSearchRequest;

        StubAiAgentClient() {
            super("http://127.0.0.1:9");
        }

        @Override
        public AiResourceGenerateResult generateResource(
                ResourceTaskRequest request, List<String> plannedAgents, Map<String, Object> studentProfile) {
            if (failResourceGeneration) {
                throw new ExternalServiceException("AI 服务", "/resource/generate 不可用");
            }
            return aiResourceResult();
        }

        @Override
        public AiResourceGenerateResult generateResourceAsync(
                ResourceTaskRequest request,
                List<String> plannedAgents,
                Map<String, Object> studentProfile,
                AiTaskProgressListener listener) {
            return generateResource(request, plannedAgents, studentProfile);
        }

        @Override
        public KnowledgeIngestResult ingestKnowledgeDocument(KnowledgeDocumentPayload payload) {
            lastIngestPayload = payload;
            return new KnowledgeIngestResult(
                    "ai_kb_ingest_001",
                    "parsed",
                    "indexed",
                    List.of(new KnowledgeChunk(
                            "kb_" + payload.documentId() + "_1",
                            "上传讲义片段",
                            payload.content(),
                            1.0,
                            payload.objectKey())),
                    new AiSafety(true, "low", List.of(), List.of("已完成 SafetyAgent 审查"), 0.94));
        }

        @Override
        public Map<String, Object> searchKnowledge(KnowledgeSearchRequest request) {
            lastSearchRequest = request;
            return Map.of(
                    "course_id", 2,
                    "query", request.query(),
                    "top_k", request.topK(),
                    "corpus", Map.of(
                            "mode", "course_corpus",
                            "documents", 66,
                            "external_documents", 58,
                            "chunks", 1183,
                            "vector_store", "local",
                            "embedding", "local-hash-v1"),
                    "results", List.of(Map.ofEntries(
                            Map.entry("chunk_id", "ai_chunk_2_251_cache_1"),
                            Map.entry("course_id", 2),
                            Map.entry("knowledge_point_id", 251),
                            Map.entry("knowledge_point", "Cache 映射方式"),
                            Map.entry("related_knowledge_point_ids", List.of(253)),
                            Map.entry("title", "Cache 组相联映射例题"),
                            Map.entry("content", "组号由主存块号对 Cache 组数取模得到。"),
                            Map.entry("score", 0.96),
                            Map.entry("source", "courseware/cache-mapping.pdf"),
                            Map.entry("author", "课程组"),
                            Map.entry("source_url", ""),
                            Map.entry("license", "competition-demo-only"),
                            Map.entry("license_status", "pending"),
                            Map.entry("document_type", "lecture"),
                            Map.entry("contains_examples", true),
                            Map.entry("heading_path", List.of("Cache", "组相联")))));
        }

        @Override
        public Map<String, Object> generatePath(
                com.edupath.path.LearningPathService.LearningPathGenerateRequest request,
                Map<String, Object> studentProfile) {
            return Map.of(
                    "task_id", "ai_path_001",
                    "path", Map.of(
                            "path_title", "3 天二叉树路径",
                            "daily_plan", List.of(Map.of(
                                    "day", 1,
                                    "theme", "二叉树递归遍历",
                                    "evidence_chunk_ids", List.of("chunk_path_1")))),
                    "evidence", List.of(Map.of("chunk_id", "chunk_path_1", "title", "二叉树递归遍历")),
                    "generation_mode", "real_model",
                    "model_runtime", Map.of(
                            "mode", "real_model",
                            "real_model_used", true,
                            "provider", "deepseek",
                            "model", "fake-path-model",
                            "call_count", 2,
                            "total_tokens", 600,
                            "duration_ms", 30),
                    "safety", Map.of(
                            "passed", true,
                            "risk_level", "low",
                            "issues", List.of(),
                            "suggestions", List.of(),
                            "confidence", 0.95));
        }

        @Override
        public Map<String, Object> replanPath(Map<String, Object> payload) {
            String trigger = String.valueOf(payload.getOrDefault("trigger", "quiz_evaluation"));
            Map<?, ?> evaluation = payload.get("evaluation") instanceof Map<?, ?> map ? map : Map.of();
            Map<?, ?> behaviorSignal = payload.get("behavior_signal") instanceof Map<?, ?> map ? map : Map.of();
            String evaluationTaskId = String.valueOf(
                    evaluation.containsKey("source_task_id") ? evaluation.get("source_task_id") : "");
            String behaviorTriggerKey = String.valueOf(
                    behaviorSignal.containsKey("trigger_key") ? behaviorSignal.get("trigger_key") : "");
            Object rawWeakPoints = "behavior_signal".equals(trigger)
                    ? behaviorSignal.get("weak_points")
                    : evaluation.get("weak_points");
            List<String> weakPoints = rawWeakPoints instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of();
            String focus = weakPoints.isEmpty() ? "综合迁移训练" : weakPoints.get(0);
            Map<?, ?> currentPath = payload.get("current_path") instanceof Map<?, ?> map ? map : Map.of();
            String previousPathId = String.valueOf(
                    currentPath.containsKey("path_id") ? currentPath.get("path_id") : "");
            List<Map<String, Object>> dailyPlan = java.util.stream.IntStream.rangeClosed(1, 3)
                    .mapToObj(day -> Map.<String, Object>ofEntries(
                            Map.entry("day", day),
                            Map.entry("theme", focus),
                            Map.entry("difficulty", "basic"),
                            Map.entry("reason", "根据本次小测结果优先安排薄弱点补救和复测。"),
                            Map.entry("tasks", List.of(Map.of(
                                    "type", "lecture",
                                    "resource_id", "lecture_pending",
                                    "title", focus + "补救讲义",
                                    "estimated_minutes", 20))),
                            Map.entry("expected_outcome", "能解释薄弱点并通过一次针对性复测。"),
                            Map.entry("evidence_chunk_ids", List.of("replan_chunk_1"))))
                    .toList();
            return Map.ofEntries(
                    Map.entry("task_id", "ai_path_replan_001"),
                    Map.entry("trigger", trigger),
                    Map.entry("evaluation_task_id", evaluationTaskId),
                    Map.entry("behavior_trigger_key", behaviorTriggerKey),
                    Map.entry("previous_path_id", previousPathId),
                    Map.entry("path", Map.ofEntries(
                            Map.entry("path_title", "未来 3 天动态补救路径"),
                            Map.entry("target", "根据最近测评动态补强薄弱知识点"),
                            Map.entry("course_ids", payload.getOrDefault("course_ids", List.of(1))),
                            Map.entry("daily_minutes", payload.getOrDefault("daily_minutes", 40)),
                            Map.entry("daily_plan", dailyPlan),
                            Map.entry("adjustment_strategy", "优先补救本次错题知识点并在第三天复测。"),
                            Map.entry("personalization_summary", "依据最新评估和画像动态调整。"),
                            Map.entry("profile_fingerprint", "test-replan-profile"),
                            Map.entry("evidence_chunk_ids", List.of("replan_chunk_1")))),
                    Map.entry("changes", List.of(Map.of(
                            "action", weakPoints.isEmpty() ? "advance" : "insert_remediation",
                            "knowledge_point", focus,
                            "reason", "依据本次小测结果调整未来三天的学习顺序。"))),
                    Map.entry("evidence", List.of(Map.of(
                            "chunk_id", "replan_chunk_1",
                            "title", focus + "课程证据"))),
                    Map.entry("generation_mode", "real_model"),
                    Map.entry("model_runtime", modelRuntime("fake-replan-model", 2, 380)),
                    Map.entry("safety", passedSafety()));
        }

        @Override
        public Map<String, Object> chatTutor(com.edupath.tutor.TutorService.TutorChatRequest request) {
            String answer = "二叉树递归遍历需要先明确递归出口和访问顺序。";
            return Map.ofEntries(
                    Map.entry("task_id", "ai_tutor_001"),
                    Map.entry("answer", answer),
                    Map.entry("answer_markdown", answer),
                    Map.entry("steps", List.of("明确递归出口", "跟踪访问顺序")),
                    Map.entry("citations", List.of(Map.of(
                            "answer_fragment", "二叉树递归遍历需要先明确递归出口",
                            "evidence_chunk_ids", List.of("chunk_1")))),
                    Map.entry("confidence", 0.9),
                    Map.entry("evidence", List.of(Map.of("chunk_id", "chunk_1", "score", 0.9))),
                    Map.entry("generation_mode", "real_model"),
                    Map.entry("model_runtime", Map.of(
                            "mode", "real_model",
                            "real_model_used", true,
                            "provider", "deepseek",
                            "model", "fake-tutor-model",
                            "call_count", 2,
                            "total_tokens", 500,
                            "duration_ms", 25)),
                    Map.entry("safety", Map.of(
                            "passed", true,
                            "risk_level", "low",
                            "issues", List.of(),
                            "suggestions", List.of(),
                            "confidence", 0.95)));
        }

        @Override
        public Map<String, Object> generateQuiz(
                com.edupath.quiz.QuizService.QuizGenerateRequest request,
                List<String> knowledgePoints,
                Map<String, Object> studentProfile) {
            int questionCount = request.questionCount() == null ? 3 : request.questionCount();
            String knowledgePoint = knowledgePoints == null || knowledgePoints.isEmpty()
                    ? "二叉树递归遍历"
                    : knowledgePoints.get(0);
            List<Map<String, Object>> questions = new java.util.ArrayList<>();
            for (int index = 1; index <= questionCount; index++) {
                questions.add(Map.ofEntries(
                        Map.entry("question_order", index),
                        Map.entry("type", "single_choice"),
                        Map.entry("knowledge_point", knowledgePoint),
                        Map.entry("stem", "第 " + index + " 题：" + knowledgePoint + " 的正确学习步骤是什么？"),
                        Map.entry("options", List.of(
                                "先明确基本概念、边界条件与执行过程",
                                "忽略边界条件直接记忆结论",
                                "只看最终答案不检查过程",
                                "跳过课程证据凭直觉作答")),
                        Map.entry("answer", "A"),
                        Map.entry("explanation", "A 与课程证据中的概念、边界条件和执行过程一致。"),
                        Map.entry("evidence_chunk_ids", List.of("quiz_chunk_1"))));
            }
            return Map.ofEntries(
                    Map.entry("task_id", "ai_quiz_001"),
                    Map.entry("quiz", Map.of("title", "3 题以内专项小测", "questions", questions)),
                    Map.entry("evidence", List.of(Map.ofEntries(
                            Map.entry("chunk_id", "quiz_chunk_1"),
                            Map.entry("knowledge_point", knowledgePoint),
                            Map.entry("title", knowledgePoint + "课程证据"),
                            Map.entry("content", "学习该知识点需要核对基本概念、边界条件与执行过程。"),
                            Map.entry("score", 0.96),
                            Map.entry("source", "courseware/quiz-evidence.md")))),
                    Map.entry("generation_mode", "real_model"),
                    Map.entry("model_runtime", modelRuntime("fake-quiz-model", 2, 420)),
                    Map.entry("safety", passedSafety()));
        }

        @Override
        public Map<String, Object> analyzeEvaluation(Map<String, Object> payload) {
            int score = payload.get("score") instanceof Number number ? number.intValue() : 0;
            List<String> weakPoints = new java.util.ArrayList<>();
            List<Map<String, Object>> mastery = new java.util.ArrayList<>();
            Object rawResults = payload.get("question_results");
            if (rawResults instanceof List<?> results) {
                for (Object item : results) {
                    if (item instanceof Map<?, ?> result) {
                        String knowledgePoint = String.valueOf(result.get("knowledge_point"));
                        boolean correct = Boolean.TRUE.equals(result.get("correct"));
                        if (!correct && !weakPoints.contains(knowledgePoint)) {
                            weakPoints.add(knowledgePoint);
                        }
                        mastery.add(Map.of(
                                "knowledge_point", knowledgePoint,
                                "mastery_score", correct ? 100 : 0,
                                "level", correct ? "优秀" : "待补救"));
                    }
                }
            }
            List<String> mistakePatterns = weakPoints.isEmpty()
                    ? List.of()
                    : List.of("concept_confusion");
            List<String> nextActions = weakPoints.isEmpty()
                    ? List.of("保持当前学习节奏并完成迁移练习")
                    : List.of("生成 " + weakPoints.get(0) + " 补救资源并复盘错题");
            Map<String, Object> evaluation = Map.ofEntries(
                    Map.entry("overall_score", score),
                    Map.entry("mastery_by_knowledge_point", mastery),
                    Map.entry("weak_points", weakPoints),
                    Map.entry("mistake_patterns", mistakePatterns),
                    Map.entry("next_actions", nextActions),
                    Map.entry("summary", weakPoints.isEmpty() ? "本次小测全部答对。" : "本次错题已定位到对应知识点。"));
            return Map.ofEntries(
                    Map.entry("task_id", "ai_evaluation_001"),
                    Map.entry("evaluation", evaluation),
                    Map.entry("evidence", List.of(Map.of(
                            "chunk_id", "quiz_chunk_1",
                            "knowledge_point", weakPoints.isEmpty() ? "已掌握知识点" : weakPoints.get(0),
                            "score", 0.95))),
                    Map.entry("generation_mode", "real_model"),
                    Map.entry("model_runtime", modelRuntime("fake-evaluation-model", 2, 360)),
                    Map.entry("safety", passedSafety()));
        }

        @Override
        public Map<String, Object> regressResourceQuality(Map<String, Object> payload) {
            Map<String, Object> baseline = new java.util.LinkedHashMap<>();
            if (payload.get("baseline_evaluation") instanceof Map<?, ?> raw) {
                raw.forEach((key, value) -> baseline.put(String.valueOf(key), value));
            }
            if (simulateQualityRegression) {
                Map<String, Object> current = new java.util.LinkedHashMap<>(baseline);
                double baselineScore = baseline.get("total_score") instanceof Number number
                        ? number.doubleValue()
                        : 100.0;
                current.put("total_score", baselineScore - 10.0);
                current.put("grade", "B");
                current.put("gate_passed", false);
                return Map.ofEntries(
                        Map.entry("resource_id", String.valueOf(payload.get("resource_id"))),
                        Map.entry("evaluator_version", "resource-quality-v1"),
                        Map.entry("baseline_evaluation", baseline),
                        Map.entry("current_evaluation", current),
                        Map.entry("score_delta", -10.0),
                        Map.entry("gate_changed", true),
                        Map.entry("evaluator_changed", false),
                        Map.entry("regressed_dimensions", List.of("evidence_coverage")),
                        Map.entry("regression_detected", true));
            }
            return Map.ofEntries(
                    Map.entry("resource_id", String.valueOf(payload.get("resource_id"))),
                    Map.entry("evaluator_version", "resource-quality-v1"),
                    Map.entry("baseline_evaluation", baseline),
                    Map.entry("current_evaluation", baseline),
                    Map.entry("score_delta", 0.0),
                    Map.entry("gate_changed", false),
                    Map.entry("evaluator_changed", false),
                    Map.entry("regressed_dimensions", List.of()),
                    Map.entry("regression_detected", false));
        }

        @Override
        public Map<String, Object> repairResource(Map<String, Object> payload) {
            Map<String, Object> candidate = new java.util.LinkedHashMap<>();
            if (payload.get("resource") instanceof Map<?, ?> raw) {
                raw.forEach((key, value) -> candidate.put(String.valueOf(key), value));
            }
            candidate.put(
                    "content",
                    String.valueOf(candidate.getOrDefault("content", ""))
                            + "\n\n## 定向修复\n已补充回归维度所需的课程证据与边界说明。");
            Map<String, Object> quality = new java.util.LinkedHashMap<>();
            if (payload.get("baseline_evaluation") instanceof Map<?, ?> raw) {
                raw.forEach((key, value) -> quality.put(String.valueOf(key), value));
            }
            quality.put("gate_passed", true);
            candidate.put("quality_evaluation", quality);
            return Map.ofEntries(
                    Map.entry("repair_id", String.valueOf(payload.get("repair_id"))),
                    Map.entry("resource_id", String.valueOf(payload.get("resource_id"))),
                    Map.entry("status", "candidate_ready"),
                    Map.entry("candidate_resource", candidate),
                    Map.entry("safety", passedSafety()),
                    Map.entry("quality_evaluation", quality),
                    Map.entry("recovery_score_delta", 10.0),
                    Map.entry("improved_dimensions", List.of("evidence_coverage")),
                    Map.entry("target_dimensions_passed", true),
                    Map.entry("model_runtime", modelRuntime("fake-repair-model", 2, 380)));
        }

        private Map<String, Object> modelRuntime(String model, int callCount, int totalTokens) {
            return Map.of(
                    "mode", "real_model",
                    "real_model_used", true,
                    "provider", "deepseek",
                    "model", model,
                    "call_count", callCount,
                    "total_tokens", totalTokens,
                    "duration_ms", 20);
        }

        private Map<String, Object> passedSafety() {
            return Map.of(
                    "passed", true,
                    "risk_level", "low",
                    "issues", List.of(),
                    "suggestions", List.of(),
                    "confidence", 0.95);
        }

        @Override
        public Map<String, Object> extractProfile(com.edupath.profile.ProfileService.ProfileChatRequest request, String studentId) {
            return Map.of(
                    "task_id", "ai_profile_001",
                    "profile", Map.of(
                            "student_id", studentId,
                            "weak_points", List.of("递归调用栈"),
                            "updated_reason", "ProfileAgent 根据对话更新画像"),
                    "extracted", Map.of("dimensions", List.of("weak_points")),
                    "generation_mode", "real_model",
                    "model_runtime", Map.of(
                            "mode", "real_model",
                            "real_model_used", true,
                            "provider", "deepseek",
                            "model", "fake-profile-model",
                            "call_count", 2,
                            "total_tokens", 400,
                            "duration_ms", 20),
                    "safety", Map.of(
                            "passed", true,
                            "risk_level", "low",
                            "issues", List.of(),
                            "suggestions", List.of(),
                            "confidence", 0.95));
        }
    }
}
