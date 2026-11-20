package com.edupath.course;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
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
                SELECT id, code, name, description, status, visibility
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
                        SELECT id, code, name, description, status, visibility
                        FROM courses
                        WHERE id = ? AND status <> 'deleted'
                        """,
                        this::mapCourse,
                        courseId)
                .stream()
                .findFirst();
    }

    public long createCourse(String code, String name, String description, long createdBy) {
        return createCourse(code, name, description, createdBy, "public", "active");
    }

    long createCourse(String code, String name, String description, long createdBy, String visibility, String status) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO courses (code, name, description, created_by, visibility, status)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            statement.setString(1, code);
            statement.setString(2, name);
            statement.setString(3, description);
            statement.setLong(4, createdBy);
            statement.setString(5, visibility);
            statement.setString(6, status);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    public Optional<CourseRow> lockClassCourse(long id) {
        return jdbcTemplate.query("""
                SELECT id, code, name, description, status, visibility FROM courses
                WHERE id = ? AND visibility = 'class' AND status <> 'deleted' FOR UPDATE
                """, this::mapCourse, id).stream().findFirst();
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
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO knowledge_points (course_id, parent_id, name, difficulty, sort_order)
                    VALUES (?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            statement.setLong(1, courseId);
            statement.setObject(2, parentId);
            statement.setString(3, name);
            statement.setString(4, difficulty);
            statement.setInt(5, sortOrder);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    void orderKnowledgePoint(long id, int sortOrder) {
        jdbcTemplate.update("UPDATE knowledge_points SET sort_order = ? WHERE id = ?", sortOrder, id);
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
                rs.getString("status"),
                rs.getString("visibility"));
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

    public record CourseRow(long id, String code, String name, String description, String status, String visibility) {}

    public record KnowledgePointRow(
            long id,
            long courseId,
            Long parentId,
            String name,
            String difficulty,
            int sortOrder,
            String status) {}
}
