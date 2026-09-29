package com.edupath.path;

import com.edupath.ai.AiAgentClient;
import com.edupath.auth.AuthContext;
import com.edupath.common.ExternalServiceException;
import com.edupath.course.CourseCatalogService;
import com.edupath.learning.LearningEventService;
import com.edupath.profile.ProfileService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningPathService {

    private final CourseCatalogService courseCatalogService;
    private final AiAgentClient aiAgentClient;
    private final LearningPathRepository learningPathRepository;
    private final ProfileService profileService;
    private final LearningEventService learningEventService;
    private final PathAdjustmentService pathAdjustmentService;

    public LearningPathService(
            CourseCatalogService courseCatalogService,
            AiAgentClient aiAgentClient,
            LearningPathRepository learningPathRepository,
            ProfileService profileService,
            LearningEventService learningEventService,
            PathAdjustmentService pathAdjustmentService) {
        this.courseCatalogService = courseCatalogService;
        this.aiAgentClient = aiAgentClient;
        this.learningPathRepository = learningPathRepository;
        this.profileService = profileService;
        this.learningEventService = learningEventService;
        this.pathAdjustmentService = pathAdjustmentService;
    }

    public Map<String, Object> currentPath() {
        String studentId = AuthContext.currentStudentId();
        return learningPathRepository
                .current(studentId)
                .map(this::toResponse)
                .orElseGet(() -> emptyPath(studentId));
    }

    public Map<String, Object> adjustmentSignal() {
        String studentId = AuthContext.currentStudentId();
        return learningPathRepository
                .current(studentId)
                .map(version -> pathAdjustmentService.evaluate(
                        version, learningEventService.completedPathDays(studentId, version.pathId())))
                .orElseGet(pathAdjustmentService::noPathSignal);
    }

    public BehaviorReplanPreparation prepareBehaviorReplan() {
        String studentId = AuthContext.currentStudentId();
        LearningPathRepository.PathVersion current = learningPathRepository.current(studentId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "尚未生成学习路径"));
        List<Integer> completedDays = learningEventService.completedPathDays(studentId, current.pathId());
        Map<String, Object> signal = pathAdjustmentService.evaluate(current, completedDays);
        if (!Boolean.TRUE.equals(signal.get("should_replan"))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "当前行为信号尚未达到可执行重规划条件");
        }
        return new BehaviorReplanPreparation(
                behaviorTriggerKey(studentId, current, signal),
                studentId,
                current.pathId(),
                current.version(),
                signal);
    }

    @Transactional
    public Map<String, Object> generate(LearningPathGenerateRequest request) {
        String studentId = AuthContext.currentStudentId();
        Map<String, Object> aiResponse = aiAgentClient.generatePath(request, profileService.currentProfileSnapshot());
        Object path = aiResponse.get("path");
        if (!(path instanceof Map<?, ?> pathMap)) {
            throw new ExternalServiceException("AI 服务", "学习路径生成未返回 path 结构");
        }
        Map<String, Object> safety = requireMap(aiResponse.get("safety"), "学习路径未返回 SafetyAgent 结果");
        if (!Boolean.TRUE.equals(safety.get("passed"))) {
            throw new ExternalServiceException("AI 服务", "学习路径未通过 SafetyAgent 审查");
        }
        Map<String, Object> modelRuntime = requireMap(aiResponse.get("model_runtime"), "学习路径未返回模型运行记录");
        String generationMode = String.valueOf(aiResponse.getOrDefault("generation_mode", ""));
        if (generationMode.isBlank()) {
            throw new ExternalServiceException("AI 服务", "学习路径未返回 generation_mode");
        }
        Map<String, Object> normalized = normalizeMap(pathMap);
        normalized.put("student_id", studentId);
        Map<String, Object> result = toResponse(learningPathRepository.save(
                studentId,
                normalized,
                String.valueOf(aiResponse.getOrDefault("task_id", ""))));
        result.put("generation_mode", generationMode);
        result.put("model_runtime", modelRuntime);
        result.put("safety", safety);
        result.put("evidence", aiResponse.getOrDefault("evidence", List.of()));
        return result;
    }

    @Transactional
    public Map<String, Object> completeDay(int day) {
        if (day < 1) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "路径节点序号必须大于 0");
        }
        String studentId = AuthContext.currentStudentId();
        LearningPathRepository.PathVersion version = learningPathRepository.current(studentId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "尚未生成学习路径"));
        Map<String, Object> node = pathNode(version.path().get("daily_plan"), day);
        String theme = String.valueOf(node.getOrDefault("theme", "核心知识点"));
        PathTopic pathTopic = resolvePathTopic(version.path().get("course_ids"), theme);
        LearningEventService.LearningUpdateResult learningUpdate = learningEventService.record(List.of(
                new LearningEventService.LearningEventCommand(
                        "path_node_completion",
                        pathTopic.courseId(),
                        pathTopic.knowledgePoint(),
                        "learning_path",
                        version.pathId(),
                        "complete",
                        100,
                        day,
                        2,
                        75,
                        "path:" + version.pathId() + ":day:" + day,
                        Map.of("path_version", version.version(), "theme", theme))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path_id", version.pathId());
        result.put("version", version.version());
        result.put("day", day);
        result.put("theme", theme);
        result.put("completed_days", learningEventService.completedPathDays(studentId, version.pathId()));
        result.put("learning_update", learningUpdate);
        return result;
    }

    private Map<String, Object> pathNode(Object dailyPlan, int day) {
        if (dailyPlan instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map && intValue(map.get("day"), -1) == day) {
                    return normalizeMap(map);
                }
            }
        }
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "学习路径中不存在第 " + day + " 天");
    }

    private PathTopic resolvePathTopic(Object rawCourseIds, String theme) {
        List<Integer> courseIds = integerList(rawCourseIds);
        if (courseIds.isEmpty()) {
            courseIds = List.of(1, 2);
        }
        for (int courseId : courseIds) {
            List<String> names = new ArrayList<>();
            for (CourseCatalogService.KnowledgePointDto point : courseCatalogService.listKnowledgePoints(courseId)) {
                collectKnowledgePoints(point, names);
            }
            String match = names.stream()
                    .filter(name -> theme.contains(name) || name.contains(theme))
                    .max((left, right) -> Integer.compare(left.length(), right.length()))
                    .orElse(null);
            if (match != null) {
                return new PathTopic(courseId, match);
            }
        }
        return new PathTopic(courseIds.get(0), theme);
    }

    private void collectKnowledgePoints(CourseCatalogService.KnowledgePointDto point, List<String> names) {
        names.add(point.name());
        point.children().forEach(child -> collectKnowledgePoints(child, names));
    }

    @Transactional
    public Map<String, Object> replanAfterEvaluation(
            int courseId,
            long quizId,
            Map<String, Object> evaluation,
            Map<String, Object> studentProfile,
            String evaluationTaskId) {
        String studentId = AuthContext.currentStudentId();
        LearningPathRepository.PathVersion previous = learningPathRepository.current(studentId).orElse(null);
        Map<String, Object> previousPath = previous == null
                ? Map.of()
                : new LinkedHashMap<>(previous.path());
        List<Integer> courseIds = integerList(previousPath.get("course_ids"));
        if (courseIds.isEmpty()) {
            courseIds = List.of(courseId);
        }
        int dailyMinutes = Math.max(10, Math.min(intValue(previousPath.get("daily_minutes"), 40), 180));
        String target = String.valueOf(previousPath.getOrDefault(
                "target", "根据最近测评动态补强薄弱知识点"));
        if (target.isBlank()) {
            target = "根据最近测评动态补强薄弱知识点";
        }

        Map<String, Object> authoritativeEvaluation = new LinkedHashMap<>();
        authoritativeEvaluation.put("quiz_id", quizId);
        authoritativeEvaluation.put("course_id", courseId);
        authoritativeEvaluation.put("overall_score", intValue(evaluation.get("overall_score"), 0));
        authoritativeEvaluation.put("weak_points", stringList(evaluation.get("weak_points")));
        authoritativeEvaluation.put("mistake_patterns", stringList(evaluation.get("mistake_patterns")));
        authoritativeEvaluation.put("next_actions", stringList(evaluation.get("next_actions")));
        authoritativeEvaluation.put("source_task_id", evaluationTaskId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("course_ids", courseIds);
        payload.put("target", target);
        payload.put("days", 3);
        payload.put("daily_minutes", dailyMinutes);
        payload.put("student_profile", studentProfile == null ? Map.of() : studentProfile);
        Map<String, Object> currentPathPayload = new LinkedHashMap<>(previousPath);
        if (previous != null) {
            currentPathPayload.put("path_id", previous.pathId());
            currentPathPayload.put("version", previous.version());
        }
        payload.put("current_path", currentPathPayload);
        payload.put("evaluation", authoritativeEvaluation);

        Map<String, Object> aiResponse = aiAgentClient.replanPath(payload);
        Map<String, Object> safety = requireMap(aiResponse.get("safety"), "动态重规划未返回 SafetyAgent 结果");
        if (!Boolean.TRUE.equals(safety.get("passed"))) {
            throw new ExternalServiceException("AI 服务", "动态重规划未通过 SafetyAgent 审查");
        }
        String trigger = String.valueOf(aiResponse.getOrDefault("trigger", ""));
        if (!"quiz_evaluation".equals(trigger)) {
            throw new ExternalServiceException("AI 服务", "动态重规划缺少测评触发标记");
        }
        if (!evaluationTaskId.equals(String.valueOf(aiResponse.getOrDefault("evaluation_task_id", "")))) {
            throw new ExternalServiceException("AI 服务", "动态重规划与本次评估任务不匹配");
        }
        Map<String, Object> path = requireMap(aiResponse.get("path"), "动态重规划未返回 path 结构");
        List<?> dailyPlan = path.get("daily_plan") instanceof List<?> list ? list : List.of();
        if (dailyPlan.isEmpty() || dailyPlan.size() > 3) {
            throw new ExternalServiceException("AI 服务", "动态重规划只能覆盖未来 1 到 3 天");
        }
        List<?> changes = aiResponse.get("changes") instanceof List<?> list ? list : List.of();
        if (changes.isEmpty()) {
            throw new ExternalServiceException("AI 服务", "动态重规划未说明路径调整内容");
        }
        Map<String, Object> modelRuntime = requireMap(
                aiResponse.get("model_runtime"), "动态重规划未返回模型运行记录");
        String generationMode = String.valueOf(aiResponse.getOrDefault("generation_mode", ""));
        if (generationMode.isBlank()) {
            throw new ExternalServiceException("AI 服务", "动态重规划未返回 generation_mode");
        }

        Map<String, Object> normalized = normalizeMap(path);
        normalized.put("student_id", studentId);
        normalized.put("replan_trigger", trigger);
        normalized.put("source_evaluation_task_id", evaluationTaskId);
        normalized.put("previous_path_id", previous == null ? "" : previous.pathId());
        normalized.put("previous_version", previous == null ? 0 : previous.version());
        normalized.put("replan_changes", changes);
        LearningPathRepository.PathVersion saved = learningPathRepository.save(
                studentId,
                normalized,
                String.valueOf(aiResponse.getOrDefault("task_id", "")));
        Map<String, Object> result = toResponse(saved);
        result.put("updated", true);
        result.put("trigger", trigger);
        result.put("previous_version", previous == null ? 0 : previous.version());
        result.put("changes", changes);
        result.put("generation_mode", generationMode);
        result.put("model_runtime", modelRuntime);
        result.put("safety", safety);
        result.put("evidence", aiResponse.getOrDefault("evidence", List.of()));
        return result;
    }

    public Map<String, Object> replanAfterBehavior(BehaviorReplanPreparation preparation) {
        String studentId = AuthContext.currentStudentId();
        if (!studentId.equals(preparation.studentId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "行为触发记录不属于当前学生");
        }
        LearningPathRepository.PathVersion previous = requireCurrentPath(preparation);
        List<Integer> completedDays = learningEventService.completedPathDays(studentId, previous.pathId());
        Map<String, Object> freshSignal = pathAdjustmentService.evaluate(previous, completedDays);
        if (!Boolean.TRUE.equals(freshSignal.get("should_replan"))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "行为信号已变化，取消本次重规划");
        }

        Map<String, Object> previousPath = new LinkedHashMap<>(previous.path());
        List<Integer> courseIds = integerList(previousPath.get("course_ids"));
        if (courseIds.isEmpty()) {
            courseIds = List.of(1, 2);
        }
        Map<String, Object> metrics = mapValue(freshSignal.get("metrics"));
        Map<String, Object> pacing = mapValue(freshSignal.get("pacing"));
        List<String> weakPoints = stringList(metrics.get("low_mastery_points"));
        int dailyMinutes = Math.max(
                10, Math.min(intValue(pacing.get("recommended_daily_minutes"), 40), 180));

        Map<String, Object> behaviorSignal = new LinkedHashMap<>();
        behaviorSignal.put("trigger_key", preparation.triggerKey());
        behaviorSignal.put("risk_score", intValue(freshSignal.get("risk_score"), 0));
        behaviorSignal.put("reasons", stringList(freshSignal.get("reasons")));
        behaviorSignal.put("weak_points", weakPoints);
        behaviorSignal.put("metrics", metrics);
        behaviorSignal.put("pacing", pacing);

        Map<String, Object> currentPathPayload = new LinkedHashMap<>(previousPath);
        currentPathPayload.put("path_id", previous.pathId());
        currentPathPayload.put("version", previous.version());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trigger", "behavior_signal");
        payload.put("course_ids", courseIds);
        payload.put("target", behaviorReplanTarget(weakPoints, stringList(freshSignal.get("reasons"))));
        payload.put("days", 3);
        payload.put("daily_minutes", dailyMinutes);
        payload.put("student_profile", profileService.currentProfileSnapshot());
        payload.put("current_path", currentPathPayload);
        payload.put("behavior_signal", behaviorSignal);

        Map<String, Object> aiResponse = aiAgentClient.replanPath(payload);
        Map<String, Object> safety = requireMap(aiResponse.get("safety"), "行为重规划未返回 SafetyAgent 结果");
        if (!Boolean.TRUE.equals(safety.get("passed"))) {
            throw new ExternalServiceException("AI 服务", "行为重规划未通过 SafetyAgent 审查");
        }
        if (!"behavior_signal".equals(String.valueOf(aiResponse.getOrDefault("trigger", "")))) {
            throw new ExternalServiceException("AI 服务", "行为重规划缺少正确的触发类型");
        }
        if (!preparation.triggerKey().equals(
                String.valueOf(aiResponse.getOrDefault("behavior_trigger_key", "")))) {
            throw new ExternalServiceException("AI 服务", "行为重规划返回的触发键不匹配");
        }
        Map<String, Object> path = requireMap(aiResponse.get("path"), "行为重规划未返回 path 结构");
        List<?> dailyPlan = path.get("daily_plan") instanceof List<?> list ? list : List.of();
        if (dailyPlan.isEmpty() || dailyPlan.size() > 3) {
            throw new ExternalServiceException("AI 服务", "行为重规划只能覆盖未来 1 到 3 天");
        }
        List<?> changes = aiResponse.get("changes") instanceof List<?> list ? list : List.of();
        if (changes.isEmpty()) {
            throw new ExternalServiceException("AI 服务", "行为重规划未说明路径调整内容");
        }
        Map<String, Object> modelRuntime = requireMap(
                aiResponse.get("model_runtime"), "行为重规划未返回模型运行记录");
        String generationMode = String.valueOf(aiResponse.getOrDefault("generation_mode", ""));
        if (generationMode.isBlank()) {
            throw new ExternalServiceException("AI 服务", "行为重规划未返回 generation_mode");
        }

        Map<String, Object> normalized = normalizeMap(path);
        normalized.put("student_id", studentId);
        normalized.put("replan_trigger", "behavior_signal");
        normalized.put("source_behavior_trigger_key", preparation.triggerKey());
        normalized.put("previous_path_id", previous.pathId());
        normalized.put("previous_version", previous.version());
        normalized.put("replan_changes", changes);
        LearningPathRepository.PathVersion saved = learningPathRepository.saveIfCurrent(
                        studentId,
                        previous.pathId(),
                        previous.version(),
                        normalized,
                        String.valueOf(aiResponse.getOrDefault("task_id", "")))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "当前路径版本已变化，行为重规划结果未写入"));
        Map<String, Object> result = toResponse(saved);
        result.put("updated", true);
        result.put("trigger", "behavior_signal");
        result.put("behavior_trigger_key", preparation.triggerKey());
        result.put("previous_version", previous.version());
        result.put("changes", changes);
        result.put("generation_mode", generationMode);
        result.put("model_runtime", modelRuntime);
        result.put("safety", safety);
        result.put("evidence", aiResponse.getOrDefault("evidence", List.of()));
        return result;
    }

    private LearningPathRepository.PathVersion requireCurrentPath(BehaviorReplanPreparation preparation) {
        LearningPathRepository.PathVersion current = learningPathRepository.current(preparation.studentId())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT, "当前学习路径已不存在"));
        if (!current.pathId().equals(preparation.pathId()) || current.version() != preparation.pathVersion()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "当前路径版本已变化，取消本次行为重规划");
        }
        return current;
    }

    private String behaviorTriggerKey(
            String studentId,
            LearningPathRepository.PathVersion current,
            Map<String, Object> signal) {
        Map<String, Object> metrics = mapValue(signal.get("metrics"));
        Map<String, Object> pacing = mapValue(signal.get("pacing"));
        int inactivityWindow = intValue(metrics.get("inactivity_hours"), 0) / 6;
        String fingerprintSource = String.join(
                "|",
                studentId,
                current.pathId(),
                String.valueOf(current.version()),
                String.valueOf(signal.getOrDefault("risk_score", 0)),
                String.valueOf(metrics.getOrDefault("consecutive_low_evidence", 0)),
                String.valueOf(metrics.getOrDefault("low_mastery_point_count", 0)),
                String.valueOf(inactivityWindow),
                String.valueOf(metrics.getOrDefault("completion_rate", 0)),
                String.valueOf(pacing.getOrDefault("recommended_daily_minutes", 0)));
        String fingerprint = UUID.nameUUIDFromBytes(fingerprintSource.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
        return "behavior_replan:" + studentId + ":" + current.pathId() + ":v" + current.version() + ":" + fingerprint;
    }

    private String behaviorReplanTarget(List<String> weakPoints, List<String> reasons) {
        String focus = !weakPoints.isEmpty()
                ? String.join("、", weakPoints)
                : reasons.stream().findFirst().orElse("近期学习行为风险");
        String target = "根据持续学习行为调整未来三天，重点改善：" + focus;
        return target.length() > 300 ? target.substring(0, 300) : target;
    }

    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? normalizeMap(map) : Map.of();
    }

    private Map<String, Object> normalizeMap(Map<?, ?> rawMap) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> normalized.put(String.valueOf(key), value));
        return normalized;
    }

    private Map<String, Object> requireMap(Object value, String message) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new ExternalServiceException("AI 服务", message);
        }
        return normalizeMap(map);
    }

    private Map<String, Object> buildPath(LearningPathGenerateRequest request, String studentId) {
        int days = request.days() == null ? 14 : Math.max(1, Math.min(request.days(), 30));
        int dailyMinutes = request.dailyMinutes() == null ? 40 : Math.max(10, Math.min(request.dailyMinutes(), 180));
        List<Integer> courseIds = request.courseIds() == null || request.courseIds().isEmpty()
                ? List.of(1, 2)
                : request.courseIds();
        String target = request.target() == null || request.target().isBlank()
                ? "掌握核心知识点并完成一次测评闭环"
                : request.target();
        List<Map<String, Object>> dailyPlan = new ArrayList<>();
        for (int day = 1; day <= days; day++) {
            int courseId = courseIds.get((day - 1) % courseIds.size());
            String topic = day % 2 == 0
                    ? courseCatalogService.resolveKnowledgePointNames(courseId, List.of(), List.of()).get(0)
                    : (courseId == 1 ? "递归调用栈" : "Cache 映射方式");
            dailyPlan.add(Map.of(
                    "day",
                    day,
                    "theme",
                    topic,
                    "tasks",
                    List.of(
                            Map.of(
                                    "type",
                                    "lecture",
                                    "resource_id",
                                    courseId == 1 ? "res_001" : "res_002",
                                    "title",
                                    topic + "讲义",
                                    "estimated_minutes",
                                    Math.max(15, dailyMinutes / 2)),
                            Map.of(
                                    "type",
                                    "quiz",
                                    "resource_id",
                                    "quiz_" + String.format("%03d", day),
                                    "title",
                                    topic + "随堂练习",
                                    "estimated_minutes",
                                    Math.max(10, dailyMinutes / 4))),
                    "expected_outcome",
                    "能用自己的话解释 " + topic + " 并完成 1 道迁移题"));
        }
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("path_id", "path_001");
        path.put("student_id", studentId);
        path.put("path_title", pathTitle(days, target));
        path.put("daily_plan", dailyPlan);
        path.put("adjustment_strategy", "如果测验正确率低于 70%，自动插入补救资源并降低下一日难度。");
        path.put("course_ids", courseIds);
        path.put("daily_minutes", dailyMinutes);
        return path;
    }

    private Map<String, Object> toResponse(LearningPathRepository.PathVersion version) {
        Map<String, Object> response = new LinkedHashMap<>(version.path());
        List<Integer> completedDays = learningEventService.completedPathDays(version.studentId(), version.pathId());
        response.put("path_id", version.pathId());
        response.put("student_id", version.studentId());
        response.put("version", version.version());
        response.put("source_task_id", version.sourceTaskId());
        response.put("completed_days", completedDays);
        response.put("adjustment_signal", pathAdjustmentService.evaluate(version, completedDays));
        response.putIfAbsent("recommended_resources", recommendedResources());
        return response;
    }

    private Map<String, Object> emptyPath(String studentId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("path_id", "");
        response.put("student_id", studentId);
        response.put("version", 0);
        response.put("source_task_id", "");
        response.put("path_title", "");
        response.put("daily_plan", List.of());
        response.put("adjustment_strategy", "");
        response.put("course_ids", List.of());
        response.put("daily_minutes", 0);
        response.put("completed_days", List.of());
        response.put("adjustment_signal", pathAdjustmentService.noPathSignal());
        response.put("recommended_resources", List.of());
        return response;
    }

    private List<Map<String, Object>> recommendedResources() {
        Object profileObject = profileService.currentProfile().get("profile");
        if (!(profileObject instanceof Map<?, ?> profile)) {
            return List.of();
        }
        List<String> weakPoints = stringList(profile.get("weak_points"));
        List<String> preferences = stringList(profile.get("resource_preference"));
        if (weakPoints.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> recommendations = new ArrayList<>();
        for (int i = 0; i < Math.min(weakPoints.size(), 4); i++) {
            String weakPoint = weakPoints.get(i);
            String type = preferences.isEmpty() ? "lecture" : normalizeResourcePreference(preferences.get(i % preferences.size()));
            recommendations.add(Map.of(
                    "type", type,
                    "title", weakPoint + "补救资源",
                    "reason", "根据最新画像弱点 " + weakPoint + " 自动推荐",
                    "priority", i + 1));
        }
        return recommendations;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();
        }
        return List.of();
    }

    private List<Integer> integerList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> intValue(item, -1))
                .filter(item -> item > 0)
                .toList();
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String normalizeResourcePreference(String preference) {
        return switch (preference) {
            case "code" -> "codelab";
            case "animation" -> "animation_script";
            default -> preference == null || preference.isBlank() ? "lecture" : preference;
        };
    }

    private String pathTitle(int days, String target) {
        String normalized = target.trim();
        if (normalized.matches("^\\d+\\s*天.*")) {
            return normalized;
        }
        return days + " 天" + normalized;
    }

    public record LearningPathGenerateRequest(
            List<Integer> courseIds, String target, Integer days, Integer dailyMinutes) {}

    public record BehaviorReplanPreparation(
            String triggerKey,
            String studentId,
            String pathId,
            int pathVersion,
            Map<String, Object> signal) {}

    private record PathTopic(int courseId, String knowledgePoint) {}
}
