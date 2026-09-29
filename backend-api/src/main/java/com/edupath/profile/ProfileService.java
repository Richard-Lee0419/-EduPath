package com.edupath.profile;

import com.edupath.ai.AiAgentClient;
import com.edupath.auth.AuthContext;
import com.edupath.common.ExternalServiceException;
import com.edupath.course.CourseCatalogService;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {

    private final CourseCatalogService courseCatalogService;
    private final AiAgentClient aiAgentClient;
    private final ProfileRepository profileRepository;

    public ProfileService(
            CourseCatalogService courseCatalogService,
            AiAgentClient aiAgentClient,
            ProfileRepository profileRepository) {
        this.courseCatalogService = courseCatalogService;
        this.aiAgentClient = aiAgentClient;
        this.profileRepository = profileRepository;
    }

    public Map<String, Object> currentProfile() {
        String studentId = AuthContext.currentStudentId();
        var persisted = profileRepository.current(studentId);
        if (persisted.isPresent()) {
            ProfileRepository.ProfileVersion version = persisted.get();
            return Map.of(
                    "profile",
                    version.profile(),
                    "version",
                    version.version(),
                    "source_task_id",
                    version.sourceTaskId() == null ? "" : version.sourceTaskId(),
                    "updated_reason",
                    version.updatedReason() == null ? "" : version.updatedReason());
        }
        Map<String, Object> emptyProfile = new LinkedHashMap<>();
        emptyProfile.put("student_id", studentId);
        emptyProfile.put("target_courses", List.of());
        emptyProfile.put("knowledge_base", Map.of());
        emptyProfile.put("course_progress", List.of());
        emptyProfile.put("cognitive_style", List.of());
        emptyProfile.put("weak_points", List.of());
        emptyProfile.put("mistake_patterns", List.of());
        emptyProfile.put("resource_preference", List.of());
        emptyProfile.put("confidence_score", 0);
        return Map.of("profile", emptyProfile, "version", 0, "updated_reason", "");
    }

    public Map<String, Object> currentProfileSnapshot() {
        Object profile = currentProfile().get("profile");
        if (profile instanceof Map<?, ?> profileMap) {
            return normalizeMap(profileMap);
        }
        return Map.of("student_id", AuthContext.currentStudentId());
    }

    @Transactional
    public Map<String, Object> applyChat(ProfileChatRequest request) {
        String studentId = AuthContext.currentStudentId();
        Map<String, Object> aiResponse = aiAgentClient.extractProfile(request, studentId);
        Object profile = aiResponse.get("profile");
        if (!(profile instanceof Map<?, ?> profileMap)) {
            throw new ExternalServiceException("AI 服务", "画像抽取未返回 profile 结构");
        }
        Map<String, Object> safety = requireMap(aiResponse.get("safety"), "画像抽取未返回 SafetyAgent 结果");
        if (!Boolean.TRUE.equals(safety.get("passed"))) {
            throw new ExternalServiceException("AI 服务", "画像抽取未通过 SafetyAgent 审查");
        }
        Map<String, Object> modelRuntime = requireMap(aiResponse.get("model_runtime"), "画像抽取未返回模型运行记录");
        String generationMode = String.valueOf(aiResponse.getOrDefault("generation_mode", ""));
        if (generationMode.isBlank()) {
            throw new ExternalServiceException("AI 服务", "画像抽取未返回 generation_mode");
        }
        Map<String, Object> aiProfile = mergeProfile(currentProfilePayload(studentId), normalizeMap(profileMap));
        aiProfile.put("student_id", studentId);
        String updatedReason = String.valueOf(aiProfile.getOrDefault("updated_reason", "由画像对话生成"));
        ProfileRepository.ProfileVersion saved = profileRepository.save(
                studentId,
                aiProfile,
                String.valueOf(aiResponse.getOrDefault("task_id", "")),
                updatedReason);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", saved.profile());
        result.put("version", saved.version());
        result.put("ai_task_id", aiResponse.get("task_id"));
        result.put("extracted", aiResponse.get("extracted"));
        result.put("generation_mode", generationMode);
        result.put("model_runtime", modelRuntime);
        result.put("safety", safety);
        return result;
    }

    @Transactional
    public Map<String, Object> updateAfterQuizResult(
            int score,
            List<String> weakPoints,
            List<String> mistakePatterns,
            String recommendation,
            String sourceTaskId) {
        String studentId = AuthContext.currentStudentId();
        Map<String, Object> profile = currentProfilePayload(studentId);
        profile.put("weak_points", mergeStringLists(profile.get("weak_points"), weakPoints));
        profile.put("mistake_patterns", mergeStringLists(profile.get("mistake_patterns"), mistakePatterns));
        profile.put("latest_quiz_score", score);
        profile.put("latest_recommendation", recommendation);
        String updatedReason = "EvaluationAgent 测验评估后自动更新画像";
        profile.put("updated_reason", updatedReason);
        ProfileRepository.ProfileVersion saved = profileRepository.save(
                studentId,
                profile,
                sourceTaskId == null ? "" : sourceTaskId,
                updatedReason);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", true);
        result.put("version", saved.version());
        result.put("latest_quiz_score", score);
        result.put("weak_points", weakPoints == null ? List.of() : weakPoints);
        result.put("mistake_patterns", mistakePatterns == null ? List.of() : mistakePatterns);
        result.put("source_task_id", sourceTaskId == null ? "" : sourceTaskId);
        return result;
    }

    @Transactional
    public Map<String, Object> updateAfterLearningEvents(
            List<MasterySignal> signals, String sourceEventId) {
        String studentId = AuthContext.currentStudentId();
        Map<String, Object> profile = currentProfilePayload(studentId);
        Map<String, Object> masterySnapshot = profile.get("mastery_snapshot") instanceof Map<?, ?> existing
                ? normalizeMap(existing)
                : new LinkedHashMap<>();
        Map<String, Object> knowledgeBase = profile.get("knowledge_base") instanceof Map<?, ?> existing
                ? normalizeMap(existing)
                : new LinkedHashMap<>();
        List<String> weakPoints = new ArrayList<>(stringList(profile.get("weak_points")));
        for (MasterySignal signal : signals == null ? List.<MasterySignal>of() : signals) {
            masterySnapshot.put(signal.knowledgePoint(), Map.of(
                    "score", signal.masteryScore(),
                    "level", masteryLevel(signal.masteryScore()),
                    "last_event_type", signal.eventType()));
            knowledgeBase.put(signal.knowledgePoint(), knowledgeLevel(signal.masteryScore()));
            if (signal.masteryScore() < 60 && !weakPoints.contains(signal.knowledgePoint())) {
                weakPoints.add(signal.knowledgePoint());
            } else if (signal.masteryScore() >= 75) {
                weakPoints.remove(signal.knowledgePoint());
            }
        }
        profile.put("mastery_snapshot", masterySnapshot);
        profile.put("knowledge_base", knowledgeBase);
        profile.put("weak_points", weakPoints);
        String updatedReason = "学习行为事件驱动掌握度连续更新";
        profile.put("updated_reason", updatedReason);
        ProfileRepository.ProfileVersion saved = profileRepository.save(
                studentId,
                profile,
                sourceEventId == null ? "" : sourceEventId,
                updatedReason);
        return Map.of(
                "updated", true,
                "version", saved.version(),
                "source_event_id", sourceEventId == null ? "" : sourceEventId,
                "weak_points", weakPoints,
                "mastery_snapshot", masterySnapshot);
    }

    private String masteryLevel(int score) {
        if (score >= 85) return "优秀";
        if (score >= 70) return "良好";
        if (score >= 60) return "一般";
        return "待补救";
    }

    private String knowledgeLevel(int score) {
        if (score >= 80) return "advanced";
        if (score >= 60) return "medium";
        return "beginner";
    }

    private Map<String, Object> currentProfilePayload(String studentId) {
        return profileRepository.current(studentId)
                .map(version -> new LinkedHashMap<>(version.profile()))
                .orElseGet(() -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> profile = (Map<String, Object>) currentProfile().get("profile");
                    return new LinkedHashMap<>(profile);
                });
    }

    private List<String> mergeStringLists(Object existing, List<String> additions) {
        List<String> merged = new ArrayList<>();
        if (existing instanceof List<?> list) {
            for (Object item : list) {
                String value = String.valueOf(item);
                if (!value.isBlank() && !merged.contains(value)) {
                    merged.add(value);
                }
            }
        }
        if (additions != null) {
            for (String item : additions) {
                if (item != null && !item.isBlank() && !merged.contains(item)) {
                    merged.add(item);
                }
            }
        }
        return merged;
    }

    private Map<String, Object> mergeProfile(
            Map<String, Object> existing, Map<String, Object> update) {
        Map<String, Object> merged = new LinkedHashMap<>(existing);
        for (Map.Entry<String, Object> entry : update.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (List.of("weak_points", "mistake_patterns", "resource_preference", "cognitive_style", "target_courses")
                    .contains(key)) {
                merged.put(key, mergeStringLists(merged.get(key), stringList(value)));
            } else if ("knowledge_base".equals(key) && value instanceof Map<?, ?> updateKnowledge) {
                Map<String, Object> knowledge = new LinkedHashMap<>();
                if (merged.get(key) instanceof Map<?, ?> existingKnowledge) {
                    existingKnowledge.forEach((itemKey, itemValue) -> knowledge.put(String.valueOf(itemKey), itemValue));
                }
                updateKnowledge.forEach((itemKey, itemValue) -> {
                    String normalized = itemValue == null ? "" : String.valueOf(itemValue);
                    if (!normalized.isBlank() && !"unknown".equals(normalized)) {
                        knowledge.put(String.valueOf(itemKey), itemValue);
                    }
                });
                merged.put(key, knowledge);
            } else if (value instanceof String text && text.isBlank()) {
                continue;
            } else if (value instanceof List<?> list && list.isEmpty()) {
                continue;
            } else if (value != null) {
                merged.put(key, value);
            }
        }
        return merged;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();
        }
        return List.of();
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

    public record ProfileChatRequest(String message, List<Integer> courseIds) {}

    public record MasterySignal(String knowledgePoint, int masteryScore, String eventType) {}
}
