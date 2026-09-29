package com.edupath.agenttask;

import com.edupath.common.JsonCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AgentTaskRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JsonCodec jsonCodec;

    public AgentTaskRepository(JdbcTemplate jdbcTemplate, JsonCodec jsonCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
    }

    @Transactional
    public void createTask(
            String taskId,
            String domain,
            String status,
            int progress,
            String operationType,
            String requestPayload,
            Long actorUserId,
            String actorUsername,
            List<AgentTaskService.AgentStepPlan> plans) {
        jdbcTemplate.update(
                """
                INSERT INTO agent_tasks
                (task_id, domain, status, progress, operation_type, request_payload, actor_user_id, actor_username, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                taskId,
                domain,
                status,
                progress,
                operationType,
                requestPayload,
                actorUserId,
                actorUsername);
        for (int i = 0; i < plans.size(); i++) {
            AgentTaskService.AgentStepPlan plan = plans.get(i);
            jdbcTemplate.update(
                    """
                    INSERT INTO agent_task_steps (task_id, step_order, agent, status, message, updated_at)
                    VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """,
                    taskId,
                    i,
                    plan.agent(),
                    "pending",
                    "等待执行");
        }
    }

    public void updateTask(
            String taskId,
            String status,
            int progress,
            String currentAgent,
            Object result,
            String errorMessage) {
        jdbcTemplate.update(
                """
                UPDATE agent_tasks
                SET status = ?, progress = ?, current_agent = ?, result_payload = ?, error_message = ?, updated_at = CURRENT_TIMESTAMP
                WHERE task_id = ?
                """,
                status,
                progress,
                currentAgent,
                result == null ? null : jsonCodec.toJson(result),
                errorMessage,
                taskId);
    }

    public void updateStep(String taskId, int stepOrder, String status, String message) {
        jdbcTemplate.update(
                """
                UPDATE agent_task_steps
                SET status = ?, message = ?, updated_at = CURRENT_TIMESTAMP
                WHERE task_id = ? AND step_order = ?
                """,
                status,
                message,
                taskId,
                stepOrder);
    }

    public Optional<AgentTaskService.AgentTaskSnapshot> findSnapshot(String taskId) {
        List<TaskRow> tasks = jdbcTemplate.query(
                """
                SELECT task_id, domain, status, progress, current_agent, operation_type, request_payload,
                       result_payload, error_message, created_at, updated_at
                FROM agent_tasks
                WHERE task_id = ?
                """,
                this::mapTask,
                taskId);
        if (tasks.isEmpty()) {
            return Optional.empty();
        }
        TaskRow task = tasks.get(0);
        List<AgentTaskService.AgentStepDto> steps = jdbcTemplate.query(
                """
                SELECT agent, status, message
                FROM agent_task_steps
                WHERE task_id = ?
                ORDER BY step_order ASC
                """,
                (rs, rowNum) -> new AgentTaskService.AgentStepDto(
                        rs.getString("agent"), rs.getString("status"), rs.getString("message")),
                taskId);
        return Optional.of(new AgentTaskService.AgentTaskSnapshot(
                task.taskId(),
                task.status(),
                task.progress(),
                task.currentAgent(),
                steps,
                jsonCodec.map(task.resultPayload()),
                task.errorMessage(),
                task.createdAt(),
                task.updatedAt()));
    }

    public Optional<TaskMetadata> findMetadata(String taskId) {
        return jdbcTemplate
                .query(
                        """
                        SELECT task_id, domain, operation_type, request_payload, status, actor_user_id, actor_username
                        FROM agent_tasks
                        WHERE task_id = ?
                        """,
                        (rs, rowNum) -> new TaskMetadata(
                                rs.getString("task_id"),
                                rs.getString("domain"),
                                rs.getString("operation_type"),
                                rs.getString("request_payload"),
                                rs.getString("status"),
                                rs.getLong("actor_user_id"),
                                rs.getString("actor_username")),
                        taskId)
                .stream()
                .findFirst();
    }

    public List<TaskListItem> listTasks(long actorUserId, String status, int limit, int offset) {
        String statusClause = status == null ? "" : " AND status = ?";
        Object[] arguments = status == null
                ? new Object[] {actorUserId, limit, offset}
                : new Object[] {actorUserId, status, limit, offset};
        return jdbcTemplate.query(
                """
                SELECT task_id, domain, operation_type, status, progress, current_agent,
                       error_message, actor_username, created_at, updated_at
                FROM agent_tasks
                WHERE actor_user_id = ?
                """ + statusClause + " ORDER BY updated_at DESC, task_id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new TaskListItem(
                        rs.getString("task_id"),
                        rs.getString("domain"),
                        rs.getString("operation_type"),
                        rs.getString("status"),
                        rs.getInt("progress"),
                        rs.getString("current_agent"),
                        rs.getString("error_message"),
                        rs.getString("actor_username"),
                        toOffsetDateTime(rs.getTimestamp("created_at")),
                        toOffsetDateTime(rs.getTimestamp("updated_at"))),
                arguments);
    }

    public long countTasks(long actorUserId, String status) {
        if (status == null) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM agent_tasks WHERE actor_user_id = ?", Long.class, actorUserId);
            return count == null ? 0 : count;
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_tasks WHERE actor_user_id = ? AND status = ?",
                Long.class,
                actorUserId,
                status);
        return count == null ? 0 : count;
    }

    public void markStaleRunningTasksFailed() {
        jdbcTemplate.update(
                """
                UPDATE agent_tasks
                SET status = 'failed', current_agent = NULL, error_message = '服务重启后任务未恢复，已标记失败', updated_at = CURRENT_TIMESTAMP
                WHERE status IN ('pending', 'running')
                """);
    }

    private TaskRow mapTask(ResultSet rs, int rowNum) throws SQLException {
        return new TaskRow(
                rs.getString("task_id"),
                rs.getString("domain"),
                rs.getString("status"),
                rs.getInt("progress"),
                rs.getString("current_agent"),
                rs.getString("operation_type"),
                rs.getString("request_payload"),
                rs.getString("result_payload"),
                rs.getString("error_message"),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at")));
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.ofHours(8));
    }

    private record TaskRow(
            String taskId,
            String domain,
            String status,
            int progress,
            String currentAgent,
            String operationType,
            String requestPayload,
            String resultPayload,
            String errorMessage,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record TaskMetadata(
            String taskId,
            String domain,
            String operationType,
            String requestPayload,
            String status,
            long actorUserId,
            String actorUsername) {}

    public record TaskListItem(
            String taskId,
            String domain,
            String operationType,
            String status,
            int progress,
            String currentAgent,
            String errorMessage,
            String actorUsername,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}
}
