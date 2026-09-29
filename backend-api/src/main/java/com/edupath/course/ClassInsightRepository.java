package com.edupath.course;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ClassInsightRepository {

    private final JdbcTemplate jdbcTemplate;

    public ClassInsightRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<StudentInsightRow> students(long classId, long courseId, OffsetDateTime since) {
        return jdbcTemplate.query(
                """
                SELECT u.id AS user_id, u.username,
                       (SELECT COUNT(*) FROM knowledge_mastery km
                        JOIN knowledge_points kp ON kp.course_id = ? AND kp.name = km.knowledge_point
                        WHERE km.student_id = u.username) AS mastery_points,
                       (SELECT AVG(km.mastery_score) FROM knowledge_mastery km
                        JOIN knowledge_points kp ON kp.course_id = ? AND kp.name = km.knowledge_point
                        WHERE km.student_id = u.username) AS average_mastery,
                       (SELECT COUNT(*) FROM learning_events le
                        WHERE le.user_id = u.id AND le.course_id = ? AND le.occurred_at >= ?) AS activity_count,
                       (SELECT MAX(le.occurred_at) FROM learning_events le
                        WHERE le.user_id = u.id AND le.course_id = ?) AS last_active_at,
                       (SELECT AVG(qa.score) FROM quiz_attempts qa
                        JOIN quizzes q ON q.quiz_id = qa.quiz_id AND q.course_id = ?
                        WHERE qa.student_id = u.username AND qa.submitted_at >= ?) AS average_quiz_score,
                       (SELECT COUNT(DISTINCT ri.resource_id) FROM resource_interactions ri
                        JOIN resources r ON r.resource_id = ri.resource_id AND r.course_id = ?
                        WHERE ri.user_id = u.id AND ri.action = 'complete' AND ri.created_at >= ?) AS completed_resources
                FROM class_members cm
                JOIN users u ON u.id = cm.user_id
                WHERE cm.class_id = ? AND cm.role = 'student' AND cm.status = 'active' AND u.status = 'active'
                ORDER BY u.username ASC
                """,
                (rs, rowNum) -> new StudentInsightRow(
                        rs.getLong("user_id"),
                        rs.getString("username"),
                        rs.getInt("mastery_points"),
                        nullableDouble(rs.getObject("average_mastery")),
                        rs.getInt("activity_count"),
                        toOffsetDateTime(rs.getTimestamp("last_active_at")),
                        nullableDouble(rs.getObject("average_quiz_score")),
                        rs.getInt("completed_resources")),
                courseId,
                courseId,
                courseId,
                Timestamp.from(since.toInstant()),
                courseId,
                courseId,
                Timestamp.from(since.toInstant()),
                courseId,
                Timestamp.from(since.toInstant()),
                classId);
    }

    public List<WeakKnowledgeRow> weakKnowledgePoints(long classId, long courseId) {
        return jdbcTemplate.query(
                """
                SELECT kp.name AS knowledge_point, COUNT(DISTINCT km.student_id) AS student_count,
                       AVG(km.mastery_score) AS average_mastery,
                       SUM(CASE WHEN km.mastery_score < 60 THEN 1 ELSE 0 END) AS at_risk_students,
                       SUM(km.attempts) AS attempts
                FROM knowledge_mastery km
                JOIN knowledge_points kp ON kp.course_id = ? AND kp.name = km.knowledge_point
                JOIN users u ON u.username = km.student_id
                JOIN class_members cm ON cm.user_id = u.id AND cm.class_id = ?
                    AND cm.role = 'student' AND cm.status = 'active'
                GROUP BY kp.name
                ORDER BY average_mastery ASC, at_risk_students DESC, student_count DESC
                LIMIT 8
                """,
                (rs, rowNum) -> new WeakKnowledgeRow(
                        rs.getString("knowledge_point"),
                        rs.getInt("student_count"),
                        rounded(rs.getDouble("average_mastery")),
                        rs.getInt("at_risk_students"),
                        rs.getInt("attempts")),
                courseId,
                classId);
    }

    public List<ActivityTrendRow> activityTrend(
            long classId, long courseId, OffsetDateTime since) {
        return jdbcTemplate.query(
                """
                SELECT CAST(le.occurred_at AS DATE) AS activity_date,
                       COUNT(*) AS event_count,
                       COUNT(DISTINCT le.user_id) AS active_students,
                       AVG(le.evidence_score) AS average_evidence_score
                FROM learning_events le
                JOIN class_members cm ON cm.user_id = le.user_id AND cm.class_id = ?
                    AND cm.role = 'student' AND cm.status = 'active'
                WHERE le.course_id = ? AND le.occurred_at >= ?
                GROUP BY CAST(le.occurred_at AS DATE)
                ORDER BY activity_date ASC
                """,
                (rs, rowNum) -> new ActivityTrendRow(
                        rs.getDate("activity_date").toLocalDate().toString(),
                        rs.getInt("event_count"),
                        rs.getInt("active_students"),
                        rounded(rs.getDouble("average_evidence_score"))),
                classId,
                courseId,
                Timestamp.from(since.toInstant()));
    }

    private Double nullableDouble(Object value) {
        return value instanceof Number number ? rounded(number.doubleValue()) : null;
    }

    private double rounded(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.ofHours(8));
    }

    public record StudentInsightRow(
            long userId,
            String username,
            int masteryPoints,
            Double averageMastery,
            int activityCount,
            OffsetDateTime lastActiveAt,
            Double averageQuizScore,
            int completedResources) {}

    public record WeakKnowledgeRow(
            String knowledgePoint,
            int studentCount,
            double averageMastery,
            int atRiskStudents,
            int attempts) {}

    public record ActivityTrendRow(
            String date,
            int eventCount,
            int activeStudents,
            double averageEvidenceScore) {}
}
