package com.edupath.resource;

import com.edupath.common.JsonCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ResourceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JsonCodec jsonCodec;

    public ResourceRepository(JdbcTemplate jdbcTemplate, JsonCodec jsonCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
    }

    public long nextNumericId() {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM resources", Long.class);
        return max == null ? 1 : max;
    }

    public List<ResourceRow> list(
            Integer courseId,
            String type,
            String status,
            String keyword,
            String sort,
            Long ownerUserId,
            int page,
            int size) {
        QueryParts query = queryParts(courseId, type, status, keyword, ownerUserId);
        query.sql().append(" ORDER BY ").append(sortClause(sort)).append(" LIMIT ? OFFSET ?");
        query.args().add(size);
        query.args().add((page - 1) * size);
        return jdbcTemplate.query(query.sql().toString(), this::mapRow, query.args().toArray());
    }

    public long count(Integer courseId, String type, String status, String keyword, Long ownerUserId) {
        QueryParts query = queryParts(courseId, type, status, keyword, ownerUserId);
        query.sql().replace(0, "SELECT *".length(), "SELECT COUNT(*)");
        Long count = jdbcTemplate.queryForObject(query.sql().toString(), Long.class, query.args().toArray());
        return count == null ? 0 : count;
    }

    public Optional<ResourceRow> find(String resourceIdOrId) {
        return jdbcTemplate
                .query(
                        """
                        SELECT *
                        FROM resources
                        WHERE resource_id = ? OR CAST(id AS CHAR) = ?
                        """,
                        this::mapRow,
                        resourceIdOrId,
                        resourceIdOrId)
                .stream()
                .findFirst();
    }

    public void insert(ResourceCreate create) {
        jdbcTemplate.update(
                """
                INSERT INTO resources (
                    resource_id, title, resource_type, course_id, knowledge_points, difficulty, summary,
                    status, content_format, content, evidence, safety, object_key, object_url, storage_status,
                    tags, created_by, owner_user_id, source_task_id, personalized_reason, estimated_minutes,
                    profile_fingerprint, quality_evaluation, generation_mode, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                create.resourceId(),
                create.title(),
                create.resourceType(),
                create.courseId(),
                jsonCodec.toJson(create.knowledgePoints()),
                create.difficulty(),
                create.summary(),
                create.status(),
                create.contentFormat(),
                create.content(),
                jsonCodec.toJson(create.evidence()),
                jsonCodec.toJson(create.safety()),
                create.objectKey(),
                create.objectUrl(),
                create.storageStatus(),
                jsonCodec.toJson(create.tags()),
                create.createdBy(),
                create.createdBy(),
                create.sourceTaskId(),
                create.personalizedReason(),
                create.estimatedMinutes(),
                create.profileFingerprint(),
                jsonCodec.toJson(create.qualityEvaluation()),
                create.generationMode());
    }

    public List<QualityMetricRow> listQualityMetrics(
            Integer courseId,
            String resourceType,
            String generationMode,
            Long ownerUserId,
            OffsetDateTime since) {
        StringBuilder sql = new StringBuilder(
                "SELECT resource_id, resource_type, generation_mode, quality_evaluation, created_at "
                        + "FROM resources WHERE status <> 'deleted' AND created_at >= ?");
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(since.toInstant()));
        if (ownerUserId != null) {
            sql.append(" AND owner_user_id = ?");
            args.add(ownerUserId);
        }
        if (courseId != null) {
            sql.append(" AND course_id = ?");
            args.add(courseId);
        }
        if (resourceType != null && !resourceType.isBlank()) {
            sql.append(" AND resource_type = ?");
            args.add(resourceType.trim());
        }
        if (generationMode != null && !generationMode.isBlank()) {
            if ("unknown".equals(generationMode.trim())) {
                sql.append(" AND (generation_mode IS NULL OR generation_mode = '')");
            } else {
                sql.append(" AND generation_mode = ?");
                args.add(generationMode.trim());
            }
        }
        sql.append(" ORDER BY created_at ASC");
        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> new QualityMetricRow(
                        rs.getString("resource_id"),
                        rs.getString("resource_type"),
                        normalizedGenerationMode(rs.getString("generation_mode")),
                        jsonCodec.map(rs.getString("quality_evaluation")),
                        toOffsetDateTime(rs.getTimestamp("created_at"))),
                args.toArray());
    }

    public List<ResourceRow> listQualityRegressionCandidates(
            Integer courseId,
            String resourceType,
            String generationMode,
            Long ownerUserId,
            OffsetDateTime since,
            int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM resources WHERE status <> 'deleted' "
                        + "AND created_at >= ? AND quality_evaluation IS NOT NULL AND quality_evaluation <> ''");
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(since.toInstant()));
        if (ownerUserId != null) {
            sql.append(" AND owner_user_id = ?");
            args.add(ownerUserId);
        }
        if (courseId != null) {
            sql.append(" AND course_id = ?");
            args.add(courseId);
        }
        if (resourceType != null && !resourceType.isBlank()) {
            sql.append(" AND resource_type = ?");
            args.add(resourceType.trim());
        }
        if (generationMode != null && !generationMode.isBlank()) {
            if ("unknown".equals(generationMode.trim())) {
                sql.append(" AND (generation_mode IS NULL OR generation_mode = '')");
            } else {
                sql.append(" AND generation_mode = ?");
                args.add(generationMode.trim());
            }
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 50)));
        return jdbcTemplate.query(sql.toString(), this::mapRow, args.toArray());
    }

    public void addInteraction(String resourceId, long userId, String action, Integer rating, Integer progressPercent) {
        jdbcTemplate.update(
                """
                INSERT INTO resource_interactions (resource_id, user_id, action, rating, progress_percent, created_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                resourceId,
                userId,
                action,
                rating,
                progressPercent);
    }

    public InteractionSummary interactionSummary(String resourceId, long userId) {
        return jdbcTemplate.query(
                        """
                        SELECT COUNT(*) AS interaction_count,
                               MAX(progress_percent) AS progress_percent,
                               MAX(rating) AS rating,
                               MAX(created_at) AS last_interaction_at
                        FROM resource_interactions WHERE resource_id = ? AND user_id = ?
                        """,
                        (rs, rowNum) -> new InteractionSummary(
                                rs.getInt("interaction_count"),
                                (Integer) rs.getObject("progress_percent"),
                                (Integer) rs.getObject("rating"),
                                toOffsetDateTime(rs.getTimestamp("last_interaction_at"))),
                        resourceId,
                        userId)
                .stream().findFirst().orElse(new InteractionSummary(0, null, null, null));
    }

    public int updateStatus(String resourceIdOrId, String status) {
        return jdbcTemplate.update(
                """
                UPDATE resources
                SET status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE resource_id = ? OR CAST(id AS CHAR) = ?
                """,
                status,
                resourceIdOrId,
                resourceIdOrId);
    }

    public int softDelete(String resourceIdOrId) {
        return updateStatus(resourceIdOrId, "deleted");
    }

    private QueryParts queryParts(
            Integer courseId, String type, String status, String keyword, Long ownerUserId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM resources WHERE status <> 'deleted'");
        List<Object> args = new ArrayList<>();
        if (ownerUserId != null) {
            sql.append(" AND owner_user_id = ?");
            args.add(ownerUserId);
        }
        if (courseId != null) {
            sql.append(" AND course_id = ?");
            args.add(courseId);
        }
        if (type != null && !type.isBlank()) {
            sql.append(" AND resource_type = ?");
            args.add(type);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (title LIKE ? OR summary LIKE ? OR knowledge_points LIKE ?)");
            String pattern = "%" + keyword.trim() + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        return new QueryParts(sql, args);
    }

    private String sortClause(String sort) {
        if ("title".equals(sort)) {
            return "title ASC";
        }
        if ("difficulty".equals(sort)) {
            return "difficulty ASC, created_at DESC";
        }
        return "created_at DESC";
    }

    private ResourceRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ResourceRow(
                rs.getLong("id"),
                rs.getString("resource_id"),
                rs.getString("title"),
                rs.getString("resource_type"),
                rs.getInt("course_id"),
                jsonCodec.stringList(rs.getString("knowledge_points")),
                rs.getString("difficulty"),
                rs.getString("summary"),
                rs.getString("status"),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                rs.getString("content_format"),
                rs.getString("content"),
                jsonCodec.mapList(rs.getString("evidence")),
                jsonCodec.map(rs.getString("safety")),
                rs.getString("object_key"),
                rs.getString("object_url"),
                rs.getString("storage_status"),
                jsonCodec.stringList(rs.getString("tags")),
                rs.getInt("version"),
                rs.getLong("created_by"),
                rs.getLong("owner_user_id"),
                rs.getString("source_task_id"),
                rs.getString("personalized_reason"),
                (Integer) rs.getObject("estimated_minutes"),
                rs.getString("profile_fingerprint"),
                jsonCodec.map(rs.getString("quality_evaluation")),
                normalizedGenerationMode(rs.getString("generation_mode")));
    }

    private String normalizedGenerationMode(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.ofHours(8));
    }

    private record QueryParts(StringBuilder sql, List<Object> args) {}

    public record ResourceRow(
            long id,
            String resourceId,
            String title,
            String resourceType,
            int courseId,
            List<String> knowledgePoints,
            String difficulty,
            String summary,
            String status,
            OffsetDateTime createdAt,
            String contentFormat,
            String content,
            List<java.util.Map<String, Object>> evidence,
            java.util.Map<String, Object> safety,
            String objectKey,
            String objectUrl,
            String storageStatus,
            List<String> tags,
            int version,
            long createdBy,
            long ownerUserId,
            String sourceTaskId,
            String personalizedReason,
            Integer estimatedMinutes,
            String profileFingerprint,
            java.util.Map<String, Object> qualityEvaluation,
            String generationMode) {}

    public record ResourceCreate(
            String resourceId,
            String title,
            String resourceType,
            int courseId,
            List<String> knowledgePoints,
            String difficulty,
            String summary,
            String status,
            String contentFormat,
            String content,
            List<?> evidence,
            Object safety,
            String objectKey,
            String objectUrl,
            String storageStatus,
            List<String> tags,
            Long createdBy,
            String sourceTaskId,
            String personalizedReason,
            Integer estimatedMinutes,
            String profileFingerprint,
            Object qualityEvaluation,
            String generationMode) {}

    public record QualityMetricRow(
            String resourceId,
            String resourceType,
            String generationMode,
            java.util.Map<String, Object> qualityEvaluation,
            OffsetDateTime createdAt) {}

    public int updateContent(
            String resourceIdOrId,
            String title,
            String summary,
            String content,
            List<String> tags,
            String objectKey,
            String objectUrl,
            String storageStatus) {
        return jdbcTemplate.update(
                """
                UPDATE resources
                SET title = COALESCE(?, title),
                    summary = COALESCE(?, summary),
                    content = COALESCE(?, content),
                    tags = COALESCE(?, tags),
                    object_key = COALESCE(?, object_key),
                    object_url = COALESCE(?, object_url),
                    storage_status = COALESCE(?, storage_status),
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE resource_id = ? OR CAST(id AS CHAR) = ?
                """,
                title,
                summary,
                content,
                tags == null ? null : jsonCodec.toJson(tags),
                objectKey,
                objectUrl,
                storageStatus,
                resourceIdOrId,
                resourceIdOrId);
    }

    public int applyRepairCandidate(
            String resourceId,
            int expectedVersion,
            Map<String, Object> candidate,
            Map<String, Object> safety,
            Map<String, Object> qualityEvaluation,
            String generationMode,
            String objectKey,
            String objectUrl,
            String storageStatus) {
        return jdbcTemplate.update(
                """
                UPDATE resources
                SET title = ?, summary = ?, content_format = ?, content = ?, difficulty = ?,
                    knowledge_points = ?, personalized_reason = ?, estimated_minutes = ?,
                    profile_fingerprint = ?, quality_evaluation = ?, safety = ?, generation_mode = ?,
                    object_key = ?, object_url = ?, storage_status = ?, status = 'published',
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE resource_id = ? AND version = ? AND status <> 'deleted'
                """,
                text(candidate, "title"),
                text(candidate, "summary"),
                text(candidate, "content_format"),
                text(candidate, "content"),
                text(candidate, "difficulty"),
                jsonCodec.toJson(candidate.getOrDefault("knowledge_points", List.of())),
                text(candidate, "personalized_reason"),
                integer(candidate.get("estimated_minutes")),
                text(candidate, "profile_fingerprint"),
                jsonCodec.toJson(qualityEvaluation),
                jsonCodec.toJson(safety),
                generationMode,
                objectKey,
                objectUrl,
                storageStatus,
                resourceId,
                expectedVersion);
    }

    public int restoreRepairSnapshot(
            String resourceId,
            int expectedVersion,
            Map<String, Object> snapshot) {
        return jdbcTemplate.update(
                """
                UPDATE resources
                SET title = ?, summary = ?, content_format = ?, content = ?, difficulty = ?,
                    knowledge_points = ?, personalized_reason = ?, estimated_minutes = ?,
                    profile_fingerprint = ?, quality_evaluation = ?, safety = ?, generation_mode = ?,
                    object_key = ?, object_url = ?, storage_status = ?, status = ?, tags = ?,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE resource_id = ? AND version = ? AND status <> 'deleted'
                """,
                text(snapshot, "title"),
                nullableText(snapshot.get("summary")),
                text(snapshot, "content_format"),
                nullableText(snapshot.get("content")),
                text(snapshot, "difficulty"),
                jsonCodec.toJson(snapshot.getOrDefault("knowledge_points", List.of())),
                nullableText(snapshot.get("personalized_reason")),
                integer(snapshot.get("estimated_minutes")),
                nullableText(snapshot.get("profile_fingerprint")),
                jsonCodec.toJson(snapshot.getOrDefault("quality_evaluation", Map.of())),
                jsonCodec.toJson(snapshot.getOrDefault("safety", Map.of())),
                text(snapshot, "generation_mode"),
                nullableText(snapshot.get("object_key")),
                nullableText(snapshot.get("object_url")),
                nullableText(snapshot.get("storage_status")),
                text(snapshot, "status"),
                jsonCodec.toJson(snapshot.getOrDefault("tags", List.of())),
                resourceId,
                expectedVersion);
    }

    private String text(Map<String, Object> value, String key) {
        String text = nullableText(value.get(key));
        return text == null ? "" : text;
    }

    private String nullableText(Object value) {
        return value == null ? null : value.toString();
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public void addVersion(ResourceRow row, String changedBy) {
        jdbcTemplate.update(
                """
                INSERT INTO resource_versions (resource_id, version, title, summary, content, tags, changed_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                row.resourceId(),
                row.version(),
                row.title(),
                row.summary(),
                row.content(),
                jsonCodec.toJson(row.tags()),
                changedBy);
    }

    public List<ResourceVersionRow> versions(String resourceIdOrId) {
        ResourceRow resource = find(resourceIdOrId).orElse(null);
        if (resource == null) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT resource_id, version, title, summary, tags, changed_by, created_at
                FROM resource_versions
                WHERE resource_id = ?
                ORDER BY version DESC
                """,
                (rs, rowNum) -> new ResourceVersionRow(
                        rs.getString("resource_id"),
                        rs.getInt("version"),
                        rs.getString("title"),
                        rs.getString("summary"),
                        jsonCodec.stringList(rs.getString("tags")),
                        rs.getString("changed_by"),
                        toOffsetDateTime(rs.getTimestamp("created_at"))),
                resource.resourceId());
    }

    public record ResourceVersionRow(
            String resourceId,
            int version,
            String title,
            String summary,
            List<String> tags,
            String changedBy,
            OffsetDateTime createdAt) {}

    public record InteractionSummary(
            int interactionCount, Integer progressPercent, Integer rating, OffsetDateTime lastInteractionAt) {}
}
