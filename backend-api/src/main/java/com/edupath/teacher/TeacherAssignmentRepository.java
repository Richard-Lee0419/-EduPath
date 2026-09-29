package com.edupath.teacher;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TeacherAssignmentRepository {

    private final JdbcTemplate jdbcTemplate;

    public TeacherAssignmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long create(long classId, long courseId, long teacherUserId, String title,
            String instructions, String resourceId, OffsetDateTime dueAt) {
        jdbcTemplate.update(
                """
                INSERT INTO teacher_assignments
                    (class_id, course_id, teacher_user_id, title, instructions, resource_id, due_at, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'draft', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                classId, courseId, teacherUserId, title, instructions, resourceId,
                dueAt == null ? null : Timestamp.from(dueAt.toInstant()));
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM teacher_assignments", Long.class);
        return id == null ? 0 : id;
    }

    public Optional<AssignmentRow> find(long assignmentId) {
        return jdbcTemplate.query(
                """
                SELECT a.id, a.class_id, a.course_id, a.teacher_user_id, a.title, a.instructions,
                       a.resource_id, a.due_at, a.status, a.published_at, a.created_at, a.updated_at,
                       (SELECT COUNT(*) FROM teacher_assignment_targets t WHERE t.assignment_id = a.id) AS assigned_count,
                       (SELECT COUNT(*) FROM teacher_assignment_targets t WHERE t.assignment_id = a.id AND t.status = 'completed') AS completed_count
                FROM teacher_assignments a WHERE a.id = ?
                """,
                this::mapAssignment, assignmentId).stream().findFirst();
    }

    public List<AssignmentRow> listByClass(long classId) {
        return jdbcTemplate.query(
                """
                SELECT a.id, a.class_id, a.course_id, a.teacher_user_id, a.title, a.instructions,
                       a.resource_id, a.due_at, a.status, a.published_at, a.created_at, a.updated_at,
                       COUNT(t.student_id) AS assigned_count,
                       SUM(CASE WHEN t.status = 'completed' THEN 1 ELSE 0 END) AS completed_count
                FROM teacher_assignments a
                LEFT JOIN teacher_assignment_targets t ON t.assignment_id = a.id
                WHERE a.class_id = ?
                GROUP BY a.id, a.class_id, a.course_id, a.teacher_user_id, a.title, a.instructions,
                         a.resource_id, a.due_at, a.status, a.published_at, a.created_at, a.updated_at
                ORDER BY a.created_at DESC, a.id DESC
                """,
                this::mapAssignment, classId);
    }

    public int publish(long assignmentId) {
        int updated = jdbcTemplate.update(
                "UPDATE teacher_assignments SET status = 'published', published_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'draft'",
                assignmentId);
        if (updated == 0) return 0;
        jdbcTemplate.update(
                """
                INSERT INTO teacher_assignment_targets (assignment_id, student_id, status)
                SELECT ?, cm.user_id, 'assigned'
                FROM teacher_assignments a
                JOIN class_members cm ON cm.class_id = a.class_id
                WHERE a.id = ? AND cm.role = 'student' AND cm.status = 'active'
                  AND NOT EXISTS (SELECT 1 FROM teacher_assignment_targets t WHERE t.assignment_id = ? AND t.student_id = cm.user_id)
                """,
                assignmentId, assignmentId, assignmentId);
        return updated;
    }

    public List<StudentAssignmentRow> listForStudent(long studentId, String status) {
        String normalized = status == null || status.isBlank() ? null : status.trim();
        return jdbcTemplate.query(
                """
                SELECT a.id, a.class_id, c.name AS class_name, a.course_id, co.name AS course_name,
                       a.title, a.instructions, a.resource_id, a.due_at, a.status AS assignment_status,
                       t.status AS target_status, t.completed_at, t.score, a.published_at
                FROM teacher_assignment_targets t
                JOIN teacher_assignments a ON a.id = t.assignment_id
                JOIN classes c ON c.id = a.class_id
                JOIN courses co ON co.id = a.course_id
                WHERE t.student_id = ? AND a.status = 'published'
                  AND (? IS NULL OR t.status = ?)
                ORDER BY CASE WHEN t.status = 'assigned' THEN 0 ELSE 1 END, a.due_at ASC NULLS LAST, a.id DESC
                """,
                this::mapStudentAssignment, studentId, normalized, normalized);
    }

    public int complete(long assignmentId, long studentId, Double score) {
        return jdbcTemplate.update(
                "UPDATE teacher_assignment_targets SET status = 'completed', completed_at = CURRENT_TIMESTAMP, score = ? WHERE assignment_id = ? AND student_id = ? AND EXISTS (SELECT 1 FROM teacher_assignments a WHERE a.id = ? AND a.status = 'published')",
                score, assignmentId, studentId, assignmentId);
    }

    public List<ProgressRow> progress(long assignmentId) {
        return jdbcTemplate.query(
                """
                SELECT t.assignment_id, u.id AS student_id, u.username, t.status, t.completed_at, t.score
                FROM teacher_assignment_targets t JOIN users u ON u.id = t.student_id
                WHERE t.assignment_id = ? ORDER BY u.username ASC
                """,
                (rs, rowNum) -> new ProgressRow(rs.getLong("assignment_id"), rs.getLong("student_id"),
                        rs.getString("username"), rs.getString("status"), toOffsetDateTime(rs.getTimestamp("completed_at")),
                        nullableDouble(rs.getObject("score"))), assignmentId);
    }

    private AssignmentRow mapAssignment(ResultSet rs, int rowNum) throws SQLException {
        return new AssignmentRow(rs.getLong("id"), rs.getLong("class_id"), rs.getLong("course_id"),
                rs.getLong("teacher_user_id"), rs.getString("title"), rs.getString("instructions"),
                rs.getString("resource_id"), toOffsetDateTime(rs.getTimestamp("due_at")), rs.getString("status"),
                toOffsetDateTime(rs.getTimestamp("published_at")), toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at")), rs.getInt("assigned_count"), rs.getInt("completed_count"));
    }

    private StudentAssignmentRow mapStudentAssignment(ResultSet rs, int rowNum) throws SQLException {
        return new StudentAssignmentRow(rs.getLong("id"), rs.getLong("class_id"), rs.getString("class_name"),
                rs.getLong("course_id"), rs.getString("course_name"), rs.getString("title"), rs.getString("instructions"),
                rs.getString("resource_id"), toOffsetDateTime(rs.getTimestamp("due_at")), rs.getString("assignment_status"),
                rs.getString("target_status"), toOffsetDateTime(rs.getTimestamp("completed_at")), nullableDouble(rs.getObject("score")),
                toOffsetDateTime(rs.getTimestamp("published_at")));
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.ofHours(8));
    }

    private Double nullableDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    public record AssignmentRow(long id, long classId, long courseId, long teacherUserId, String title,
            String instructions, String resourceId, OffsetDateTime dueAt, String status,
            OffsetDateTime publishedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt,
            int assignedCount, int completedCount) {}

    public record StudentAssignmentRow(long id, long classId, String className, long courseId, String courseName,
            String title, String instructions, String resourceId, OffsetDateTime dueAt, String assignmentStatus,
            String targetStatus, OffsetDateTime completedAt, Double score, OffsetDateTime publishedAt) {}

    public record ProgressRow(long assignmentId, long studentId, String username, String status,
            OffsetDateTime completedAt, Double score) {}
}
