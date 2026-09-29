package com.edupath.resource;

import com.edupath.ai.AiAgentClient.AiGeneratedResource;
import com.edupath.ai.AiAgentClient.AiResourceGenerateResult;
import com.edupath.auth.AuthContext;
import com.edupath.common.ExternalServiceException;
import com.edupath.course.CourseCatalogService;
import com.edupath.learning.LearningEventService;
import com.edupath.learning.LearningEventService.LearningEventCommand;
import com.edupath.storage.ObjectStorageService;
import com.edupath.storage.ObjectStorageService.SignedUrl;
import com.edupath.storage.ObjectStorageService.StoredObject;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ResourceService {

    private final CourseCatalogService courseCatalogService;
    private final ObjectStorageService objectStorageService;
    private final ResourceRepository resourceRepository;
    private final LearningEventService learningEventService;

    public ResourceService(
            CourseCatalogService courseCatalogService,
            ObjectStorageService objectStorageService,
            ResourceRepository resourceRepository,
            LearningEventService learningEventService) {
        this.courseCatalogService = courseCatalogService;
        this.objectStorageService = objectStorageService;
        this.resourceRepository = resourceRepository;
        this.learningEventService = learningEventService;
    }

    public ResourceListResponse listResources(Integer courseId, String type, int page, int size) {
        return listResources(courseId, type, null, null, "created_at", page, size);
    }

    public ResourceListResponse listResources(
            Integer courseId, String type, String status, String keyword, String sort, int page, int size) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.max(Math.min(size, 50), 1);
        Long ownerUserId = currentStudentOwnerId();
        List<ResourceSummary> items = resourceRepository
                .list(courseId, type, status, keyword, sort, ownerUserId, normalizedPage, normalizedSize)
                .stream()
                .map(this::toSummary)
                .toList();
        long total = resourceRepository.count(courseId, type, status, keyword, ownerUserId);
        return new ResourceListResponse(items, normalizedPage, normalizedSize, total);
    }

    private Long currentStudentOwnerId() {
        var principal = AuthContext.current();
        return principal != null && "student".equals(principal.role()) ? principal.userId() : null;
    }

    public ResourceDetail getResource(String resourceId) {
        return resourceRepository
                .find(resourceId)
                .map(this::toDetail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在: " + resourceId));
    }

    @Transactional
    public ResourceInteractionResult recordInteraction(
            String resourceId, ResourceInteractionRequest request) {
        ResourceRepository.ResourceRow row = resourceRepository.find(resourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在: " + resourceId));
        var principal = AuthContext.requirePrincipal();
        if ("student".equals(principal.role()) && row.ownerUserId() != principal.userId()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不能操作其他学生的资源");
        }
        String action = request == null || request.action() == null ? "view" : request.action().trim();
        if (!List.of("view", "start", "complete", "skip", "favorite", "rate").contains(action)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "资源行为类型不合法");
        }
        Integer rating = request == null ? null : request.rating();
        Integer progress = request == null ? null : request.progressPercent();
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评分必须在 1 到 5 之间");
        }
        if (progress != null && (progress < 0 || progress > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "学习进度必须在 0 到 100 之间");
        }
        resourceRepository.addInteraction(row.resourceId(), principal.userId(), action, rating, progress);
        int weight = behaviorWeight(row.resourceType(), action);
        int score = behaviorScore(row.resourceType(), action, progress);
        String baseKey = request != null && request.eventId() != null && !request.eventId().isBlank()
                ? request.eventId().trim()
                : String.join(
                        ":",
                        "resource",
                        row.resourceId(),
                        action,
                        String.valueOf(progress == null ? "none" : progress),
                        String.valueOf(rating == null ? "none" : rating));
        String eventType = "codelab".equals(row.resourceType()) ? "code_lab" : "resource_reading";
        List<LearningEventCommand> commands = row.knowledgePoints().stream()
                .map(point -> new LearningEventCommand(
                        eventType,
                        row.courseId(),
                        point,
                        "resource",
                        row.resourceId(),
                        action,
                        progress,
                        null,
                        weight,
                        score,
                        baseKey + ":" + Integer.toUnsignedString(point.hashCode()),
                        Map.of(
                                "resource_type", row.resourceType(),
                                "title", row.title())))
                .toList();
        LearningEventService.LearningUpdateResult learningUpdate = learningEventService.record(commands);
        ResourceRepository.InteractionSummary summary =
                resourceRepository.interactionSummary(row.resourceId(), principal.userId());
        return new ResourceInteractionResult(
                summary.interactionCount(),
                summary.progressPercent(),
                summary.rating(),
                summary.lastInteractionAt(),
                learningUpdate);
    }

    private int behaviorWeight(String resourceType, String action) {
        if ("complete".equals(action)) {
            return "codelab".equals(resourceType) ? 3 : 2;
        }
        if ("start".equals(action)) {
            return 1;
        }
        return 0;
    }

    private int behaviorScore(String resourceType, String action, Integer progress) {
        if ("complete".equals(action)) {
            return "codelab".equals(resourceType) ? 80 : 70;
        }
        if ("start".equals(action)) {
            return Math.min(65, 50 + Math.max(0, progress == null ? 0 : progress) / 5);
        }
        return 0;
    }

    public ResourceAccess access(String resourceId) {
        ResourceRepository.ResourceRow row = resourceRepository
                .find(resourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在: " + resourceId));
        return new ResourceAccess(row.courseId(), row.ownerUserId());
    }

    @Transactional
    public ResourceBatchResult generateFromTask(ResourceTaskRequest request, AiResourceGenerateResult aiResult) {
        if (aiResult == null || aiResult.resources() == null || aiResult.resources().isEmpty()) {
            throw new ExternalServiceException("AI 服务", "资源生成结果为空，已停止写入资源记录");
        }
        List<String> requestedPoints = courseCatalogService.resolveKnowledgePointNames(
                request.courseId(), request.knowledgePointIds(), request.knowledgePoints());
        List<EvidenceRef> evidence = aiResult.evidence().stream()
                .map(item -> new EvidenceRef(
                        item.chunkId(), item.title(), item.content(), item.score(), item.source()))
                .toList();
        SafetyReview safety = new SafetyReview(
                aiResult.safety().passed(),
                aiResult.safety().riskLevel(),
                aiResult.safety().issues(),
                aiResult.safety().suggestions(),
                aiResult.safety().confidence());

        List<ResourceSummary> created = new ArrayList<>();
        String generationMode = aiResult.modelRuntime()
                .getOrDefault("mode", "deterministic_fallback")
                .toString();
        for (AiGeneratedResource generated : aiResult.resources()) {
            ResourceRepository.ResourceCreate create =
                    toCreate(request, requestedPoints, generated, evidence, safety, aiResult.taskId(), generationMode);
            resourceRepository.insert(create);
            created.add(toSummary(resourceRepository.find(create.resourceId()).orElseThrow()));
        }
        return new ResourceBatchResult(
                created,
                generationMode,
                aiResult.taskId(),
                evidence,
                safety,
                aiResult.modelRuntime());
    }

    public ResourceSummary updateStatus(String resourceId, ResourceStatusUpdateRequest request) {
        String status = request == null || request.status() == null || request.status().isBlank()
                ? "draft"
                : request.status();
        if (!List.of("draft", "published", "archived").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "资源状态不合法");
        }
        int updated = resourceRepository.updateStatus(resourceId, status);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在: " + resourceId);
        }
        return toSummary(resourceRepository.find(resourceId).orElseThrow());
    }

    public ResourceSummary archive(String resourceId) {
        return updateStatus(resourceId, new ResourceStatusUpdateRequest("archived"));
    }

    public void delete(String resourceId) {
        ResourceRepository.ResourceRow row = resourceRepository
                .find(resourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在: " + resourceId));
        int updated = resourceRepository.softDelete(resourceId);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在: " + resourceId);
        }
        objectStorageService.delete(row.objectKey());
    }

    @Transactional
    public ResourceSummary updateResource(String resourceId, ResourceUpdateRequest request) {
        ResourceRepository.ResourceRow before = resourceRepository
                .find(resourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在: " + resourceId));
        resourceRepository.addVersion(before, AuthContext.currentUsername());
        StoredObject storedObject = null;
        if (request != null && request.content() != null) {
            storedObject = objectStorageService.store(
                    "resources",
                    before.resourceId() + "." + extensionFor(before.contentFormat()),
                    before.contentFormat(),
                    request.content().getBytes(StandardCharsets.UTF_8));
        }
        int updated = resourceRepository.updateContent(
                resourceId,
                request == null ? null : request.title(),
                request == null ? null : request.summary(),
                request == null ? null : request.content(),
                request == null ? null : request.tags(),
                storedObject == null ? null : storedObject.objectKey(),
                storedObject == null ? null : storedObject.objectUrl(),
                storedObject == null ? null : storedObject.storageStatus());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在: " + resourceId);
        }
        return toSummary(resourceRepository.find(resourceId).orElseThrow());
    }

    public ResourceVersionsResponse versions(String resourceId) {
        return new ResourceVersionsResponse(resourceRepository.versions(resourceId));
    }

    public ResourceDownloadUrl downloadUrl(String resourceId) {
        ResourceRepository.ResourceRow row = resourceRepository
                .find(resourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在: " + resourceId));
        SignedUrl signed = objectStorageService.signedDownloadUrl(row.objectKey());
        return new ResourceDownloadUrl(row.resourceId(), signed.url(), signed.expiresAt());
    }

    private ResourceRepository.ResourceCreate toCreate(
            ResourceTaskRequest request,
            List<String> requestedPoints,
            AiGeneratedResource generated,
            List<EvidenceRef> evidence,
            SafetyReview safety,
            String sourceTaskId,
            String generationMode) {
        String topic = String.join("、", requestedPoints);
        String resourceType = generated.resourceType() == null || generated.resourceType().isBlank()
                ? request.normalizedResourceTypes().get(0)
                : generated.resourceType();
        String contentFormat = generated.contentFormat() == null || generated.contentFormat().isBlank()
                ? "markdown"
                : generated.contentFormat();
        String content = generated.content() == null ? "" : generated.content();
        String resourceId = formatResourceId();
        StoredObject storedObject =
                objectStorageService.store(
                        "resources",
                        resourceId + "." + extensionFor(contentFormat),
                        contentFormat,
                        content.getBytes(StandardCharsets.UTF_8));
        return new ResourceRepository.ResourceCreate(
                resourceId,
                generated.title() == null || generated.title().isBlank()
                        ? titleFor(resourceType, topic)
                        : generated.title(),
                resourceType,
                request.courseId(),
                generated.knowledgePoints() == null || generated.knowledgePoints().isEmpty()
                        ? requestedPoints
                        : generated.knowledgePoints(),
                generated.difficulty() == null || generated.difficulty().isBlank()
                        ? request.normalizedDifficulty()
                        : generated.difficulty(),
                generated.summary() == null || generated.summary().isBlank()
                        ? summaryFor(resourceType, topic)
                        : generated.summary(),
                safety.passed() && qualityGatePassed(generated.qualityEvaluation()) ? "published" : "draft",
                contentFormat,
                content,
                evidence,
                safety,
                storedObject.objectKey(),
                storedObject.objectUrl(),
                storedObject.storageStatus(),
                List.of(),
                AuthContext.current() == null ? null : AuthContext.current().userId(),
                sourceTaskId,
                generated.personalizedReason(),
                generated.estimatedMinutes(),
                generated.profileFingerprint(),
                generated.qualityEvaluation(),
                generationMode);
    }

    private ResourceSummary toSummary(ResourceRepository.ResourceRow row) {
        return new ResourceSummary(
                row.id(),
                row.resourceId(),
                row.title(),
                row.resourceType(),
                row.resourceType(),
                row.courseId(),
                row.knowledgePoints(),
                row.difficulty(),
                row.summary(),
                row.status(),
                row.createdAt(),
                row.objectKey(),
                row.objectUrl(),
                row.storageStatus(),
                row.personalizedReason(),
                row.estimatedMinutes(),
                row.profileFingerprint(),
                row.qualityEvaluation(),
                row.generationMode());
    }

    private ResourceDetail toDetail(ResourceRepository.ResourceRow row) {
        return new ResourceDetail(
                row.id(),
                row.resourceId(),
                row.title(),
                row.resourceType(),
                row.resourceType(),
                row.courseId(),
                row.knowledgePoints(),
                row.difficulty(),
                row.summary(),
                row.status(),
                row.createdAt(),
                row.contentFormat(),
                row.content(),
                row.evidence().stream().map(this::toEvidence).toList(),
                toSafety(row.safety()),
                row.objectKey(),
                row.objectUrl(),
                row.storageStatus(),
                row.personalizedReason(),
                row.estimatedMinutes(),
                row.profileFingerprint(),
                row.qualityEvaluation(),
                row.generationMode());
    }

    private boolean qualityGatePassed(Map<String, Object> qualityEvaluation) {
        Object value = qualityEvaluation == null ? null : qualityEvaluation.get("gate_passed");
        if (value == null && qualityEvaluation != null) {
            value = qualityEvaluation.get("gatePassed");
        }
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private EvidenceRef toEvidence(Map<String, Object> map) {
        return new EvidenceRef(
                text(map, "chunk_id", "chunkId"),
                text(map, "title"),
                text(map, "content"),
                score(map.get("score")),
                text(map, "source"));
    }

    private SafetyReview toSafety(Map<String, Object> map) {
        return new SafetyReview(
                Boolean.parseBoolean(String.valueOf(map.getOrDefault("passed", "false"))),
                text(map, "risk_level", "riskLevel"),
                stringList(map.get("issues")),
                stringList(map.get("suggestions")),
                score(map.get("confidence")));
    }

    private String text(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return "";
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private double score(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0.0 : Double.parseDouble(value.toString());
        } catch (NumberFormatException exception) {
            return 0.0;
        }
    }

    private String titleFor(String resourceType, String topic) {
        return switch (resourceType) {
            case "mindmap" -> topic + "知识图谱";
            case "quiz" -> topic + "分层练习题";
            case "codelab" -> topic + "代码实验";
            case "animation_script" -> topic + "动画脚本";
            case "flowchart" -> topic + "流程图";
            case "reading" -> topic + "拓展阅读";
            default -> topic + "个性化讲义";
        };
    }

    private String summaryFor(String resourceType, String topic) {
        return switch (resourceType) {
            case "mindmap" -> "梳理 " + topic + " 的概念、前置知识和易错关系。";
            case "quiz" -> "按基础、迁移、综合三个层次训练 " + topic + "。";
            case "codelab" -> "通过可运行代码实验掌握 " + topic + "。";
            case "animation_script" -> "用逐帧脚本展示 " + topic + " 的动态过程。";
            case "flowchart" -> "把 " + topic + " 的判断流程拆成可追踪步骤。";
            case "reading" -> "补充 " + topic + " 的教材阅读和例题延伸。";
            default -> "结合画像和 RAG 证据生成 " + topic + " 的个性化讲义。";
        };
    }

    private String formatResourceId() {
        return "res_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
    }

    private String extensionFor(String contentFormat) {
        return "json".equalsIgnoreCase(contentFormat) ? "json" : "md";
    }

    private double roundScore(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public record ResourceListResponse(List<ResourceSummary> items, int page, int size, long total) {}

    public record ResourceSummary(
            long id,
            String resourceId,
            String title,
            String type,
            String resourceType,
            int courseId,
            List<String> knowledgePoints,
            String difficulty,
            String summary,
            String status,
            OffsetDateTime createdAt,
            String objectKey,
            String objectUrl,
            String storageStatus,
            String personalizedReason,
            Integer estimatedMinutes,
            String profileFingerprint,
            Map<String, Object> qualityEvaluation,
            String generationMode) {}

    public record ResourceDetail(
            long id,
            String resourceId,
            String title,
            String type,
            String resourceType,
            int courseId,
            List<String> knowledgePoints,
            String difficulty,
            String summary,
            String status,
            OffsetDateTime createdAt,
            String contentFormat,
            String content,
            List<EvidenceRef> evidence,
            SafetyReview safety,
            String objectKey,
            String objectUrl,
            String storageStatus,
            String personalizedReason,
            Integer estimatedMinutes,
            String profileFingerprint,
            Map<String, Object> qualityEvaluation,
            String generationMode) {}

    public record EvidenceRef(String chunkId, String title, String content, double score, String source) {}

    public record SafetyReview(
            boolean passed,
            String riskLevel,
            List<String> issues,
            List<String> suggestions,
            double confidence) {}

    public record ResourceBatchResult(
            List<ResourceSummary> resources,
            String generationMode,
            String aiTaskId,
            List<EvidenceRef> evidence,
            SafetyReview safety,
            Map<String, Object> modelRuntime) {}

    public record ResourceStatusUpdateRequest(String status) {}

    public record ResourceUpdateRequest(String title, String summary, String content, List<String> tags) {}

    public record ResourceInteractionRequest(
            String action, Integer rating, Integer progressPercent, String eventId) {}

    public record ResourceInteractionResult(
            int interactionCount,
            Integer progressPercent,
            Integer rating,
            OffsetDateTime lastInteractionAt,
            LearningEventService.LearningUpdateResult learningUpdate) {}

    public record ResourceVersionsResponse(List<ResourceRepository.ResourceVersionRow> items) {}

    public record ResourceDownloadUrl(String resourceId, String url, OffsetDateTime expiresAt) {}

    public record ResourceAccess(int courseId, long ownerUserId) {}
}
