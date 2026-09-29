package com.edupath.resource;

import com.edupath.agenttask.AgentTaskService;
import com.edupath.ai.AiAgentClient;
import com.edupath.auth.AuthContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ResourceQualityRegressionService {

    private static final ZoneOffset CHINA_OFFSET = ZoneOffset.ofHours(8);

    private final AgentTaskService agentTaskService;
    private final ResourceRepository resourceRepository;
    private final ResourceQualityMetricsService qualityMetricsService;
    private final ResourceQualityRepairRepository qualityRepairRepository;
    private final AiAgentClient aiAgentClient;

    public ResourceQualityRegressionService(
            AgentTaskService agentTaskService,
            ResourceRepository resourceRepository,
            ResourceQualityMetricsService qualityMetricsService,
            ResourceQualityRepairRepository qualityRepairRepository,
            AiAgentClient aiAgentClient) {
        this.agentTaskService = agentTaskService;
        this.resourceRepository = resourceRepository;
        this.qualityMetricsService = qualityMetricsService;
        this.qualityRepairRepository = qualityRepairRepository;
        this.aiAgentClient = aiAgentClient;
    }

    public AgentTaskService.TaskCreatedResponse createTask(QualityRegressionRequest request) {
        var principal = AuthContext.requirePrincipal();
        QualityRegressionRequest normalized = normalize(request);
        if (normalized.force() && "student".equals(principal.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "学生不能强制执行无告警回归");
        }
        ResourceQualityMetricsService.QualityMetricsResponse metrics = qualityMetricsService.metrics(
                normalized.courseId(),
                normalized.resourceType(),
                normalized.generationMode(),
                normalized.windowDays());
        List<String> alertCodes = metrics.alerts().stream()
                .map(ResourceQualityMetricsService.QualityAlert::code)
                .distinct()
                .toList();
        if (alertCodes.isEmpty() && !normalized.force()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前筛选窗口未触发质量告警，无需执行自动回归");
        }
        List<AgentTaskService.AgentStepPlan> plans = List.of(
                new AgentTaskService.AgentStepPlan(
                        "QualityMonitorAgent", "正在锁定告警窗口和回归样本", "已锁定小批量真实资源样本"),
                new AgentTaskService.AgentStepPlan(
                        "QualityEvaluator", "正在使用当前确定性评测器重新评分", "已完成当前评测器复评"),
                new AgentTaskService.AgentStepPlan(
                        "RegressionGuardAgent", "正在比较持久化基线并判定回归", "已输出只读回归结论"));
        return agentTaskService.createObservableWorkflowTask(
                "quality_regression",
                plans,
                "quality_regression",
                normalized,
                progress -> executeRegression(normalized, alertCodes, progress));
    }

    private QualityRegressionResult executeRegression(
            QualityRegressionRequest request,
            List<String> alertCodes,
            AgentTaskService.TaskProgress progress) {
        progress.running("QualityMonitorAgent", "正在读取告警范围内的真实资源和质量快照", 12);
        Long ownerUserId = currentStudentOwnerId();
        List<ResourceRepository.ResourceRow> candidates = resourceRepository.listQualityRegressionCandidates(
                        request.courseId(),
                        request.resourceType(),
                        request.generationMode(),
                        ownerUserId,
                        OffsetDateTime.now(CHINA_OFFSET).minusDays(request.windowDays()),
                        Math.min(50, request.maxResources() * 5))
                .stream()
                .filter(row -> baselineScore(row.qualityEvaluation()) != null)
                .sorted(Comparator
                        .comparingDouble((ResourceRepository.ResourceRow row) -> baselineScore(row.qualityEvaluation()))
                        .thenComparing(ResourceRepository.ResourceRow::createdAt, Comparator.reverseOrder()))
                .limit(request.maxResources())
                .toList();
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前范围没有可重新评测的有效质量快照");
        }
        progress.success(
                "QualityMonitorAgent", "已锁定 " + candidates.size() + " 个最低分优先回归样本", 28);
        progress.running("QualityEvaluator", "正在逐条执行确定性质量复评", 36);
        List<Map<String, Object>> results = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            ResourceRepository.ResourceRow row = candidates.get(index);
            results.add(aiAgentClient.regressResourceQuality(regressionPayload(row)));
            int itemProgress = 36 + (int) Math.round(((index + 1.0) / candidates.size()) * 42);
            progress.running(
                    "QualityEvaluator",
                    "已重新评测 " + (index + 1) + "/" + candidates.size() + " 个资源",
                    itemProgress);
        }
        progress.success("QualityEvaluator", "已完成当前评测器复评", 80);
        progress.running("RegressionGuardAgent", "正在比较基线总分、门禁和五维变化", 86);
        long regressionCount = results.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("regression_detected")))
                .count();
        List<String> regressedResourceIds = results.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("regression_detected")))
                .map(item -> String.valueOf(item.get("resource_id")))
                .toList();
        Map<String, ResourceRepository.ResourceRow> candidatesById = candidates.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ResourceRepository.ResourceRow::resourceId,
                        row -> row));
        List<String> queuedRepairIds = results.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("regression_detected")))
                .map(item -> {
                    String resourceId = String.valueOf(item.get("resource_id"));
                    ResourceRepository.ResourceRow resource = candidatesById.get(resourceId);
                    if (resource == null) {
                        throw new IllegalStateException("回归结果引用了任务范围外资源: " + resourceId);
                    }
                    return qualityRepairRepository.enqueue(progress.taskId(), resource, alertCodes, item);
                })
                .distinct()
                .toList();
        progress.success(
                "RegressionGuardAgent",
                regressionCount == 0
                        ? "当前评测器与持久化基线保持稳定"
                        : "发现 " + regressionCount + " 个资源发生质量回归",
                96);
        return new QualityRegressionResult(
                alertCodes,
                candidates.size(),
                (int) regressionCount,
                candidates.size() - (int) regressionCount,
                !regressedResourceIds.isEmpty(),
                regressedResourceIds,
                queuedRepairIds,
                "audit_only",
                "任务只重新评测和比较，不修改资源内容、状态或发布结果。",
                results,
                OffsetDateTime.now(CHINA_OFFSET));
    }

    private Map<String, Object> regressionPayload(ResourceRepository.ResourceRow row) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("title", row.title());
        resource.put("resource_type", row.resourceType());
        resource.put("content_format", row.contentFormat());
        resource.put("content", row.content() == null ? "" : row.content());
        resource.put("summary", row.summary() == null ? "" : row.summary());
        resource.put("difficulty", normalizedDifficulty(row.difficulty()));
        resource.put("knowledge_points", row.knowledgePoints());
        resource.put("personalized_reason", row.personalizedReason() == null ? "回归复评" : row.personalizedReason());
        resource.put("estimated_minutes", row.estimatedMinutes() == null ? 20 : row.estimatedMinutes());
        resource.put("profile_fingerprint", row.profileFingerprint() == null ? "historical" : row.profileFingerprint());
        resource.put("evidence_chunk_ids", evidenceChunkIds(row.evidence()));

        List<Map<String, Object>> evidence = new ArrayList<>();
        for (Map<String, Object> item : row.evidence()) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("chunk_id", text(item, "chunk_id", "chunkId"));
            normalized.put("title", text(item, "title"));
            normalized.put("content", text(item, "content"));
            normalized.put("score", number(item.get("score"), 0.0));
            normalized.put("source", text(item, "source"));
            normalized.put("knowledge_point", row.knowledgePoints().isEmpty() ? "" : row.knowledgePoints().get(0));
            evidence.add(normalized);
        }

        Map<String, Object> safety = new LinkedHashMap<>(row.safety());
        safety.putIfAbsent("passed", false);
        safety.putIfAbsent("risk_level", "medium");
        safety.putIfAbsent("issues", List.of());
        safety.putIfAbsent("suggestions", List.of());
        safety.putIfAbsent("confidence", 0.0);

        Map<String, Object> baseline = new LinkedHashMap<>(row.qualityEvaluation());
        baseline.putIfAbsent("evaluator_version", "unknown");
        baseline.putIfAbsent("grade", grade(baselineScore(baseline)));
        baseline.putIfAbsent("gate_passed", false);
        baseline.putIfAbsent("dimensions", Map.of());
        baseline.putIfAbsent("issues", List.of());
        baseline.putIfAbsent("recommendations", List.of());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resource_id", row.resourceId());
        payload.put("course_id", row.courseId());
        payload.put("resource", resource);
        payload.put("evidence", evidence);
        payload.put("safety", safety);
        payload.put("baseline_evaluation", baseline);
        payload.put("expected_difficulty", normalizedDifficulty(row.difficulty()));
        return payload;
    }

    private List<String> evidenceChunkIds(List<Map<String, Object>> evidence) {
        return evidence.stream()
                .map(item -> text(item, "chunk_id", "chunkId"))
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private String normalizedDifficulty(String value) {
        return List.of("basic", "medium", "advanced").contains(value) ? value : "medium";
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

    private double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(value.toString());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private Double baselineScore(Map<String, Object> evaluation) {
        if (evaluation == null || evaluation.isEmpty()) {
            return null;
        }
        Object value = evaluation.get("total_score");
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? null : Double.parseDouble(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String grade(Double score) {
        if (score == null || score < 70) {
            return "D";
        }
        if (score < 80) {
            return "C";
        }
        if (score < 90) {
            return "B";
        }
        return "A";
    }

    private Long currentStudentOwnerId() {
        var principal = AuthContext.requirePrincipal();
        return "student".equals(principal.role()) ? principal.userId() : null;
    }

    private QualityRegressionRequest normalize(QualityRegressionRequest request) {
        QualityRegressionRequest value = request == null
                ? new QualityRegressionRequest(null, null, null, 30, 5, false)
                : request;
        int windowDays = Math.max(1, Math.min(value.windowDays() == null ? 30 : value.windowDays(), 365));
        int maxResources = Math.max(1, Math.min(value.maxResources() == null ? 5 : value.maxResources(), 10));
        return new QualityRegressionRequest(
                value.courseId(),
                normalizedFilter(value.resourceType()),
                normalizedFilter(value.generationMode()),
                windowDays,
                maxResources,
                Boolean.TRUE.equals(value.force()));
    }

    private String normalizedFilter(String value) {
        return value == null || value.isBlank() || "all".equalsIgnoreCase(value) ? null : value.trim();
    }

    public record QualityRegressionRequest(
            Integer courseId,
            String resourceType,
            String generationMode,
            Integer windowDays,
            Integer maxResources,
            Boolean force) {}

    public record QualityRegressionResult(
            List<String> triggerAlertCodes,
            int inspectedResources,
            int regressionCount,
            int stableCount,
            boolean actionRequired,
            List<String> regressedResourceIds,
            List<String> queuedRepairIds,
            String automationPolicy,
            String policyDescription,
            List<Map<String, Object>> results,
            OffsetDateTime completedAt) {}
}
