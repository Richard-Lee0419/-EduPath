package com.edupath.course;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CourseRepository {

    private final JdbcTemplate jdbcTemplate;

    public CourseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<CourseRow> listCourses() {
        return jdbcTemplate.query(
                """
                SELECT id, code, name, description, status
                FROM courses
                WHERE status <> 'deleted'
                ORDER BY id ASC
                """,
                this::mapCourse);
    }

    public Optional<CourseRow> findCourse(long courseId) {
        return jdbcTemplate
                .query(
                        """
                        SELECT id, code, name, description, status
                        FROM courses
                        WHERE id = ? AND status <> 'deleted'
                        """,
                        this::mapCourse,
                        courseId)
                .stream()
                .findFirst();
    }

    public long createCourse(String code, String name, String description, long createdBy) {
        jdbcTemplate.update(
                """
                INSERT INTO courses (code, name, description, status, created_by, created_at, updated_at)
                VALUES (?, ?, ?, 'active', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                code,
                name,
                description,
                createdBy);
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM courses", Long.class);
        return id == null ? 0 : id;
    }

    public int updateCourse(long courseId, String name, String description, String status) {
        return jdbcTemplate.update(
                """
                UPDATE courses
                SET name = COALESCE(?, name),
                    description = COALESCE(?, description),
                    status = COALESCE(?, status),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                name,
                description,
                status,
                courseId);
    }

    public List<KnowledgePointRow> listKnowledgePoints(long courseId) {
        return jdbcTemplate.query(
                """
                SELECT id, course_id, parent_id, name, difficulty, sort_order, status
                FROM knowledge_points
                WHERE course_id = ? AND status <> 'deleted'
                ORDER BY parent_id ASC, sort_order ASC, id ASC
                """,
                this::mapPoint,
                courseId);
    }

    public long createKnowledgePoint(
            long courseId,
            Long parentId,
            String name,
            String difficulty,
            int sortOrder) {
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_points (course_id, parent_id, name, difficulty, sort_order, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                courseId,
                parentId,
                name,
                difficulty,
                sortOrder);
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM knowledge_points", Long.class);
        return id == null ? 0 : id;
    }

    public int updateKnowledgePoint(long id, String name, String difficulty, String status) {
        return jdbcTemplate.update(
                """
                UPDATE knowledge_points
                SET name = COALESCE(?, name),
                    difficulty = COALESCE(?, difficulty),
                    status = COALESCE(?, status),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                name,
                difficulty,
                status,
                id);
    }

    private CourseRow mapCourse(ResultSet rs, int rowNum) throws SQLException {
        return new CourseRow(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("status"));
    }

    private KnowledgePointRow mapPoint(ResultSet rs, int rowNum) throws SQLException {
        long parent = rs.getLong("parent_id");
        return new KnowledgePointRow(
                rs.getLong("id"),
                rs.getLong("course_id"),
                rs.wasNull() ? null : parent,
                rs.getString("name"),
                rs.getString("difficulty"),
                rs.getInt("sort_order"),
                rs.getString("status"));
    }

    public record CourseRow(long id, String code, String name, String description, String status) {}

    public record KnowledgePointRow(
            long id,
            long courseId,
            Long parentId,
            String name,
            String difficulty,
            int sortOrder,
            String status) {}
}
