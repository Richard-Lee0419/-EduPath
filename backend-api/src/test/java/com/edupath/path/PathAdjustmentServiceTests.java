package com.edupath.path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PathAdjustmentServiceTests {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-15T12:00:00+08:00");
    private PathAdjustmentRepository repository;
    private PathAdjustmentService service;

    @BeforeEach
    void setUp() {
        repository = mock(PathAdjustmentRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-15T04:00:00Z"), ZoneOffset.ofHours(8));
        service = new PathAdjustmentService(repository, clock);
    }

    @Test
    void shouldReturnStableSignalWhenPathDoesNotExist() {
        Map<String, Object> signal = service.noPathSignal();

        assertThat(signal).containsEntry("path_available", false)
                .containsEntry("status", "stable")
                .containsEntry("should_replan", false)
                .containsEntry("risk_score", 0);
    }

    @Test
    void shouldRecommendReplanWhenDeterministicRiskThresholdIsReached() {
        LearningPathRepository.PathVersion path = pathVersion(NOW.minusDays(3), NOW.minusHours(7), 40);
        when(repository.metrics("student-1")).thenReturn(new PathAdjustmentRepository.BehaviorMetrics(
                5, 3, 58, 4, 2, 56, List.of("递归调用栈", "二叉树遍历"), NOW.minusHours(2)));

        Map<String, Object> signal = service.evaluate(path, List.of());

        assertThat(signal).containsEntry("status", "replan_recommended")
                .containsEntry("should_replan", true)
                .containsEntry("replan_candidate", true)
                .containsEntry("risk_score", 7);
        assertThat(map(signal.get("metrics"))).containsEntry("completion_rate", 0);
        assertThat(map(signal.get("pacing")))
                .containsEntry("action", "reduce")
                .containsEntry("recommended_daily_minutes", 30);
        assertThat(map(signal.get("cooldown"))).containsEntry("eligible", true);
    }

    @Test
    void shouldKeepCandidateOnWatchDuringCooldown() {
        LearningPathRepository.PathVersion path = pathVersion(NOW.minusDays(3), NOW.minusHours(1), 40);
        when(repository.metrics("student-1")).thenReturn(new PathAdjustmentRepository.BehaviorMetrics(
                5, 3, 58, 4, 2, 56, List.of("递归调用栈", "二叉树遍历"), NOW.minusHours(2)));

        Map<String, Object> signal = service.evaluate(path, List.of());

        assertThat(signal).containsEntry("status", "watch")
                .containsEntry("should_replan", false)
                .containsEntry("replan_candidate", true);
        assertThat(map(signal.get("cooldown")))
                .containsEntry("eligible", false)
                .containsEntry("remaining_minutes", 300);
    }

    @Test
    void shouldKeepStableLearnerAndSuggestSmallPacingIncrease() {
        LearningPathRepository.PathVersion path = pathVersion(NOW.minusDays(3), NOW.minusHours(7), 40);
        when(repository.metrics("student-1")).thenReturn(new PathAdjustmentRepository.BehaviorMetrics(
                5, 0, 85, 4, 0, 82, List.of(), NOW.minusHours(1)));

        Map<String, Object> signal = service.evaluate(path, List.of(1, 2, 3));

        assertThat(signal).containsEntry("status", "stable")
                .containsEntry("should_replan", false)
                .containsEntry("risk_score", 0);
        assertThat(map(signal.get("pacing")))
                .containsEntry("action", "increase")
                .containsEntry("recommended_daily_minutes", 45);
    }

    private LearningPathRepository.PathVersion pathVersion(
            OffsetDateTime createdAt, OffsetDateTime updatedAt, int dailyMinutes) {
        return new LearningPathRepository.PathVersion(
                "path-1",
                "student-1",
                1,
                Map.of(
                        "daily_minutes", dailyMinutes,
                        "daily_plan", List.of(Map.of("day", 1), Map.of("day", 2), Map.of("day", 3))),
                "task-1",
                createdAt,
                updatedAt);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
