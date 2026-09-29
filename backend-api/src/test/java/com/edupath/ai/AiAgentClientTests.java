package com.edupath.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.edupath.common.ExternalServiceException;
import com.edupath.kb.KnowledgeBaseService.KnowledgeSearchRequest;
import com.edupath.path.LearningPathService.LearningPathGenerateRequest;
import com.edupath.resource.ResourceTaskRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiAgentClientTests {

    private final Map<String, String> requestBodies = new ConcurrentHashMap<>();
    private HttpServer server;
    private AiAgentClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = new AiAgentClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void generateResourcePostsContractPayloadAndMapsStructuredAiResponse() {
        registerJsonHandler(
                "/resource/generate",
                200,
                """
                {
                  "task_id": "ai_resource_contract_001",
                  "status": "success",
                  "planned_agents": ["ProfileAgent", "KnowledgeAgent", "LectureAgent", "SafetyAgent"],
                  "safety_status": "passed",
                  "resources": [
                    {
                      "title": "二叉树递归遍历个性化讲义",
                      "resource_type": "lecture",
                      "content_format": "markdown",
                      "content": "## 二叉树递归遍历",
                      "summary": "RAG 证据讲义",
                      "difficulty": "basic",
                      "knowledge_points": ["二叉树递归遍历"]
                    }
                  ],
                  "evidence": [
                    {
                      "chunk_id": "ai_chunk_1_131_1",
                      "title": "二叉树递归遍历与调用栈",
                      "content": "前序遍历先访问根节点。",
                      "score": 0.93,
                      "source": "data_structures_algorithms/tree-recursive-traversal.md"
                    }
                  ],
                  "safety": {
                    "passed": true,
                    "risk_level": "low",
                    "issues": [],
                    "suggestions": ["已引用 RAG 证据"],
                    "confidence": 0.92
                  }
                }
                """);

        AiAgentClient.AiResourceGenerateResult result = client.generateResource(
                new ResourceTaskRequest(1, List.of(131), List.of("递归调用栈"), "补齐递归", List.of("lecture"), "basic"),
                List.of("ProfileAgent", "KnowledgeAgent", "LectureAgent", "SafetyAgent"),
                Map.of(
                        "student_id", "student_42",
                        "weak_points", List.of("递归调用栈"),
                        "resource_preference", List.of("mindmap"),
                        "latest_quiz_score", 48));

        assertThat(requestBodies.get("/resource/generate")).contains("\"course_id\":1");
        assertThat(requestBodies.get("/resource/generate")).contains("\"knowledge_point_ids\":[131]");
        assertThat(requestBodies.get("/resource/generate")).contains("\"knowledge_points\":[\"递归调用栈\"]");
        assertThat(requestBodies.get("/resource/generate")).contains("\"goal\":\"补齐递归\"");
        assertThat(requestBodies.get("/resource/generate")).contains("\"student_id\":\"student_42\"");
        assertThat(requestBodies.get("/resource/generate")).contains("\"weak_points\":[\"递归调用栈\"]");
        assertThat(requestBodies.get("/resource/generate")).contains("\"latest_quiz_score\":48");
        assertThat(result.taskId()).isEqualTo("ai_resource_contract_001");
        assertThat(result.resources()).hasSize(1);
        assertThat(result.resources().get(0).resourceType()).isEqualTo("lecture");
        assertThat(result.evidence().get(0).chunkId()).isEqualTo("ai_chunk_1_131_1");
        assertThat(result.safety().passed()).isTrue();
    }

    @Test
    void asyncResourceGenerationPollsAiTaskAndMapsModelRuntime() {
        registerJsonHandler(
                "/resource/tasks",
                200,
                """
                {
                  "task_id": "ai_resource_async_001",
                  "status": "pending",
                  "planned_agents": ["ProfileAgent", "KnowledgeAgent", "LectureAgent", "SafetyAgent"]
                }
                """);
        registerJsonHandler(
                "/tasks/ai_resource_async_001",
                200,
                """
                {
                  "task_id": "ai_resource_async_001",
                  "status": "success",
                  "progress": 100,
                  "planned_agents": ["ProfileAgent", "KnowledgeAgent", "LectureAgent", "SafetyAgent"],
                  "steps": [
                    {"agent":"ProfileAgent","status":"success","message":"画像完成"},
                    {"agent":"KnowledgeAgent","status":"success","message":"检索完成"},
                    {"agent":"LectureAgent","status":"success","message":"讲义完成"},
                    {"agent":"SafetyAgent","status":"success","message":"审查通过"}
                  ],
                  "result": {
                    "safety_status": "passed",
                    "resources": [{
                      "title": "真实模型讲义",
                      "resource_type": "lecture",
                      "content_format": "markdown",
                      "content": "## 完整讲义",
                      "summary": "真实模型生成",
                      "difficulty": "basic",
                      "knowledge_points": ["二叉树递归遍历"],
                      "personalized_reason": "针对薄弱点",
                      "estimated_minutes": 20,
                      "profile_fingerprint": "profile-test"
                    }],
                    "evidence": [{
                      "chunk_id": "ai_chunk_1_131_1",
                      "title": "递归遍历",
                      "content": "前序遍历先访问根节点。",
                      "score": 0.94,
                      "source": "course/tree.md"
                    }],
                    "safety": {
                      "passed": true,
                      "risk_level": "low",
                      "issues": [],
                      "suggestions": [],
                      "confidence": 0.95
                    },
                    "model_runtime": {
                      "mode": "real_model",
                      "provider": "deepseek",
                      "model": "deepseek-v4-flash",
                      "call_count": 2,
                      "total_tokens": 480
                    }
                  }
                }
                """);

        List<Map<String, Object>> snapshots = new java.util.ArrayList<>();
        AiAgentClient.AiResourceGenerateResult result = client.generateResourceAsync(
                new ResourceTaskRequest(1, List.of(131), List.of("二叉树递归遍历"), "掌握递归", List.of("lecture"), "basic"),
                List.of("ProfileAgent", "KnowledgeAgent", "LectureAgent", "SafetyAgent"),
                Map.of("student_id", "student_42"),
                snapshots::add);

        assertThat(requestBodies.get("/resource/tasks")).contains("\"course_id\":1");
        assertThat(snapshots).hasSize(1);
        assertThat(result.taskId()).isEqualTo("ai_resource_async_001");
        assertThat(result.resources()).hasSize(1);
        assertThat(result.modelRuntime()).containsEntry("mode", "real_model");
        assertThat(result.modelRuntime()).containsEntry("model", "deepseek-v4-flash");
    }

    @Test
    void searchKnowledgePostsQueryAndReturnsAiResults() {
        registerJsonHandler(
                "/kb/search",
                200,
                """
                {
                  "results": [
                    {
                      "chunk_id": "ai_chunk_1_131_1",
                      "title": "二叉树递归遍历与调用栈",
                      "content": "递归出口通常是当前节点为空。",
                      "score": 0.94,
                      "source": "data_structures_algorithms/tree-recursive-traversal.md"
                    }
                  ]
                }
                """);

        Map<String, Object> response = client.searchKnowledge(new KnowledgeSearchRequest(1, "二叉树递归遍历", 1));

        assertThat(requestBodies.get("/kb/search")).contains("\"query\":\"二叉树递归遍历\"");
        assertThat(response.get("results")).isInstanceOf(List.class);
    }

    @Test
    void generatePathPostsAuthenticatedProfileSnapshot() {
        registerJsonHandler(
                "/path/generate",
                200,
                """
                {
                  "task_id": "ai_path_001",
                  "path": {
                    "path_id": "path_ai_local",
                    "path_title": "7 天最短路径补强",
                    "daily_plan": []
                  }
                }
                """);

        client.generatePath(
                new LearningPathGenerateRequest(List.of(1), "最短路径补强", 7, 30),
                Map.of(
                        "student_id", "student_path",
                        "weak_points", List.of("最短路径"),
                        "resource_preference", List.of("codelab")));

        assertThat(requestBodies.get("/path/generate")).contains("\"student_id\":\"student_path\"");
        assertThat(requestBodies.get("/path/generate")).contains("\"weak_points\":[\"最短路径\"]");
        assertThat(requestBodies.get("/path/generate")).contains("\"resource_preference\":[\"codelab\"]");
    }

    @Test
    void aiHttpFailureSurfacesAsExternalServiceException() {
        registerJsonHandler("/resource/generate", 503, "{\"error\":\"unavailable\"}");

        assertThatThrownBy(() -> client.generateResource(
                        new ResourceTaskRequest(1, List.of(131), List.of(), null, List.of("lecture"), "basic"),
                        List.of("ProfileAgent", "KnowledgeAgent", "LectureAgent", "SafetyAgent"),
                        Map.of("student_id", "failure_case")))
                .isInstanceOf(ExternalServiceException.class)
                .hasMessageContaining("/resource/generate 不可用");
    }

    private void registerJsonHandler(String path, int status, String json) {
        server.createContext(path, exchange -> {
            requestBodies.put(path, body(exchange));
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }
}
