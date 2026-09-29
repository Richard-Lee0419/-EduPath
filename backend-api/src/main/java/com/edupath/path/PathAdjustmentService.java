package com.edupath.path;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PathAdjustmentService {

    static final int COOLDOWN_MINUTES = 6 * 60;
    private final PathAdjustmentRepository repository;
    private final Clock clock;

    @Autowired
    public PathAdjustmentService(PathAdjustmentRepository repository) {
        this(repository, Clock.system(ZoneOffset.ofHours(8)));
    }

    PathAdjustmentService(PathAdjustmentRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Map<String, Object> noPathSignal() {
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("path_available", false);
        signal.put("status", "stable");
        signal.put("should_replan", false);
        signal.put("replan_candidate", false);
        signal.put("risk_score", 0);
        signal.put("reasons", List.of("尚未生成学习路径，暂不进行行为驱动调整"));
        signal.put("metrics", Map.of());
        signal.put("pacing", Map.of("action", "maintain", "current_daily_minutes", 0, "recommended_daily_minutes", 0));
        signal.put("cooldown", Map.of("eligible", false, "remaining_minutes", 0, "window_minutes", COOLDOWN_MINUTES));
        signal.put("evaluated_at", now());
        return signal;
    }

    public Map<String, Object> evaluate(
            LearningPathRepository.PathVersion pathVersion, List<Integer> completedDays) {
        PathAdjustmentRepository.BehaviorMetrics metrics = repository.metrics(pathVersion.studentId());
        OffsetDateTime now = now();
        long pathAgeHours = nonNegativeHours(pathVersion.createdAt(), now);
        OffsetDateTime activityBaseline = metrics.lastActivityAt() == null
                ? pathVersion.createdAt()
                : metrics.lastActivityAt();
        long inactivityHours = nonNegativeHours(activityBaseline, now);
        int totalDays = dailyPlanSize(pathVersion.path().get("daily_plan"));
        int completedCount = completedDays == null ? 0 : completedDays.stream().distinct().toList().size();
        int completionRate = totalDays == 0 ? 0 : Math.min(100, Math.round(completedCount * 100.0f / totalDays));

        int riskScore = 0;
        List<String> reasons = new ArrayList<>();
        if (metrics.consecutiveLowEvidence() >= 3) {
            riskScore += 3;
            reasons.add("最近连续 " + metrics.consecutiveLowEvidence() + " 次有效学习证据低于 65 分");
        } else if (metrics.recentWeightedEvents() >= 3 && metrics.averageRecentEvidence() < 65) {
            riskScore += 1;
            reasons.add("最近有效学习证据均值偏低（" + metrics.averageRecentEvidence() + " 分）");
        }
        if (metrics.lowMasteryPointCount() >= 2) {
            riskScore += 2;
            reasons.add("存在 " + metrics.lowMasteryPointCount() + " 个掌握度低于 60 分的知识点");
        }
        if (inactivityHours >= 72) {
            riskScore += 3;
            reasons.add("已连续 " + inactivityHours + " 小时没有有效学习行为");
        } else if (inactivityHours >= 24) {
            riskScore += 1;
            reasons.add("最近学习间隔已达到 " + inactivityHours + " 小时");
        }
        boolean completionLag = pathAgeHours >= 48 && totalDays > 0 && completionRate < 35;
        if (completionLag) {
            riskScore += 2;
            reasons.add("当前路径已运行至少 2 天，但节点完成率仅为 " + completionRate + "%");
        }

        long minutesSincePathUpdate = nonNegativeMinutes(pathVersion.updatedAt(), now);
        int remainingCooldown = (int) Math.max(0, COOLDOWN_MINUTES - minutesSincePathUpdate);
        boolean cooldownEligible = remainingCooldown == 0;
        boolean replanCandidate = riskScore >= 3;
        boolean shouldReplan = replanCandidate && cooldownEligible;
        String status = shouldReplan ? "replan_recommended" : riskScore > 0 ? "watch" : "stable";
        if (reasons.isEmpty()) {
            reasons.add("近期学习行为、掌握度与路径进度均处于稳定范围");
        } else if (replanCandidate && !cooldownEligible) {
            reasons.add("已达到重规划候选阈值，但仍处于路径调整冷却期");
        }

        int currentDailyMinutes = boundedInt(pathVersion.path().get("daily_minutes"), 40, 10, 180);
        int recommendedDailyMinutes = currentDailyMinutes;
        String pacingAction = "maintain";
        if (inactivityHours >= 72 || completionLag) {
            recommendedDailyMinutes = Math.max(20, currentDailyMinutes - 10);
            pacingAction = recommendedDailyMinutes < currentDailyMinutes ? "reduce" : "maintain";
        } else if (metrics.consecutiveLowEvidence() >= 3 || metrics.lowMasteryPointCount() >= 2) {
            recommendedDailyMinutes = Math.max(20, currentDailyMinutes - 5);
            pacingAction = recommendedDailyMinutes < currentDailyMinutes ? "reduce" : "maintain";
        } else if (riskScore == 0 && completionRate >= 70 && metrics.averageMastery() >= 75) {
            recommendedDailyMinutes = Math.min(120, currentDailyMinutes + 5);
            pacingAction = recommendedDailyMinutes > currentDailyMinutes ? "increase" : "maintain";
        }

        Map<String, Object> metricPayload = new LinkedHashMap<>();
        metricPayload.put("recent_weighted_events", metrics.recentWeightedEvents());
        metricPayload.put("consecutive_low_evidence", metrics.consecutiveLowEvidence());
        metricPayload.put("average_recent_evidence", metrics.averageRecentEvidence());
        metricPayload.put("mastery_point_count", metrics.masteryPointCount());
        metricPayload.put("low_mastery_point_count", metrics.lowMasteryPointCount());
        metricPayload.put("average_mastery", metrics.averageMastery());
        metricPayload.put("low_mastery_points", metrics.lowMasteryPoints());
        metricPayload.put("path_age_hours", pathAgeHours);
        metricPayload.put("inactivity_hours", inactivityHours);
        metricPayload.put("completed_days", completedCount);
        metricPayload.put("total_days", totalDays);
        metricPayload.put("completion_rate", completionRate);
        metricPayload.put("last_activity_at", metrics.lastActivityAt());

        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("path_available", true);
        signal.put("status", status);
        signal.put("should_replan", shouldReplan);
        signal.put("replan_candidate", replanCandidate);
        signal.put("risk_score", riskScore);
        signal.put("reasons", reasons);
        signal.put("metrics", metricPayload);
        signal.put("pacing", Map.of(
                "action", pacingAction,
                "current_daily_minutes", currentDailyMinutes,
                "recommended_daily_minutes", recommendedDailyMinutes));
        signal.put("cooldown", Map.of(
                "eligible", cooldownEligible,
                "remaining_minutes", remainingCooldown,
                "window_minutes", COOLDOWN_MINUTES));
        signal.put("evaluated_at", now);
        return signal;
    }

    private int dailyPlanSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private int boundedInt(Object value, int fallback, int minimum, int maximum) {
        int parsed = fallback;
        try {
            if (value instanceof Number number) {
                parsed = number.intValue();
            } else if (value != null) {
                parsed = Integer.parseInt(value.toString());
            }
        } catch (NumberFormatException ignored) {
            parsed = fallback;
        }
        return Math.max(minimum, Math.min(maximum, parsed));
    }

    private long nonNegativeHours(OffsetDateTime start, OffsetDateTime end) {
        return start == null ? 0 : Math.max(0, Duration.between(start, end).toHours());
    }

    private long nonNegativeMinutes(OffsetDateTime start, OffsetDateTime end) {
        return start == null ? COOLDOWN_MINUTES : Math.max(0, Duration.between(start, end).toMinutes());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
