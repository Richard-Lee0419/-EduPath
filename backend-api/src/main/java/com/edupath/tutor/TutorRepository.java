package com.edupath.tutor;

import com.edupath.auth.AuthContext;
import com.edupath.common.JsonCodec;
import com.edupath.course.CourseAccess;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TutorRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JsonCodec jsonCodec;

    public TutorRepository(JdbcTemplate jdbcTemplate, JsonCodec jsonCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
    }

    public String createSession(String studentId, long courseId, String title) {
        String sessionId = "tutor_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update(
                """
                INSERT INTO tutor_sessions (session_id, student_id, course_id, title, created_at, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                sessionId,
                studentId,
                courseId,
                title);
        return sessionId;
    }

    public void addMessage(String sessionId, String role, String content, Object evidence) {
        jdbcTemplate.update(
                """
                INSERT INTO tutor_messages (session_id, role, content, evidence, created_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                sessionId,
                role,
                content,
                evidence == null ? null : jsonCodec.toJson(evidence));
        jdbcTemplate.update("UPDATE tutor_sessions SET updated_at = CURRENT_TIMESTAMP WHERE session_id = ?", sessionId);
    }

    public List<SessionRow> sessions(String studentId, int page, int size) {
        var viewer = AuthContext.current();
        var scope = CourseAccess.readableCourses("tutor_sessions.course_id", viewer == null ? null : viewer.userId(),
                viewer == null ? null : viewer.role());
        List<Object> arguments = new ArrayList<>();
        arguments.add(studentId);
        scope.ifPresent(value -> arguments.addAll(value.arguments()));
        arguments.add(size);
        arguments.add((page - 1) * size);
        return jdbcTemplate.query(
                """
                SELECT session_id, student_id, course_id, title, created_at, updated_at
                FROM tutor_sessions
                WHERE student_id = ?
                """ + scope.map(value -> " AND " + value.sql()).orElse("") + """
                 ORDER BY updated_at DESC
                LIMIT ? OFFSET ?
                """,
                this::mapSession,
                arguments.toArray());
    }

    public Optional<SessionRow> findSession(String sessionId) {
        return jdbcTemplate
                .query(
                        """
                        SELECT session_id, student_id, course_id, title, created_at, updated_at
                        FROM tutor_sessions
                        WHERE session_id = ?
                        """,
                        this::mapSession,
                        sessionId)
                .stream()
                .findFirst();
    }

    private SessionRow mapSession(ResultSet rs, int rowNum) throws SQLException {
        return new SessionRow(
                rs.getString("session_id"),
                rs.getString("student_id"),
                rs.getLong("course_id"),
                rs.getString("title"),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at")));
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.ofHours(8));
    }

    public record SessionRow(
            String sessionId,
            String studentId,
            long courseId,
            String title,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}
}
