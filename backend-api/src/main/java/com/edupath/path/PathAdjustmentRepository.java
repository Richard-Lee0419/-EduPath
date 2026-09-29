package com.edupath.path;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PathAdjustmentRepository {

    private final JdbcTemplate jdbcTemplate;

    public PathAdjustmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public BehaviorMetrics metrics(String studentId) {
        List<Integer> recentScores = jdbcTemplate.query(
                """
                SELECT evidence_score
                FROM learning_events
                WHERE student_id = ? AND evidence_weight > 0
                ORDER BY occurred_at DESC, id DESC
                LIMIT 5
                """,
                (rs, rowNum) -> rs.getInt("evidence_score"),
                studentId);
        int consecutiveLowEvidence = 0;
        for (int score : recentScores) {
            if (score >= 65) {
                break;
            }
            consecutiveLowEvidence++;
        }
        int averageRecentEvidence = recentScores.isEmpty()
                ? 0
                : Math.round((float) recentScores.stream().mapToInt(Integer::intValue).sum() / recentScores.size());

        MasterySummary mastery = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) AS point_count,
                       COALESCE(SUM(CASE WHEN mastery_score < 60 THEN 1 ELSE 0 END), 0) AS low_count,
                       COALESCE(AVG(mastery_score), 0) AS average_mastery
                FROM knowledge_mastery
                WHERE student_id = ?
                """,
                (rs, rowNum) -> new MasterySummary(
                        rs.getInt("point_count"),
                        rs.getInt("low_count"),
                        (int) Math.round(rs.getDouble("average_mastery"))),
                studentId);
        List<String> lowMasteryPoints = jdbcTemplate.query(
                """
                SELECT knowledge_point
                FROM knowledge_mastery
                WHERE student_id = ? AND mastery_score < 60
                ORDER BY mastery_score ASC, updated_at DESC
                LIMIT 3
                """,
                (rs, rowNum) -> rs.getString("knowledge_point"),
                studentId);

        Timestamp lastActivity = jdbcTemplate.queryForObject(
                "SELECT MAX(occurred_at) FROM learning_events WHERE student_id = ?",
                Timestamp.class,
                studentId);
        return new BehaviorMetrics(
                recentScores.size(),
                consecutiveLowEvidence,
                averageRecentEvidence,
                mastery == null ? 0 : mastery.pointCount(),
                mastery == null ? 0 : mastery.lowCount(),
                mastery == null ? 0 : mastery.averageMastery(),
                lowMasteryPoints,
                lastActivity == null ? null : lastActivity.toInstant().atOffset(ZoneOffset.ofHours(8)));
    }

    private record MasterySummary(int pointCount, int lowCount, int averageMastery) {}

    public record BehaviorMetrics(
            int recentWeightedEvents,
            int consecutiveLowEvidence,
            int averageRecentEvidence,
            int masteryPointCount,
            int lowMasteryPointCount,
            int averageMastery,
            List<String> lowMasteryPoints,
            OffsetDateTime lastActivityAt) {}
}
