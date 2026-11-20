package com.edupath.ai;

import com.edupath.common.ExternalServiceException;
import com.edupath.course.CourseAccess;
import com.edupath.course.CourseCatalogService;
import com.edupath.kb.KnowledgeBaseService.KnowledgeSearchRequest;
import com.edupath.path.LearningPathService.LearningPathGenerateRequest;
import com.edupath.profile.ProfileService.ProfileChatRequest;
import com.edupath.quiz.QuizService.QuizGenerateRequest;
import com.edupath.resource.ResourceTaskRequest;
import com.edupath.tutor.PersonalPdfService.PdfAnswer;
import com.edupath.tutor.PersonalPdfService.PdfIndex;
import com.edupath.tutor.PersonalPdfService.PdfPage;
import com.edupath.tutor.TutorService.TutorChatRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class AiAgentClient {

    private static final Logger log = LoggerFactory.getLogger(AiAgentClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_RESPONSE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final long resourceTaskTimeoutMillis;
    private final long resourceTaskPollMillis;
    private final CourseAccess courseAccess;
    private final CourseCatalogService courseCatalog;

    @Autowired
    public AiAgentClient(
            @Value("${edupath.ai-service-base-url:http://localhost:8000}") String aiServiceBaseUrl,
            @Value("${edupath.ai.connect-timeout-millis:700}") int connectTimeoutMillis,
            @Value("${edupath.ai.read-timeout-millis:1200}") int readTimeoutMillis,
            @Value("${edupath.ai.resource-task-timeout-seconds:180}") long resourceTaskTimeoutSeconds,
            @Value("${edupath.ai.resource-task-poll-millis:250}") long resourceTaskPollMillis,
            CourseAccess courseAccess,
            CourseCatalogService courseCatalog) {
        this.restClient = buildRestClient(aiServiceBaseUrl, connectTimeoutMillis, readTimeoutMillis);
        this.resourceTaskTimeoutMillis = Math.max(resourceTaskTimeoutSeconds, 10) * 1000;
        this.resourceTaskPollMillis = Math.max(resourceTaskPollMillis, 50);
        this.courseAccess = courseAccess;
        this.courseCatalog = courseCatalog;
    }

    protected AiAgentClient(String aiServiceBaseUrl) {
        this.restClient = buildRestClient(aiServiceBaseUrl, 700, 1200);
        this.resourceTaskTimeoutMillis = 30_000;
        this.resourceTaskPollMillis = 50;
        this.courseAccess = null;
        this.courseCatalog = null;
    }

    private static RestClient buildRestClient(String aiServiceBaseUrl, int connectTimeoutMillis, int readTimeoutMillis) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMillis);
        requestFactory.setReadTimeout(readTimeoutMillis);
        return RestClient.builder()
                .baseUrl(aiServiceBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public AiResourceGenerateResult generateResource(
            ResourceTaskRequest request,
            List<String> plannedAgents,
            Map<String, Object> studentProfile) {
        Map<String, Object> response = postForMap("/resource/generate", resourcePayload(request, studentProfile));
        return aiResourceResult(response, asString(response.get("task_id")));
    }

    public AiResourceGenerateResult generateResourceAsync(
            ResourceTaskRequest request,
            List<String> plannedAgents,
            Map<String, Object> studentProfile,
            AiTaskProgressListener listener) {
        Map<String, Object> created = postForMap("/resource/tasks", resourcePayload(request, studentProfile));
        String taskId = asString(created.get("task_id"));
        if (taskId == null || taskId.isBlank()) {
            throw new ExternalServiceException("AI 服务", "/resource/tasks 未返回 task_id");
        }
        long deadline = System.currentTimeMillis() + resourceTaskTimeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> snapshot = getForMap("/tasks/" + taskId);
            if (listener != null) {
                listener.onSnapshot(snapshot);
            }
            String status = asString(snapshot.get("status"));
            if ("success".equals(status)) {
                Map<String, Object> result = asStringMap(snapshot.get("result"));
                result.putIfAbsent("task_id", taskId);
                result.putIfAbsent("status", status);
                result.putIfAbsent("planned_agents", snapshot.get("planned_agents"));
                return aiResourceResult(result, taskId);
            }
            if ("failed".equals(status) || "cancelled".equals(status)) {
                String detail = asString(first(snapshot, "error", "error_message", "errorMessage"));
                throw new ExternalServiceException(
                        "AI 服务",
                        "资源任务 " + taskId + " " + status + (detail == null ? "" : ": " + detail));
            }
            pauseResourcePolling(taskId);
        }
        try {
            postForMap("/tasks/" + taskId + "/cancel", Map.of());
        } catch (ExternalServiceException ignored) {
            log.warn("ai_resource_task cancel_after_timeout task_id={} status=failed", taskId);
        }
        throw new ExternalServiceException("AI 服务", "资源任务超时: " + taskId);
    }

    private Map<String, Object> resourcePayload(
            ResourceTaskRequest request, Map<String, Object> studentProfile) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("course_id", request.courseId());
        payload.put(
                "knowledge_point_ids",
                request.knowledgePointIds() == null ? List.of() : request.knowledgePointIds());
        payload.put(
                "knowledge_points",
                scopedTopicNames(request.courseId(), request.knowledgePointIds(), request.knowledgePoints()));
        payload.put("resource_types", request.normalizedResourceTypes());
        payload.put("difficulty", request.normalizedDifficulty());
        payload.put("goal", request.goal() == null ? "" : request.goal());
        payload.put("student_profile", studentProfile == null ? Map.of() : studentProfile);
        return localCoursePayload(payload);
    }

    private void pauseResourcePolling(String taskId) {
        try {
            Thread.sleep(resourceTaskPollMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            try {
                postForMap("/tasks/" + taskId + "/cancel", Map.of());
            } catch (ExternalServiceException ignored) {
                log.warn("ai_resource_task cancel_after_interrupt task_id={} status=failed", taskId);
            }
            throw new ExternalServiceException("AI 服务", "资源任务等待被中断", exception);
        }
    }

    public Map<String, Object> searchKnowledge(KnowledgeSearchRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("course_id", request.resolvedCourseId());
        payload.put("query", request.query());
        payload.put("top_k", request.topK() == null ? 5 : request.topK());
        return postForMap("/kb/search", localCoursePayload(payload));
    }

    public KnowledgeIngestResult ingestKnowledgeDocument(KnowledgeDocumentPayload document) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("document_id", document.documentId());
        payload.put("course_id", document.courseId());
        payload.put("filename", document.filename());
        payload.put("content_type", document.contentType());
        payload.put("content_base64", Base64.getEncoder().encodeToString(document.contentBytes()));
        payload.put("source", document.requirePdfPages() ? document.filename() : document.objectKey());
        if (document.knowledgePointId() != null) {
            payload.put("knowledge_point_id", document.knowledgePointId());
            payload.put("knowledge_point", document.knowledgePoint());
        }
        if (document.requirePdfPages()) payload.put("require_pdf_pages", true);
        Map<String, Object> response;
        if (document.requirePdfPages()) {
            try {
                response = restClient.post().uri("/kb/ingest").body(payload).retrieve().body(MAP_RESPONSE);
            } catch (RestClientResponseException exception) {
                if (exception.getStatusCode().value() == 422) {
                    String reason = "无法解析 PDF。请确认文件未加密、包含可复制文本，且不超过 100 页和文本量上限。";
                    return new KnowledgeIngestResult(null, "failed", "failed", List.of(),
                            new AiSafety(false, "unknown", List.of(), List.of(), 0.0), "not_reviewed", reason);
                }
                throw new ExternalServiceException("AI 服务", "课程 PDF 索引暂不可用");
            } catch (RestClientException exception) {
                throw new ExternalServiceException("AI 服务", "课程 PDF 索引暂不可用");
            }
            if (response == null) throw new ExternalServiceException("AI 服务", "课程 PDF 解析未返回结果");
        } else {
            response = postForMap("/kb/ingest", payload);
        }
        return new KnowledgeIngestResult(
                asString(response.get("task_id")),
                asString(first(response, "parse_status", "parseStatus")),
                asString(first(response, "index_status", "indexStatus")),
                knowledgeChunks(first(response, "chunks")),
                aiSafety(first(response, "safety")),
                asString(first(response, "safety_status", "safetyStatus")),
                asString(first(response, "index_error", "indexError")));
    }

    public KnowledgeVectorDeleteResult deleteKnowledgeDocumentVectors(long documentId, long courseId) {
        String uri = "/kb/documents/" + documentId + "/vectors?course_id=" + courseId;
        try {
            Map<String, Object> response = restClient.delete().uri(uri).retrieve().body(MAP_RESPONSE);
            Integer deleted = response == null ? null : asInteger(response.get("deleted_chunks"));
            if (deleted == null || deleted < 0
                    || !String.valueOf(documentId).equals(asString(response.get("document_id")))
                    || !String.valueOf(courseId).equals(asString(response.get("course_id")))) {
                throw new ExternalServiceException("AI 服务", "向量撤回接口未返回匹配的删除确认");
            }
            return new KnowledgeVectorDeleteResult(documentId, courseId, deleted);
        } catch (RestClientException exception) {
            throw new ExternalServiceException("AI 服务", "撤回文档向量失败: " + documentId, exception);
        }
    }

    public Map<String, Object> extractProfile(ProfileChatRequest request) {
        return extractProfile(request, "demo");
    }

    public Map<String, Object> extractProfile(ProfileChatRequest request, String studentId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("student_id", studentId == null || studentId.isBlank() ? "demo" : studentId);
        payload.put("message", request.message());
        payload.put("course_ids", request.courseIds() == null ? List.of() : request.courseIds());
        return postForMap("/profile/extract", payload);
    }

    public Map<String, Object> generatePath(
            LearningPathGenerateRequest request, Map<String, Object> studentProfile) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("course_ids", request == null || request.courseIds() == null ? List.of(1, 2) : request.courseIds());
        payload.put("target", request == null ? "掌握核心知识点" : request.target());
        payload.put("days", request == null || request.days() == null ? 14 : request.days());
        payload.put("daily_minutes", request == null || request.dailyMinutes() == null ? 40 : request.dailyMinutes());
        payload.put("student_profile", studentProfile == null ? Map.of() : studentProfile);
        return postForMap("/path/generate", localCoursePayload(payload));
    }

    public Map<String, Object> replanPath(Map<String, Object> payload) {
        return postForMap("/path/replan", localCoursePayload(payload));
    }

    public Map<String, Object> chatTutor(TutorChatRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("course_id", request.courseId() == null ? 1 : request.courseId());
        payload.put("question", request.normalizedQuestion());
        payload.put("answer_mode", request.normalizedMode());
        return postForMap("/tutor/chat", localCoursePayload(payload));
    }

    public Map<String, Object> generateQuiz(
            QuizGenerateRequest request,
            List<String> knowledgePoints,
            Map<String, Object> studentProfile) {
        Map<String, Object> payload = new LinkedHashMap<>();
        int courseId = request.courseId() == null ? 1 : request.courseId();
        payload.put("course_id", courseId);
        payload.put("knowledge_point_ids", request.knowledgePointIds() == null ? List.of() : request.knowledgePointIds());
        payload.put("knowledge_points", scopedTopicNames(courseId, request.knowledgePointIds(), knowledgePoints));
        payload.put("difficulty", request.difficulty() == null ? "basic" : request.difficulty());
        payload.put("question_count", request.questionCount() == null ? 3 : request.questionCount());
        payload.put("student_profile", studentProfile == null ? Map.of() : studentProfile);
        return postForMap("/quiz/generate", localCoursePayload(payload));
    }

    public Map<String, Object> analyzeEvaluation(Map<String, Object> payload) {
        return postForMap("/evaluation/analyze", localCoursePayload(payload));
    }

    public Map<String, Object> regressResourceQuality(Map<String, Object> payload) {
        return postForMap("/resource/quality/regression", localCoursePayload(payload));
    }

    public Map<String, Object> repairResource(Map<String, Object> payload) {
        return postForMap("/resource/repair", localCoursePayload(payload));
    }

    private Map<String, Object> localCoursePayload(Map<String, Object> payload) {
        if (payload == null) return Map.of();
        if (courseAccess == null) return payload;
        Object candidate = payload.get("course_ids");
        boolean classCourse = isClassCourse(payload.get("course_id"))
                || (candidate instanceof List<?> list && list.stream().anyMatch(this::isClassCourse));
        if (!classCourse) return payload;
        Map<String, Object> scoped = new LinkedHashMap<>(payload);
        scoped.put("local_only", true);
        return scoped;
    }

    private boolean isClassCourse(Object value) {
        return value instanceof Number id && courseAccess.isClassCourse(id.longValue());
    }

    private List<String> scopedTopicNames(Integer courseId, List<Integer> pointIds, List<String> names) {
        if (courseAccess != null && pointIds != null && !pointIds.isEmpty() && isClassCourse(courseId)) {
            return courseCatalog.requireTopicNames(courseId, pointIds);
        }
        return names == null ? List.of() : names;
    }

    public PdfIndex indexPersonalPdf(byte[] bytes) {
        return personalPdfRequest("/internal/personal-pdfs/index", MediaType.APPLICATION_PDF, bytes, PdfIndex.class);
    }

    public PdfAnswer askPersonalPdf(String question, List<PdfPage> pages) {
        return personalPdfRequest("/internal/personal-pdfs/ask", MediaType.APPLICATION_JSON,
                Map.of("question", question, "pages", pages), PdfAnswer.class);
    }

    private <T> T personalPdfRequest(String path, MediaType mediaType, Object payload, Class<T> type) {
        try {
            T response = restClient.post().uri(path).contentType(mediaType).body(payload).retrieve().body(type);
            if (response != null) {
                return response;
            }
        } catch (RestClientException exception) {
            // Avoid embedding private page text or questions from downstream errors in logs.
            log.warn("personal_pdf_call_failed path={} error_type={}", path, exception.getClass().getSimpleName());
        }
        throw new ExternalServiceException("个人 PDF", "处理服务暂不可用，请稍后重试");
    }

    public Map<String, Object> evaluationReport() {
        return getForMap("/evaluation/report");
    }

    public Map<String, Object> readiness() {
        return getForMap("/readiness");
    }

    private Map<String, Object> postForMap(String uri, Map<String, Object> payload) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> response =
                    restClient.post().uri(uri).body(payload).retrieve().body(MAP_RESPONSE);
            if (response == null) {
                throw new ExternalServiceException("AI 服务", uri + " 返回空响应");
            }
            log.info("ai_call method=POST uri={} duration_ms={} status=success", uri, System.currentTimeMillis() - start);
            return response;
        } catch (RestClientException exception) {
            log.warn(
                    "ai_call method=POST uri={} duration_ms={} status=failed error={}",
                    uri,
                    System.currentTimeMillis() - start,
                    exception.getMessage());
            throw new ExternalServiceException("AI 服务", uri + " 不可用", exception);
        }
    }

    private Map<String, Object> getForMap(String uri) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> response = restClient.get().uri(uri).retrieve().body(MAP_RESPONSE);
            if (response == null) {
                throw new ExternalServiceException("AI 服务", uri + " 返回空响应");
            }
            log.info("ai_call method=GET uri={} duration_ms={} status=success", uri, System.currentTimeMillis() - start);
            return response;
        } catch (RestClientException exception) {
            log.warn(
                    "ai_call method=GET uri={} duration_ms={} status=failed error={}",
                    uri,
                    System.currentTimeMillis() - start,
                    exception.getMessage());
            throw new ExternalServiceException("AI 服务", uri + " 不可用", exception);
        }
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? null : Integer.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private AiResourceGenerateResult aiResourceResult(Map<String, Object> response, String fallbackTaskId) {
        return new AiResourceGenerateResult(
                asString(response.get("task_id")) == null ? fallbackTaskId : asString(response.get("task_id")),
                asString(response.get("status")),
                asStringList(response.get("planned_agents")),
                asString(response.get("safety_status")),
                aiResources(response.get("resources")),
                aiEvidence(response.get("evidence")),
                aiSafety(response.get("safety")),
                asStringMap(response.get("model_runtime")));
    }

    private List<AiGeneratedResource> aiResources(Object value) {
        List<AiGeneratedResource> resources = new ArrayList<>();
        for (Map<String, Object> item : asMapList(value)) {
            resources.add(new AiGeneratedResource(
                    asString(first(item, "title")),
                    asString(first(item, "resource_type", "resourceType", "type")),
                    asString(first(item, "content_format", "contentFormat")),
                    asString(first(item, "content")),
                    asString(first(item, "summary")),
                    asString(first(item, "difficulty")),
                    asStringList(first(item, "knowledge_points", "knowledgePoints")),
                    asString(first(item, "personalized_reason", "personalizedReason")),
                    asInteger(first(item, "estimated_minutes", "estimatedMinutes")),
                    asString(first(item, "profile_fingerprint", "profileFingerprint")),
                    asStringMap(first(item, "quality_evaluation", "qualityEvaluation"))));
        }
        return resources;
    }

    private List<AiEvidence> aiEvidence(Object value) {
        List<AiEvidence> evidence = new ArrayList<>();
        for (Map<String, Object> item : asMapList(value)) {
            evidence.add(new AiEvidence(
                    asString(first(item, "chunk_id", "chunkId")),
                    asString(first(item, "title")),
                    asString(first(item, "content")),
                    asDouble(first(item, "score")),
                    asString(first(item, "source")),
                    item.get("document_id") instanceof Number id ? id.longValue() : null,
                    asInteger(first(item, "page"))));
        }
        return evidence;
    }

    private List<KnowledgeChunk> knowledgeChunks(Object value) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (Map<String, Object> item : asMapList(value)) {
            chunks.add(new KnowledgeChunk(
                    asString(first(item, "chunk_id", "chunkId")),
                    asString(first(item, "title")),
                    asString(first(item, "content")),
                    asDouble(first(item, "score")),
                    asString(first(item, "source"))));
        }
        return chunks;
    }

    private AiSafety aiSafety(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return new AiSafety(false, "unknown", List.of("AI 服务未返回 SafetyAgent 结果"), List.of(), 0.0);
        }
        Map<String, Object> map = normalizeMap(rawMap);
        return new AiSafety(
                Boolean.TRUE.equals(first(map, "passed")),
                asString(first(map, "risk_level", "riskLevel")),
                asStringList(first(map, "issues")),
                asStringList(first(map, "suggestions")),
                asDouble(first(map, "confidence")));
    }

    private List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::normalizeMap)
                .toList();
    }

    private Map<String, Object> normalizeMap(Map<?, ?> map) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        map.forEach((key, value) -> normalized.put(String.valueOf(key), value));
        return normalized;
    }

    private Map<String, Object> asStringMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return normalizeMap(map);
        }
        return new LinkedHashMap<>();
    }

    private Object first(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0.0 : Double.parseDouble(value.toString());
        } catch (NumberFormatException exception) {
            return 0.0;
        }
    }

    @FunctionalInterface
    public interface AiTaskProgressListener {
        void onSnapshot(Map<String, Object> snapshot);
    }

    public record AiResourceGenerateResult(
            String taskId,
            String status,
            List<String> plannedAgents,
            String safetyStatus,
            List<AiGeneratedResource> resources,
            List<AiEvidence> evidence,
            AiSafety safety,
            Map<String, Object> modelRuntime) {
        public AiResourceGenerateResult(
                String taskId,
                String status,
                List<String> plannedAgents,
                String safetyStatus,
                List<AiGeneratedResource> resources,
                List<AiEvidence> evidence,
                AiSafety safety) {
            this(taskId, status, plannedAgents, safetyStatus, resources, evidence, safety, Map.of());
        }
    }

    public record AiGeneratedResource(
            String title,
            String resourceType,
            String contentFormat,
            String content,
            String summary,
            String difficulty,
            List<String> knowledgePoints,
            String personalizedReason,
            Integer estimatedMinutes,
            String profileFingerprint,
            Map<String, Object> qualityEvaluation) {
        public AiGeneratedResource(
                String title,
                String resourceType,
                String contentFormat,
                String content,
                String summary,
                String difficulty,
                List<String> knowledgePoints,
                String personalizedReason,
                Integer estimatedMinutes,
                String profileFingerprint) {
            this(
                    title,
                    resourceType,
                    contentFormat,
                    content,
                    summary,
                    difficulty,
                    knowledgePoints,
                    personalizedReason,
                    estimatedMinutes,
                    profileFingerprint,
                    Map.of(
                            "evaluator_version", "legacy-test-fixture",
                            "total_score", 100,
                            "grade", "A",
                            "gate_passed", true,
                            "dimensions", Map.of()));
        }
    }

    public record AiEvidence(String chunkId, String title, String content, double score, String source,
                             Long documentId, Integer page) {
        public AiEvidence(String chunkId, String title, String content, double score, String source) {
            this(chunkId, title, content, score, source, null, null);
        }
    }

    public record AiSafety(
            boolean passed,
            String riskLevel,
            List<String> issues,
            List<String> suggestions,
            double confidence) {}

    public record KnowledgeDocumentPayload(
            long documentId,
            int courseId,
            String filename,
            String contentType,
            byte[] contentBytes,
            String objectKey,
            Long knowledgePointId,
            String knowledgePoint,
            boolean requirePdfPages) {
        public KnowledgeDocumentPayload(long documentId, int courseId, String filename, String contentType,
                                        byte[] contentBytes, String objectKey) {
            this(documentId, courseId, filename, contentType, contentBytes, objectKey, null, null, false);
        }
        public String content() {
            return new String(contentBytes == null ? new byte[0] : contentBytes, StandardCharsets.UTF_8);
        }
    }

    public record KnowledgeChunk(String chunkId, String title, String content, double score, String source) {}

    public record KnowledgeIngestResult(
            String taskId,
            String parseStatus,
            String indexStatus,
            List<KnowledgeChunk> chunks,
            AiSafety safety,
            String safetyStatus,
            String indexError) {
        public KnowledgeIngestResult(String taskId, String parseStatus, String indexStatus,
                List<KnowledgeChunk> chunks, AiSafety safety) {
            this(taskId, parseStatus, indexStatus, chunks, safety,
                    safety != null && safety.passed() ? "passed" : "review_required", "");
        }
    }

    public record KnowledgeVectorDeleteResult(long documentId, long courseId, int deletedChunks) {}
}
