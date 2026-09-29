package com.edupath.observability;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuditLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(
            String eventType,
            String traceId,
            String actor,
            String method,
            String path,
            Integer status,
            Long durationMs,
            String message) {
        jdbcTemplate.update(
                """
                INSERT INTO audit_logs (event_type, trace_id, actor, method, path, status, duration_ms, message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                eventType,
                traceId,
                actor,
                method,
                path,
                status,
                durationMs,
                message);
    }

    public List<AuditLogRow> list(String actor, String path, Integer status, int page, int size) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, event_type, trace_id, actor, method, path, status, duration_ms, message, created_at FROM audit_logs WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (actor != null && !actor.isBlank()) {
            sql.append(" AND actor = ?");
            args.add(actor.trim());
        }
        if (path != null && !path.isBlank()) {
            sql.append(" AND path LIKE ?");
            args.add("%" + path.trim() + "%");
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add((page - 1) * size);
        return jdbcTemplate.query(sql.toString(), this::mapRow, args.toArray());
    }

    private AuditLogRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AuditLogRow(
                rs.getLong("id"),
                rs.getString("event_type"),
                rs.getString("trace_id"),
                rs.getString("actor"),
                rs.getString("method"),
                rs.getString("path"),
                rs.getInt("status"),
                rs.getLong("duration_ms"),
                rs.getString("message"),
                toOffsetDateTime(rs.getTimestamp("created_at")));
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.ofHours(8));
    }

    public record AuditLogRow(
            long id,
            String eventType,
            String traceId,
            String actor,
            String method,
            String path,
            int status,
            long durationMs,
            String message,
            OffsetDateTime createdAt) {}
}
