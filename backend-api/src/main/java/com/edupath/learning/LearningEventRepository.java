package com.edupath.learning;

import com.edupath.common.JsonCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LearningEventRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JsonCodec jsonCodec;

    public LearningEventRepository(JdbcTemplate jdbcTemplate, JsonCodec jsonCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
    }

    public boolean insert(LearningEventService.LearningEventCommand command, String eventId, String studentId, long userId) {
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO learning_events (
                        event_id, idempotency_key, student_id, user_id, event_type, course_id,
                        knowledge_point, source_type, source_id, action, progress_percent,
                        sequence_no, evidence_weight, evidence_score, metadata, occurred_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """,
                    eventId,
                    command.idempotencyKey(),
                    studentId,
                    userId,
                    command.eventType(),
                    command.courseId(),
                    command.knowledgePoint(),
                    command.sourceType(),
                    command.sourceId(),
                    command.action(),
                    command.progressPercent(),
                    command.sequenceNo(),
                    command.evidenceWeight(),
                    command.evidenceScore(),
                    jsonCodec.toJson(command.metadata() == null ? Map.of() : command.metadata()));
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    public LearningEventService.MasteryUpdate applyBehaviorEvidence(
            String studentId,
            String knowledgePoint,
            int evidenceWeight,
            int evidenceScore,
            String eventType) {
        List<MasteryState> rows = jdbcTemplate.query(
                """
                SELECT attempts, correct_count, behavior_event_count, behavior_weight, behavior_score_sum
                FROM knowledge_mastery
                WHERE student_id = ? AND knowledge_point = ?
                """,
                (rs, rowNum) -> new MasteryState(
                        rs.getInt("attempts"),
                        rs.getInt("correct_count"),
                        rs.getInt("behavior_event_count"),
                        rs.getInt("behavior_weight"),
                        rs.getInt("behavior_score_sum")),
                studentId,
                knowledgePoint);
        int weightedScore = evidenceWeight * evidenceScore;
        if (rows.isEmpty()) {
            int masteryScore = weightedMastery(0, 0, evidenceWeight, weightedScore);
            jdbcTemplate.update(
                    """
                    INSERT INTO knowledge_mastery (
                        student_id, knowledge_point, attempts, correct_count, mastery_score,
                        behavior_event_count, behavior_weight, behavior_score_sum,
                        last_event_type, last_event_at, updated_at
                    ) VALUES (?, ?, 0, 0, ?, 1, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    studentId,
                    knowledgePoint,
                    masteryScore,
                    evidenceWeight,
                    weightedScore,
                    eventType);
            return new LearningEventService.MasteryUpdate(
                    knowledgePoint, 0, masteryScore, 0, evidenceWeight, eventType);
        }
        MasteryState current = rows.get(0);
        int nextBehaviorWeight = current.behaviorWeight() + evidenceWeight;
        int nextBehaviorScoreSum = current.behaviorScoreSum() + weightedScore;
        int masteryScore = weightedMastery(
                current.attempts(), current.correctCount(), nextBehaviorWeight, nextBehaviorScoreSum);
        int previousScore = weightedMastery(
                current.attempts(), current.correctCount(), current.behaviorWeight(), current.behaviorScoreSum());
        jdbcTemplate.update(
                """
                UPDATE knowledge_mastery
                SET behavior_event_count = ?, behavior_weight = ?, behavior_score_sum = ?,
                    mastery_score = ?, last_event_type = ?, last_event_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE student_id = ? AND knowledge_point = ?
                """,
                current.behaviorEventCount() + 1,
                nextBehaviorWeight,
                nextBehaviorScoreSum,
                masteryScore,
                eventType,
                studentId,
                knowledgePoint);
        return new LearningEventService.MasteryUpdate(
                knowledgePoint,
                previousScore,
                masteryScore,
                current.attempts(),
                nextBehaviorWeight,
                eventType);
    }

    public List<LearningEventRow> events(String studentId, int page, int size) {
        return jdbcTemplate.query(
                """
                SELECT event_id, event_type, course_id, knowledge_point, source_type, source_id,
                       action, progress_percent, sequence_no, evidence_weight, evidence_score,
                       metadata, occurred_at
                FROM learning_events
                WHERE student_id = ?
                ORDER BY occurred_at DESC, id DESC
                LIMIT ? OFFSET ?
                """,
                this::mapEvent,
                studentId,
                size,
                (page - 1) * size);
    }

    public List<Integer> completedPathDays(String studentId, String pathId) {
        return jdbcTemplate.query(
                """
                SELECT DISTINCT sequence_no
                FROM learning_events
                WHERE student_id = ? AND source_type = 'learning_path' AND source_id = ?
                  AND action = 'complete' AND sequence_no IS NOT NULL
                ORDER BY sequence_no
                """,
                (rs, rowNum) -> rs.getInt("sequence_no"),
                studentId,
                pathId);
    }

    private LearningEventRow mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new LearningEventRow(
                rs.getString("event_id"),
                rs.getString("event_type"),
                rs.getLong("course_id"),
                rs.getString("knowledge_point"),
                rs.getString("source_type"),
                rs.getString("source_id"),
                rs.getString("action"),
                (Integer) rs.getObject("progress_percent"),
                (Integer) rs.getObject("sequence_no"),
                rs.getInt("evidence_weight"),
                rs.getInt("evidence_score"),
                jsonCodec.map(rs.getString("metadata")),
                toOffsetDateTime(rs.getTimestamp("occurred_at")));
    }

    private int weightedMastery(
            int quizAttempts, int correctCount, int behaviorWeight, int behaviorScoreSum) {
        int totalWeight = quizAttempts * 10 + behaviorWeight;
        if (totalWeight == 0) {
            return 0;
        }
        return Math.round((correctCount * 1000.0f + behaviorScoreSum) / totalWeight);
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.ofHours(8));
    }

    private record MasteryState(
            int attempts,
            int correctCount,
            int behaviorEventCount,
            int behaviorWeight,
            int behaviorScoreSum) {}

    public record LearningEventRow(
            String eventId,
            String eventType,
            long courseId,
            String knowledgePoint,
            String sourceType,
            String sourceId,
            String action,
            Integer progressPercent,
            Integer sequenceNo,
            int evidenceWeight,
            int evidenceScore,
            Map<String, Object> metadata,
            OffsetDateTime occurredAt) {}
}
