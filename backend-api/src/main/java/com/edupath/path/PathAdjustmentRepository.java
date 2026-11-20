package com.edupath.path;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PathAdjustmentRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public PathAdjustmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    public BehaviorMetrics metrics(String studentId) {
        return metrics(studentId, List.of());
    }

    BehaviorMetrics metrics(String studentId, List<Integer> courseIds) {
        boolean classPath = !courseIds.isEmpty();
        var args = new MapSqlParameterSource("student", studentId).addValue("courses", courseIds);
        String eventScope = classPath ? " AND course_id IN (:courses) "
                : " AND NOT EXISTS (SELECT 1 FROM courses c WHERE c.id = learning_events.course_id AND c.visibility = 'class') ";
        String masteryScope = classPath ? " FROM class_course_mastery WHERE student_id = :student AND course_id IN (:courses) "
                : " FROM knowledge_mastery WHERE student_id = :student ";
        List<Integer> recentScores = jdbc.query(
                """
                SELECT evidence_score
                FROM learning_events
                WHERE student_id = :student AND evidence_weight > 0
                """ + eventScope + """
                ORDER BY occurred_at DESC, id DESC
                LIMIT 5
                """,
                args,
                (rs, rowNum) -> rs.getInt("evidence_score"));
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

        MasterySummary mastery = jdbc.queryForObject(
                """
                SELECT COUNT(*) AS point_count,
                       COALESCE(SUM(CASE WHEN mastery_score < 60 THEN 1 ELSE 0 END), 0) AS low_count,
                       COALESCE(AVG(mastery_score), 0) AS average_mastery
                """ + masteryScope,
                args,
                (rs, rowNum) -> new MasterySummary(
                        rs.getInt("point_count"),
                        rs.getInt("low_count"),
                        (int) Math.round(rs.getDouble("average_mastery"))));
        List<String> lowMasteryPoints = jdbc.query(
                "SELECT knowledge_point" + masteryScope + """
                  AND mastery_score < 60
                ORDER BY mastery_score ASC, updated_at DESC
                LIMIT 3
                """,
                args,
                (rs, rowNum) -> rs.getString("knowledge_point"));

        Timestamp lastActivity = jdbc.queryForObject(
                "SELECT MAX(occurred_at) FROM learning_events WHERE student_id = :student" + eventScope,
                args, Timestamp.class);
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
