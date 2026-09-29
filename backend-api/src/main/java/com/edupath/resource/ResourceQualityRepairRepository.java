package com.edupath.resource;

import com.edupath.common.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ResourceQualityRepairRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JsonCodec jsonCodec;

    public ResourceQualityRepairRepository(JdbcTemplate jdbcTemplate, JsonCodec jsonCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
    }

    public String enqueue(
            String sourceTaskId,
            ResourceRepository.ResourceRow resource,
            List<String> alertCodes,
            Map<String, Object> regressionResult) {
        List<String> regressedDimensions = stringList(regressionResult.get("regressed_dimensions"));
        Map<String, Object> baseline = map(regressionResult.get("baseline_evaluation"));
        Map<String, Object> current = map(regressionResult.get("current_evaluation"));
        double scoreDelta = number(regressionResult.get("score_delta"));
        String signature = String.join(
                "|",
                resource.resourceId(),
                jsonCodec.toJson(baseline),
                jsonCodec.toJson(current),
                String.join(",", regressedDimensions));
        String dedupeKey = "repair:" + UUID.nameUUIDFromBytes(signature.getBytes(StandardCharsets.UTF_8));
        Optional<String> existing = findRepairIdByDedupeKey(dedupeKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        String repairId = "repair_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO resource_quality_repairs (
                        repair_id, dedupe_key, resource_id, source_task_id, course_id, owner_user_id,
                        trigger_alert_codes, regressed_dimensions, baseline_evaluation, current_evaluation,
                        score_delta, status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending_review', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    repairId,
                    dedupeKey,
                    resource.resourceId(),
                    sourceTaskId,
                    resource.courseId(),
                    resource.ownerUserId() > 0 ? resource.ownerUserId() : null,
                    jsonCodec.toJson(alertCodes),
                    jsonCodec.toJson(regressedDimensions),
                    jsonCodec.toJson(baseline),
                    jsonCodec.toJson(current),
                    scoreDelta);
            return repairId;
        } catch (DuplicateKeyException duplicate) {
            return findRepairIdByDedupeKey(dedupeKey).orElseThrow(() -> duplicate);
        }
    }

    public List<RepairRow> list(
            Integer courseId,
            String status,
            Long ownerUserId,
            Long managerUserId,
            int page,
            int size) {
        QueryParts query = queryParts(courseId, status, ownerUserId, managerUserId);
        query.sql().append(" ORDER BY q.created_at DESC LIMIT ? OFFSET ?");
        query.args().add(size);
        query.args().add((page - 1) * size);
        return jdbcTemplate.query(query.sql().toString(), this::mapRow, query.args().toArray());
    }

    public long count(Integer courseId, String status, Long ownerUserId, Long managerUserId) {
        QueryParts query = queryParts(courseId, status, ownerUserId, managerUserId);
        query.sql().replace(0, "SELECT q.*, r.title, r.resource_type".length(), "SELECT COUNT(*)");
        Long value = jdbcTemplate.queryForObject(query.sql().toString(), Long.class, query.args().toArray());
        return value == null ? 0 : value;
    }

    public Optional<RepairRow> find(String repairId) {
        return jdbcTemplate.query(
                        """
                        SELECT q.*, r.title, r.resource_type
                        FROM resource_quality_repairs q
                        JOIN resources r ON r.resource_id = q.resource_id
                        WHERE q.repair_id = ?
                        """,
                        this::mapRow,
                        repairId)
                .stream()
                .findFirst();
    }

    public int decide(String repairId, String status, long reviewedBy, String note) {
        return jdbcTemplate.update(
                """
                UPDATE resource_quality_repairs
                SET status = ?, reviewed_by = ?, review_note = ?, reviewed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE repair_id = ? AND status = 'pending_review'
                """,
                status,
                reviewedBy,
                note,
                repairId);
    }

    public int claimForExecution(String repairId) {
        return jdbcTemplate.update(
                """
                UPDATE resource_quality_repairs
                SET status = 'in_progress', publish_ready = FALSE, execution_error = NULL,
                    executed_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE repair_id = ? AND status = 'approved'
                """,
                repairId);
    }

    public void bindExecutionTask(String repairId, String taskId) {
        jdbcTemplate.update(
                """
                UPDATE resource_quality_repairs
                SET execution_task_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE repair_id = ? AND status = 'in_progress'
                """,
                taskId,
                repairId);
    }

    public void releaseExecutionClaim(String repairId) {
        jdbcTemplate.update(
                """
                UPDATE resource_quality_repairs
                SET status = 'approved', execution_task_id = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE repair_id = ? AND status = 'in_progress' AND execution_task_id IS NULL
                """,
                repairId);
    }

    public int completeExecution(
            String repairId,
            String taskId,
            Map<String, Object> candidateResource,
            Map<String, Object> candidateSafety,
            Map<String, Object> candidateQuality,
            Map<String, Object> modelRuntime,
            String generationMode,
            boolean publishReady,
            int candidateBaseVersion) {
        return jdbcTemplate.update(
                """
                UPDATE resource_quality_repairs
                SET status = 'completed', execution_task_id = ?, candidate_resource = ?,
                    candidate_safety = ?, candidate_quality_evaluation = ?, candidate_model_runtime = ?,
                    candidate_generation_mode = ?, publish_ready = ?, candidate_base_version = ?, execution_error = NULL,
                    executed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE repair_id = ? AND status = 'in_progress'
                """,
                taskId,
                jsonCodec.toJson(candidateResource),
                jsonCodec.toJson(candidateSafety),
                jsonCodec.toJson(candidateQuality),
                jsonCodec.toJson(modelRuntime),
                generationMode,
                publishReady,
                candidateBaseVersion,
                repairId);
    }

    public int markPublished(
            String repairId,
            Map<String, Object> previousSnapshot,
            int previousVersion,
            int publishedVersion,
            long publishedBy,
            String publishNote) {
        return jdbcTemplate.update(
                """
                UPDATE resource_quality_repairs
                SET status = 'published', previous_resource_snapshot = ?, previous_resource_version = ?,
                    published_resource_version = ?, published_by = ?, publish_note = ?,
                    published_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE repair_id = ? AND status = 'completed' AND publish_ready = TRUE
                    AND candidate_base_version = ?
                """,
                jsonCodec.toJson(previousSnapshot),
                previousVersion,
                publishedVersion,
                publishedBy,
                publishNote,
                repairId,
                previousVersion);
    }

    public int markRolledBack(
            String repairId,
            int publishedVersion,
            int rollbackVersion,
            long rolledBackBy,
            String rollbackNote) {
        return jdbcTemplate.update(
                """
                UPDATE resource_quality_repairs
                SET status = 'rolled_back', rollback_resource_version = ?, rolled_back_by = ?,
                    rollback_note = ?, rolled_back_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE repair_id = ? AND status = 'published' AND published_resource_version = ?
                """,
                rollbackVersion,
                rolledBackBy,
                rollbackNote,
                repairId,
                publishedVersion);
    }

    public void failExecution(String repairId, String taskId, String error) {
        jdbcTemplate.update(
                """
                UPDATE resource_quality_repairs
                SET status = 'failed', execution_task_id = ?, publish_ready = FALSE,
                    execution_error = ?, executed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE repair_id = ? AND status = 'in_progress'
                """,
                taskId,
                error,
                repairId);
    }

    private Optional<String> findRepairIdByDedupeKey(String dedupeKey) {
        return jdbcTemplate.query(
                        "SELECT repair_id FROM resource_quality_repairs WHERE dedupe_key = ?",
                        (rs, rowNum) -> rs.getString("repair_id"),
                        dedupeKey)
                .stream()
                .findFirst();
    }

    private QueryParts queryParts(
            Integer courseId, String status, Long ownerUserId, Long managerUserId) {
        StringBuilder sql = new StringBuilder(
                "SELECT q.*, r.title, r.resource_type FROM resource_quality_repairs q "
                        + "JOIN resources r ON r.resource_id = q.resource_id WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (ownerUserId != null) {
            sql.append(" AND q.owner_user_id = ?");
            args.add(ownerUserId);
        }
        if (managerUserId != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM user_course_roles ucr "
                    + "WHERE ucr.user_id = ? AND ucr.course_id = q.course_id)");
            args.add(managerUserId);
        }
        if (courseId != null) {
            sql.append(" AND q.course_id = ?");
            args.add(courseId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND q.status = ?");
            args.add(status.trim());
        }
        return new QueryParts(sql, args);
    }

    private RepairRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RepairRow(
                rs.getLong("id"),
                rs.getString("repair_id"),
                rs.getString("resource_id"),
                rs.getString("title"),
                rs.getString("resource_type"),
                rs.getString("source_task_id"),
                rs.getInt("course_id"),
                (Long) rs.getObject("owner_user_id"),
                jsonCodec.stringList(rs.getString("trigger_alert_codes")),
                jsonCodec.stringList(rs.getString("regressed_dimensions")),
                jsonCodec.map(rs.getString("baseline_evaluation")),
                jsonCodec.map(rs.getString("current_evaluation")),
                rs.getDouble("score_delta"),
                rs.getString("status"),
                (Long) rs.getObject("reviewed_by"),
                rs.getString("review_note"),
                toOffsetDateTime(rs.getTimestamp("reviewed_at")),
                rs.getString("execution_task_id"),
                jsonCodec.map(rs.getString("candidate_resource")),
                jsonCodec.map(rs.getString("candidate_safety")),
                jsonCodec.map(rs.getString("candidate_quality_evaluation")),
                jsonCodec.map(rs.getString("candidate_model_runtime")),
                rs.getString("candidate_generation_mode"),
                rs.getBoolean("publish_ready"),
                rs.getString("execution_error"),
                toOffsetDateTime(rs.getTimestamp("executed_at")),
                (Integer) rs.getObject("candidate_base_version"),
                jsonCodec.map(rs.getString("previous_resource_snapshot")),
                (Integer) rs.getObject("previous_resource_version"),
                (Integer) rs.getObject("published_resource_version"),
                (Long) rs.getObject("published_by"),
                rs.getString("publish_note"),
                toOffsetDateTime(rs.getTimestamp("published_at")),
                (Integer) rs.getObject("rollback_resource_version"),
                (Long) rs.getObject("rolled_back_by"),
                rs.getString("rollback_note"),
                toOffsetDateTime(rs.getTimestamp("rolled_back_at")),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at")));
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> normalized = new java.util.LinkedHashMap<>();
        raw.forEach((key, item) -> normalized.put(String.valueOf(key), item));
        return normalized;
    }

    private double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0.0 : Double.parseDouble(value.toString());
        } catch (NumberFormatException exception) {
            return 0.0;
        }
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.ofHours(8));
    }

    private record QueryParts(StringBuilder sql, List<Object> args) {}

    public record RepairRow(
            long id,
            String repairId,
            String resourceId,
            String resourceTitle,
            String resourceType,
            String sourceTaskId,
            int courseId,
            Long ownerUserId,
            List<String> triggerAlertCodes,
            List<String> regressedDimensions,
            Map<String, Object> baselineEvaluation,
            Map<String, Object> currentEvaluation,
            double scoreDelta,
            String status,
            Long reviewedBy,
            String reviewNote,
            OffsetDateTime reviewedAt,
            String executionTaskId,
            Map<String, Object> candidateResource,
            Map<String, Object> candidateSafety,
            Map<String, Object> candidateQualityEvaluation,
            Map<String, Object> candidateModelRuntime,
            String candidateGenerationMode,
            boolean publishReady,
            String executionError,
            OffsetDateTime executedAt,
            Integer candidateBaseVersion,
            Map<String, Object> previousResourceSnapshot,
            Integer previousResourceVersion,
            Integer publishedResourceVersion,
            Long publishedBy,
            String publishNote,
            OffsetDateTime publishedAt,
            Integer rollbackResourceVersion,
            Long rolledBackBy,
            String rollbackNote,
            OffsetDateTime rolledBackAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}
}
