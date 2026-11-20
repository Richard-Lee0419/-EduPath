package com.edupath.path;

import com.edupath.common.JsonCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LearningPathRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JsonCodec jsonCodec;

    public LearningPathRepository(JdbcTemplate jdbcTemplate, JsonCodec jsonCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
    }

    public Optional<PathVersion> current(String studentId) {
        return jdbcTemplate
                .query(
                        """
                        SELECT path_id, student_id, version, path_payload, source_task_id, created_at, updated_at
                        FROM learning_paths
                        WHERE student_id = ?
                        ORDER BY version DESC
                        LIMIT 1
                        """,
                        this::mapPath,
                        studentId)
                .stream()
                .findFirst();
    }

    Optional<PathVersion> currentMatching(String studentId, Predicate<PathVersion> filter) {
        try (var rows = jdbcTemplate.queryForStream("""
                SELECT path_id, student_id, version, path_payload, source_task_id, created_at, updated_at
                FROM learning_paths WHERE student_id = ? ORDER BY version DESC, id DESC
                """, this::mapPath, studentId)) {
            return rows.filter(filter).findFirst();
        }
    }

    @Transactional
    public PathVersion save(String studentId, Map<String, Object> path, String sourceTaskId) {
        lockStudent(studentId);
        int nextVersion = current(studentId).map(version -> version.version() + 1).orElse(1);
        return insert(studentId, nextVersion, path, sourceTaskId);
    }

    private PathVersion insert(String studentId, int nextVersion, Map<String, Object> path, String sourceTaskId) {
        String pathId = "path_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update(
                """
                INSERT INTO learning_paths (
                    path_id, student_id, version, path_payload, source_task_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                pathId,
                studentId,
                nextVersion,
                jsonCodec.toJson(path),
                sourceTaskId);
        return jdbcTemplate.queryForObject("""
                SELECT path_id, student_id, version, path_payload, source_task_id, created_at, updated_at
                FROM learning_paths WHERE path_id = ?
                """, this::mapPath, pathId);
    }

    @Transactional
    public Optional<PathVersion> saveIfCurrent(
            String studentId,
            String expectedPathId,
            int expectedVersion,
            Map<String, Object> path,
            String sourceTaskId,
            Predicate<PathVersion> filter) {
        lockStudent(studentId);
        Optional<PathVersion> lockedCurrent = currentMatching(studentId, filter);
        if (lockedCurrent.isEmpty()
                || !lockedCurrent.get().pathId().equals(expectedPathId)
                || lockedCurrent.get().version() != expectedVersion) {
            return Optional.empty();
        }
        int nextVersion = current(studentId).map(version -> version.version() + 1).orElse(1);
        return Optional.of(insert(studentId, nextVersion, path, sourceTaskId));
    }

    private void lockStudent(String studentId) {
        jdbcTemplate.queryForList("SELECT id FROM users WHERE username = ? FOR UPDATE", Long.class, studentId);
    }

    private PathVersion mapPath(ResultSet rs, int rowNum) throws SQLException {
        return new PathVersion(
                rs.getString("path_id"),
                rs.getString("student_id"),
                rs.getInt("version"),
                jsonCodec.map(rs.getString("path_payload")),
                rs.getString("source_task_id"),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at")));
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.ofHours(8));
    }

    public record PathVersion(
            String pathId,
            String studentId,
            int version,
            Map<String, Object> path,
            String sourceTaskId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}
}
