package com.edupath.resource;

import com.edupath.agenttask.AgentTaskService;
import com.edupath.ai.AiAgentClient;
import com.edupath.auth.AuthContext;
import com.edupath.auth.RoleGuard;
import com.edupath.storage.ObjectStorageService;
import com.edupath.storage.ObjectStorageService.StoredObject;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ResourceQualityRepairService {

    private static final List<String> STATUSES =
            List.of(
                    "pending_review", "approved", "rejected", "in_progress", "completed", "failed",
                    "published", "rolled_back");

    private final ResourceQualityRepairRepository repairRepository;
    private final ResourceRepository resourceRepository;
    private final AgentTaskService agentTaskService;
    private final AiAgentClient aiAgentClient;
    private final RoleGuard roleGuard;
    private final ObjectStorageService objectStorageService;

    public ResourceQualityRepairService(
            ResourceQualityRepairRepository repairRepository,
            ResourceRepository resourceRepository,
            AgentTaskService agentTaskService,
            AiAgentClient aiAgentClient,
            RoleGuard roleGuard,
            ObjectStorageService objectStorageService) {
        this.repairRepository = repairRepository;
        this.resourceRepository = resourceRepository;
        this.agentTaskService = agentTaskService;
        this.aiAgentClient = aiAgentClient;
        this.roleGuard = roleGuard;
        this.objectStorageService = objectStorageService;
    }

    public RepairListResponse list(Integer courseId, String status, int page, int size) {
        var principal = AuthContext.requirePrincipal();
        String normalizedStatus = normalizedStatus(status);
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.max(1, Math.min(size, 20));
        Long ownerUserId = "student".equals(principal.role()) ? principal.userId() : null;
        Long managerUserId = "teacher".equals(principal.role()) ? principal.userId() : null;
        boolean canReview = "teacher".equals(principal.role()) || "admin".equals(principal.role());
        List<RepairItem> items = repairRepository
                .list(courseId, normalizedStatus, ownerUserId, managerUserId, normalizedPage, normalizedSize)
                .stream()
                .map(row -> toItem(row, canReview))
                .toList();
        long total = repairRepository.count(courseId, normalizedStatus, ownerUserId, managerUserId);
        return new RepairListResponse(items, normalizedPage, normalizedSize, total);
    }

    public RepairDecisionResult decide(String repairId, RepairDecisionRequest request) {
        var principal = AuthContext.requirePrincipal();
        ResourceQualityRepairRepository.RepairRow row = repairRepository.find(repairId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "修复队列项不存在: " + repairId));
        roleGuard.requireCourseManager(row.courseId());
        String decision = request == null || request.decision() == null
                ? ""
                : request.decision().trim().toLowerCase();
        if (!List.of("approve", "reject").contains(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审核决定必须为 approve 或 reject");
        }
        String note = request.note() == null ? "" : request.note().trim();
        if ("reject".equals(decision) && note.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "驳回修复项时必须填写原因");
        }
        if (note.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审核备注不能超过 500 字");
        }
        String status = "approve".equals(decision) ? "approved" : "rejected";
        String resolvedNote = note.isBlank() ? "人工审核通过，等待受控修复执行器处理。" : note;
        int updated = repairRepository.decide(repairId, status, principal.userId(), resolvedNote);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该修复项已完成审核，不能重复操作");
        }
        RepairItem item = toItem(repairRepository.find(repairId).orElseThrow(), true);
        return new RepairDecisionResult(
                item,
                "approved".equals(status) ? "ready_for_controlled_repair" : "closed",
                "本阶段审核不会直接修改资源内容或发布状态。");
    }

    public RepairExecutionCreated startExecution(String repairId) {
        ResourceQualityRepairRepository.RepairRow row = repairRepository.find(repairId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "修复队列项不存在: " + repairId));
        roleGuard.requireCourseManager(row.courseId());
        if (!"approved".equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有已批准的修复项可以启动执行");
        }
        if (repairRepository.claimForExecution(repairId) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "修复项已被其他执行任务占用");
        }
        String resourceAgent = agentFor(row.resourceType());
        List<AgentTaskService.AgentStepPlan> plans = List.of(
                new AgentTaskService.AgentStepPlan(
                        "RepairPlannerAgent", "正在锁定回归维度和原始证据", "已生成单资源定向修复约束"),
                new AgentTaskService.AgentStepPlan(
                        resourceAgent, "正在依据回归反馈生成候选版本", "已生成未发布候选版本"),
                new AgentTaskService.AgentStepPlan(
                        "SafetyAgent", "正在复核候选版本的证据一致性与安全性", "已完成候选版本安全复核"),
                new AgentTaskService.AgentStepPlan(
                        "QualityEvaluator", "正在重新执行五维质量门禁", "已完成候选版本质量判定"));
        try {
            AgentTaskService.TaskCreatedResponse created = agentTaskService.createObservableWorkflowTask(
                    "quality_repair",
                    plans,
                    "quality_repair",
                    new RepairExecutionRequest(repairId),
                    progress -> executeRepair(repairId, resourceAgent, progress));
            repairRepository.bindExecutionTask(repairId, created.taskId());
            return new RepairExecutionCreated(repairId, created.taskId(), "in_progress");
        } catch (RuntimeException exception) {
            repairRepository.releaseExecutionClaim(repairId);
            throw exception;
        }
    }

    private RepairExecutionResult executeRepair(
            String repairId,
            String resourceAgent,
            AgentTaskService.TaskProgress progress) {
        try {
            ResourceQualityRepairRepository.RepairRow repair = repairRepository.find(repairId)
                    .orElseThrow(() -> new IllegalStateException("修复队列项执行期间丢失: " + repairId));
            ResourceRepository.ResourceRow resource = resourceRepository.find(repair.resourceId())
                    .orElseThrow(() -> new IllegalStateException("原资源不存在: " + repair.resourceId()));
            progress.running("RepairPlannerAgent", "正在锁定回归维度和原始证据", 10);
            Map<String, Object> payload = repairPayload(repair, resource);
            progress.success(
                    "RepairPlannerAgent",
                    "已锁定 " + repair.regressedDimensions().size() + " 个回归维度和 "
                            + resource.evidence().size() + " 条原始证据",
                    24);

            progress.running(resourceAgent, "正在依据回归反馈生成候选版本", 32);
            Map<String, Object> response = aiAgentClient.repairResource(payload);
            Map<String, Object> candidate = map(response.get("candidate_resource"));
            Map<String, Object> safety = map(response.get("safety"));
            Map<String, Object> quality = map(response.get("quality_evaluation"));
            Map<String, Object> modelRuntime = map(response.get("model_runtime"));
            if (candidate.isEmpty() || quality.isEmpty() || safety.isEmpty()) {
                throw new IllegalStateException("AI 修复服务未返回完整候选资源、安全审查或质量评测");
            }
            progress.success(resourceAgent, "已生成隔离保存的未发布候选版本", 64);

            progress.running("SafetyAgent", "正在复核候选版本的证据一致性与安全性", 70);
            boolean safetyPassed = bool(safety.get("passed"));
            progress.success(
                    "SafetyAgent",
                    safetyPassed ? "候选版本安全复核通过" : "候选版本需要继续处理安全问题",
                    80);

            progress.running("QualityEvaluator", "正在重新执行五维质量门禁", 84);
            double candidateScore = number(quality.get("total_score"));
            double currentScore = number(repair.currentEvaluation().get("total_score"));
            Object baselineScoreValue = repair.baselineEvaluation().get("total_score");
            double baselineScore = number(baselineScoreValue);
            double recoveryScoreDelta = Math.round((candidateScore - currentScore) * 100.0) / 100.0;
            boolean qualityGatePassed = bool(quality.get("gate_passed"));
            boolean targetDimensionsPassed = bool(response.get("target_dimensions_passed"));
            boolean scoreRecovered = recoveryScoreDelta >= 5.0
                    || (baselineScoreValue != null && candidateScore >= baselineScore);
            boolean publishReady = safetyPassed && qualityGatePassed && targetDimensionsPassed && scoreRecovered;
            String generationMode = String.valueOf(modelRuntime.getOrDefault("mode", "deterministic_fallback"));
            int updated = repairRepository.completeExecution(
                    repairId,
                    progress.taskId(),
                    candidate,
                    safety,
                    quality,
                    modelRuntime,
                    generationMode,
                    publishReady,
                    resource.version());
            if (updated == 0) {
                throw new IllegalStateException("修复项状态已变化，候选版本未写入");
            }
            progress.success(
                    "QualityEvaluator",
                    publishReady ? "候选版本已通过恢复判定，等待人工发布确认" : "候选版本未达到发布恢复条件",
                    96);
            return new RepairExecutionResult(
                    repairId,
                    resource.resourceId(),
                    text(candidate, "title"),
                    text(candidate, "summary"),
                    quality,
                    safety,
                    modelRuntime,
                    recoveryScoreDelta,
                    stringList(response.get("improved_dimensions")),
                    targetDimensionsPassed,
                    publishReady,
                    true,
                    publishReady ? "manual_publish_review" : "inspect_quality_rejection");
        } catch (RuntimeException exception) {
            repairRepository.failExecution(repairId, progress.taskId(), truncate(exception.getMessage(), 1000));
            throw exception;
        }
    }

    public RepairComparison comparison(String repairId) {
        ResourceQualityRepairRepository.RepairRow repair = requiredRepair(repairId);
        roleGuard.requireCourseManager(repair.courseId());
        ResourceRepository.ResourceRow resource = requiredResource(repair.resourceId());
        if (repair.candidateResource().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该修复项尚未生成候选版本");
        }
        boolean stale = repair.candidateBaseVersion() == null
                || resource.version() != repair.candidateBaseVersion();
        return new RepairComparison(
                repair.repairId(),
                repair.resourceId(),
                resource.version(),
                repair.candidateBaseVersion(),
                stale,
                resourceView(resource),
                repair.candidateResource(),
                repair.currentEvaluation(),
                repair.candidateQualityEvaluation(),
                changedFields(resource, repair.candidateResource()),
                !stale && "completed".equals(repair.status()) && repair.publishReady());
    }

    @Transactional
    public RepairPublicationResult publish(String repairId, RepairPublishRequest request) {
        var principal = AuthContext.requirePrincipal();
        ResourceQualityRepairRepository.RepairRow repair = requiredRepair(repairId);
        roleGuard.requireCourseManager(repair.courseId());
        if (!"completed".equals(repair.status()) || !repair.publishReady()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有通过全部门禁的候选版本可以发布");
        }
        ResourceRepository.ResourceRow current = requiredResource(repair.resourceId());
        int expectedVersion = request == null || request.expectedResourceVersion() == null
                ? -1
                : request.expectedResourceVersion();
        if (repair.candidateBaseVersion() == null
                || expectedVersion != repair.candidateBaseVersion()
                || current.version() != expectedVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "原资源版本已变化，请重新生成并审核修复候选");
        }
        String note = normalizedNote(request == null ? null : request.note(), "人工对比确认后发布修复候选。");
        Map<String, Object> candidate = repair.candidateResource();
        String content = text(candidate, "content");
        if (text(candidate, "title").isBlank() || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "候选版本缺少标题或正文，不能发布");
        }
        StoredObject storedObject = objectStorageService.store(
                "resources",
                current.resourceId() + "-repair-v" + (current.version() + 1) + extensionFor(text(candidate, "content_format")),
                text(candidate, "content_format"),
                content.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> previousSnapshot = resourceSnapshot(current);
        resourceRepository.addVersion(current, AuthContext.currentUsername() + " · repair publish");
        int updated = resourceRepository.applyRepairCandidate(
                current.resourceId(),
                expectedVersion,
                candidate,
                repair.candidateSafety(),
                repair.candidateQualityEvaluation(),
                repair.candidateGenerationMode(),
                storedObject.objectKey(),
                storedObject.objectUrl(),
                storedObject.storageStatus());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "原资源版本已变化，发布已安全终止");
        }
        int publishedVersion = expectedVersion + 1;
        if (repairRepository.markPublished(
                        repairId,
                        previousSnapshot,
                        expectedVersion,
                        publishedVersion,
                        principal.userId(),
                        note)
                == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "修复项发布状态已变化，发布已回滚");
        }
        return new RepairPublicationResult(
                repairId,
                current.resourceId(),
                "published",
                expectedVersion,
                publishedVersion,
                true,
                "candidate_published");
    }

    @Transactional
    public RepairPublicationResult rollback(String repairId, RepairRollbackRequest request) {
        var principal = AuthContext.requirePrincipal();
        ResourceQualityRepairRepository.RepairRow repair = requiredRepair(repairId);
        roleGuard.requireCourseManager(repair.courseId());
        if (!"published".equals(repair.status())
                || repair.publishedResourceVersion() == null
                || repair.previousResourceSnapshot().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该修复发布没有可回滚的原始快照");
        }
        ResourceRepository.ResourceRow current = requiredResource(repair.resourceId());
        if (current.version() != repair.publishedResourceVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "资源发布后已有新版本，禁止覆盖后续修改");
        }
        String note = normalizedNote(request == null ? null : request.note(), "人工确认回滚该修复发布。");
        resourceRepository.addVersion(current, AuthContext.currentUsername() + " · repair rollback");
        if (resourceRepository.restoreRepairSnapshot(
                        current.resourceId(), current.version(), repair.previousResourceSnapshot())
                == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "资源版本已变化，回滚已安全终止");
        }
        int rollbackVersion = current.version() + 1;
        if (repairRepository.markRolledBack(
                        repairId,
                        repair.publishedResourceVersion(),
                        rollbackVersion,
                        principal.userId(),
                        note)
                == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "修复项回滚状态已变化，操作已回滚");
        }
        return new RepairPublicationResult(
                repairId,
                current.resourceId(),
                "rolled_back",
                current.version(),
                rollbackVersion,
                true,
                "previous_snapshot_restored");
    }

    private Map<String, Object> repairPayload(
            ResourceQualityRepairRepository.RepairRow repair,
            ResourceRepository.ResourceRow resource) {
        Map<String, Object> storedResource = new LinkedHashMap<>();
        storedResource.put("title", resource.title());
        storedResource.put("resource_type", resource.resourceType());
        storedResource.put("content_format", resource.contentFormat());
        storedResource.put("content", resource.content() == null ? "" : resource.content());
        storedResource.put("summary", resource.summary() == null ? "" : resource.summary());
        storedResource.put("difficulty", normalizedDifficulty(resource.difficulty()));
        storedResource.put("knowledge_points", resource.knowledgePoints());
        storedResource.put("personalized_reason", resource.personalizedReason() == null
                ? "质量回归定向修复"
                : resource.personalizedReason());
        storedResource.put("estimated_minutes", resource.estimatedMinutes() == null ? 20 : resource.estimatedMinutes());
        storedResource.put("profile_fingerprint", resource.profileFingerprint() == null
                ? "controlled-repair"
                : resource.profileFingerprint());
        storedResource.put(
                "evidence_chunk_ids",
                resource.evidence().stream()
                        .map(item -> text(item, "chunk_id", "chunkId"))
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .toList());
        storedResource.put("quality_evaluation", repair.currentEvaluation());

        List<Map<String, Object>> evidence = new ArrayList<>();
        for (int index = 0; index < resource.evidence().size(); index++) {
            Map<String, Object> item = resource.evidence().get(index);
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("chunk_id", text(item, "chunk_id", "chunkId"));
            normalized.put("title", text(item, "title"));
            normalized.put("content", text(item, "content"));
            normalized.put("score", number(item.get("score")));
            normalized.put("source", text(item, "source"));
            normalized.put(
                    "knowledge_point",
                    resource.knowledgePoints().isEmpty()
                            ? ""
                            : resource.knowledgePoints().get(Math.min(index, resource.knowledgePoints().size() - 1)));
            evidence.add(normalized);
        }

        Map<String, Object> safety = new LinkedHashMap<>(resource.safety());
        safety.putIfAbsent("passed", false);
        safety.putIfAbsent("risk_level", "medium");
        safety.putIfAbsent("issues", List.of());
        safety.putIfAbsent("suggestions", List.of());
        safety.putIfAbsent("confidence", 0.0);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("repair_id", repair.repairId());
        payload.put("resource_id", resource.resourceId());
        payload.put("course_id", resource.courseId());
        payload.put("resource", storedResource);
        payload.put("evidence", evidence);
        payload.put("safety", safety);
        payload.put("baseline_evaluation", repair.baselineEvaluation());
        payload.put("current_evaluation", repair.currentEvaluation());
        payload.put("regressed_dimensions", repair.regressedDimensions());
        payload.put("expected_difficulty", normalizedDifficulty(resource.difficulty()));
        return payload;
    }

    private String normalizedStatus(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) {
            return null;
        }
        String value = status.trim();
        if (!STATUSES.contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "修复队列状态不合法");
        }
        return value;
    }

    private RepairItem toItem(ResourceQualityRepairRepository.RepairRow row, boolean canManage) {
        return new RepairItem(
                row.repairId(),
                row.resourceId(),
                row.resourceTitle(),
                row.resourceType(),
                row.sourceTaskId(),
                row.courseId(),
                row.triggerAlertCodes(),
                row.regressedDimensions(),
                row.baselineEvaluation(),
                row.currentEvaluation(),
                row.scoreDelta(),
                row.status(),
                canManage && "pending_review".equals(row.status()),
                canManage && "approved".equals(row.status()),
                row.reviewedBy(),
                row.reviewNote(),
                row.reviewedAt(),
                row.executionTaskId(),
                row.candidateQualityEvaluation(),
                row.candidateGenerationMode(),
                row.publishReady(),
                row.executionError(),
                row.executedAt(),
                row.candidateBaseVersion(),
                canManage && !row.candidateResource().isEmpty(),
                canManage && "completed".equals(row.status()) && row.publishReady(),
                canManage && "published".equals(row.status()),
                row.previousResourceVersion(),
                row.publishedResourceVersion(),
                row.publishedBy(),
                row.publishNote(),
                row.publishedAt(),
                row.rollbackResourceVersion(),
                row.rolledBackBy(),
                row.rollbackNote(),
                row.rolledBackAt(),
                row.createdAt(),
                row.updatedAt());
    }

    private ResourceQualityRepairRepository.RepairRow requiredRepair(String repairId) {
        return repairRepository.find(repairId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "修复队列项不存在: " + repairId));
    }

    private ResourceRepository.ResourceRow requiredResource(String resourceId) {
        return resourceRepository.find(resourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在: " + resourceId));
    }

    private Map<String, Object> resourceView(ResourceRepository.ResourceRow resource) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("resource_id", resource.resourceId());
        view.put("version", resource.version());
        view.put("title", resource.title());
        view.put("summary", resource.summary());
        view.put("content_format", resource.contentFormat());
        view.put("content", resource.content());
        view.put("difficulty", resource.difficulty());
        view.put("knowledge_points", resource.knowledgePoints());
        view.put("quality_evaluation", resource.qualityEvaluation());
        view.put("status", resource.status());
        return view;
    }

    private Map<String, Object> resourceSnapshot(ResourceRepository.ResourceRow resource) {
        Map<String, Object> snapshot = resourceView(resource);
        snapshot.put("personalized_reason", resource.personalizedReason());
        snapshot.put("estimated_minutes", resource.estimatedMinutes());
        snapshot.put("profile_fingerprint", resource.profileFingerprint());
        snapshot.put("safety", resource.safety());
        snapshot.put("generation_mode", resource.generationMode());
        snapshot.put("object_key", resource.objectKey());
        snapshot.put("object_url", resource.objectUrl());
        snapshot.put("storage_status", resource.storageStatus());
        snapshot.put("tags", resource.tags());
        return snapshot;
    }

    private List<String> changedFields(
            ResourceRepository.ResourceRow resource, Map<String, Object> candidate) {
        Map<String, Object> original = resourceView(resource);
        return List.of("title", "summary", "content", "difficulty", "knowledge_points")
                .stream()
                .filter(field -> !java.util.Objects.equals(original.get(field), candidate.get(field)))
                .toList();
    }

    private String normalizedNote(String value, String fallback) {
        String note = value == null || value.isBlank() ? fallback : value.trim();
        if (note.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "操作备注不能超过 500 字");
        }
        return note;
    }

    private String extensionFor(String contentFormat) {
        return switch (contentFormat == null ? "" : contentFormat.toLowerCase()) {
            case "json" -> ".json";
            case "mermaid" -> ".mmd";
            case "html" -> ".html";
            default -> ".md";
        };
    }

    private String agentFor(String resourceType) {
        return switch (resourceType) {
            case "mindmap", "flowchart" -> "MindmapAgent";
            case "quiz" -> "QuizAgent";
            case "codelab" -> "CodelabAgent";
            case "animation_script" -> "AnimationScriptAgent";
            default -> "LectureAgent";
        };
    }

    private String normalizedDifficulty(String value) {
        return List.of("basic", "medium", "advanced").contains(value) ? value : "medium";
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        raw.forEach((key, item) -> normalized.put(String.valueOf(key), item));
        return normalized;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private String text(Map<String, Object> value, String... keys) {
        for (String key : keys) {
            Object item = value.get(key);
            if (item != null) {
                return item.toString();
            }
        }
        return "";
    }

    private boolean bool(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0.0 : Double.parseDouble(value.toString());
        } catch (NumberFormatException exception) {
            return 0.0;
        }
    }

    private String truncate(String value, int maxLength) {
        String text = value == null || value.isBlank() ? "受控修复执行失败" : value;
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    public record RepairListResponse(List<RepairItem> items, int page, int size, long total) {}

    public record RepairItem(
            String repairId,
            String resourceId,
            String resourceTitle,
            String resourceType,
            String sourceTaskId,
            int courseId,
            List<String> triggerAlertCodes,
            List<String> regressedDimensions,
            Map<String, Object> baselineEvaluation,
            Map<String, Object> currentEvaluation,
            double scoreDelta,
            String status,
            boolean canReview,
            boolean canExecute,
            Long reviewedBy,
            String reviewNote,
            OffsetDateTime reviewedAt,
            String executionTaskId,
            Map<String, Object> candidateQualityEvaluation,
            String candidateGenerationMode,
            boolean publishReady,
            String executionError,
            OffsetDateTime executedAt,
            Integer candidateBaseVersion,
            boolean canCompare,
            boolean canPublish,
            boolean canRollback,
            Integer previousResourceVersion,
            Integer publishedResourceVersion,
            Long publishedBy,
            String publishNote,
            OffsetDateTime publishedAt,
            Integer rollbackResourceVersion,
            Long rolledBackBy,
            String rollbackNote,
            OffsetDateTime rolledBackAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record RepairDecisionRequest(String decision, String note) {}

    public record RepairDecisionResult(RepairItem item, String nextAction, String safetyNotice) {}

    public record RepairExecutionRequest(String repairId) {}

    public record RepairExecutionCreated(String repairId, String taskId, String status) {}

    public record RepairPublishRequest(Integer expectedResourceVersion, String note) {}

    public record RepairRollbackRequest(String note) {}

    public record RepairComparison(
            String repairId,
            String resourceId,
            int currentResourceVersion,
            Integer candidateBaseVersion,
            boolean stale,
            Map<String, Object> originalResource,
            Map<String, Object> candidateResource,
            Map<String, Object> currentQualityEvaluation,
            Map<String, Object> candidateQualityEvaluation,
            List<String> changedFields,
            boolean canPublish) {}

    public record RepairPublicationResult(
            String repairId,
            String resourceId,
            String status,
            int fromVersion,
            int toVersion,
            boolean atomic,
            String nextAction) {}

    public record RepairExecutionResult(
            String repairId,
            String resourceId,
            String candidateTitle,
            String candidateSummary,
            Map<String, Object> qualityEvaluation,
            Map<String, Object> safety,
            Map<String, Object> modelRuntime,
            double recoveryScoreDelta,
            List<String> improvedDimensions,
            boolean targetDimensionsPassed,
            boolean publishReady,
            boolean originalResourceUnchanged,
            String nextAction) {}
}
