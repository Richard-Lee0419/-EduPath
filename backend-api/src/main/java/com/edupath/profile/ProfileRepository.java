package com.edupath.profile;

import com.edupath.common.JsonCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProfileRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JsonCodec jsonCodec;

    public ProfileRepository(JdbcTemplate jdbcTemplate, JsonCodec jsonCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
    }

    public Optional<ProfileVersion> current(String studentId) {
        return jdbcTemplate
                .query(
                        """
                        SELECT *
                        FROM profile_versions
                        WHERE student_id = ?
                        ORDER BY version DESC
                        LIMIT 1
                        """,
                        this::mapVersion,
                        studentId)
                .stream()
                .findFirst();
    }

    public ProfileVersion save(String studentId, Map<String, Object> profile, String sourceTaskId, String updatedReason) {
        int nextVersion = current(studentId).map(value -> value.version() + 1).orElse(1);
        jdbcTemplate.update(
                """
                INSERT INTO profile_versions (student_id, version, profile_payload, source_task_id, updated_reason, created_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                studentId,
                nextVersion,
                jsonCodec.toJson(profile),
                sourceTaskId,
                updatedReason);
        return current(studentId).orElseThrow();
    }

    private ProfileVersion mapVersion(ResultSet rs, int rowNum) throws SQLException {
        return new ProfileVersion(
                rs.getLong("id"),
                rs.getString("student_id"),
                rs.getInt("version"),
                jsonCodec.map(rs.getString("profile_payload")),
                rs.getString("source_task_id"),
                rs.getString("updated_reason"),
                toOffsetDateTime(rs.getTimestamp("created_at")));
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.ofHours(8));
    }

    public record ProfileVersion(
            long id,
            String studentId,
            int version,
            Map<String, Object> profile,
            String sourceTaskId,
            String updatedReason,
            OffsetDateTime createdAt) {}
}
