package com.edupath.course;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ClassRepository {

    private final JdbcTemplate jdbcTemplate;

    public ClassRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long create(String name, String description, long ownerUserId) {
        jdbcTemplate.update(
                """
                INSERT INTO classes (name, description, owner_user_id, status, created_at, updated_at)
                VALUES (?, ?, ?, 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                name,
                description,
                ownerUserId == 0 ? null : ownerUserId);
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM classes", Long.class);
        return id == null ? 0 : id;
    }

    public List<ClassRow> list() {
        return jdbcTemplate.query(
                """
                SELECT id, name, description, owner_user_id, status, created_at, updated_at
                FROM classes
                WHERE status <> 'deleted'
                ORDER BY created_at DESC, id DESC
                """,
                this::mapClass);
    }

    public List<ClassRow> listOwnedBy(long ownerUserId) {
        return jdbcTemplate.query(
                """
                SELECT id, name, description, owner_user_id, status, created_at, updated_at
                FROM classes
                WHERE status <> 'deleted' AND owner_user_id = ?
                ORDER BY created_at DESC, id DESC
                """,
                this::mapClass,
                ownerUserId);
    }

    public ClassRow find(long classId) {
        return jdbcTemplate
                .query(
                        """
                        SELECT id, name, description, owner_user_id, status, created_at, updated_at
                        FROM classes
                        WHERE id = ? AND status <> 'deleted'
                        """,
                        this::mapClass,
                        classId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    public ClassRow findByName(String name) {
        return jdbcTemplate
                .query(
                        """
                        SELECT id, name, description, owner_user_id, status, created_at, updated_at
                        FROM classes
                        WHERE name = ? AND status <> 'deleted'
                        ORDER BY id ASC
                        """,
                        this::mapClass,
                        name)
                .stream()
                .findFirst()
                .orElse(null);
    }

    public List<Long> activeStudentUserIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE role = 'student' AND status = 'active' ORDER BY id ASC",
                Long.class);
    }

    public void addCourse(long classId, long courseId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM class_courses WHERE class_id = ? AND course_id = ?",
                Long.class,
                classId,
                courseId);
        if (count != null && count > 0) {
            jdbcTemplate.update(
                    "UPDATE class_courses SET status = 'active' WHERE class_id = ? AND course_id = ?",
                    classId,
                    courseId);
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO class_courses (class_id, course_id, status, created_at)
                VALUES (?, ?, 'active', CURRENT_TIMESTAMP)
                """,
                classId,
                courseId);
    }

    public void addMember(long classId, long userId, String role) {
        String normalizedRole = role == null || role.isBlank() ? "student" : role.trim();
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM class_members WHERE class_id = ? AND user_id = ? AND role = ?",
                Long.class,
                classId,
                userId,
                normalizedRole);
        if (count != null && count > 0) {
            jdbcTemplate.update(
                    """
                    UPDATE class_members
                    SET status = 'active'
                    WHERE class_id = ? AND user_id = ? AND role = ?
                    """,
                    classId,
                    userId,
                    normalizedRole);
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO class_members (class_id, user_id, role, status, created_at)
                VALUES (?, ?, ?, 'active', CURRENT_TIMESTAMP)
                """,
                classId,
                userId,
                normalizedRole);
    }

    public List<ClassMemberRow> members(long classId) {
        return jdbcTemplate.query(
                """
                SELECT cm.class_id, cm.user_id, u.username, u.role AS user_role, cm.role AS class_role, cm.status, cm.created_at
                FROM class_members cm
                JOIN users u ON u.id = cm.user_id
                WHERE cm.class_id = ? AND cm.status = 'active'
                ORDER BY cm.created_at ASC, cm.user_id ASC
                """,
                (rs, rowNum) -> new ClassMemberRow(
                        rs.getLong("class_id"),
                        rs.getLong("user_id"),
                        rs.getString("username"),
                        rs.getString("user_role"),
                        rs.getString("class_role"),
                        rs.getString("status"),
                        toOffsetDateTime(rs.getTimestamp("created_at"))),
                classId);
    }

    public List<ClassCourseRow> courses(long classId) {
        return jdbcTemplate.query(
                """
                SELECT cc.class_id, cc.course_id, c.code, c.name, cc.status, cc.created_at
                FROM class_courses cc
                JOIN courses c ON c.id = cc.course_id
                WHERE cc.class_id = ? AND cc.status = 'active'
                ORDER BY cc.created_at ASC, cc.course_id ASC
                """,
                (rs, rowNum) -> new ClassCourseRow(
                        rs.getLong("class_id"),
                        rs.getLong("course_id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("status"),
                        toOffsetDateTime(rs.getTimestamp("created_at"))),
                classId);
    }

    private ClassRow mapClass(ResultSet rs, int rowNum) throws SQLException {
        return new ClassRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getLong("owner_user_id"),
                rs.getString("status"),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at")));
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.ofHours(8));
    }

    public record ClassRow(
            long id,
            String name,
            String description,
            long ownerUserId,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record ClassMemberRow(
            long classId,
            long userId,
            String username,
            String userRole,
            String classRole,
            String status,
            OffsetDateTime createdAt) {}

    public record ClassCourseRow(
            long classId,
            long courseId,
            String courseCode,
            String courseName,
            String status,
            OffsetDateTime createdAt) {}
}
