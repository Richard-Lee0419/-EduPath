package com.edupath.quiz;

import com.edupath.common.JsonCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class QuizRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JsonCodec jsonCodec;

    public QuizRepository(JdbcTemplate jdbcTemplate, JsonCodec jsonCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
    }

    public long createQuiz(String title, int courseId, String difficulty) {
        jdbcTemplate.update(
                """
                INSERT INTO quizzes (title, course_id, difficulty, status, created_at)
                VALUES (?, ?, ?, 'active', CURRENT_TIMESTAMP)
                """,
                title,
                courseId,
                difficulty);
        Long id = jdbcTemplate.queryForObject("SELECT MAX(quiz_id) FROM quizzes", Long.class);
        return id == null ? 0 : id;
    }

    public long addQuestion(
            long quizId,
            int order,
            String questionType,
            String difficulty,
            String knowledgePoint,
            String question,
            List<String> options,
            String answer,
            String explanation) {
        jdbcTemplate.update(
                """
                INSERT INTO quiz_questions (
                    quiz_id, question_order, question_type, difficulty, knowledge_point,
                    question, options, answer, explanation
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                quizId,
                order,
                questionType,
                difficulty,
                knowledgePoint,
                question,
                jsonCodec.toJson(options),
                answer,
                explanation);
        Long id = jdbcTemplate.queryForObject("SELECT MAX(question_id) FROM quiz_questions", Long.class);
        return id == null ? 0 : id;
    }

    public List<QuestionRow> questions(long quizId) {
        return jdbcTemplate.query(
                """
                SELECT *
                FROM quiz_questions
                WHERE quiz_id = ?
                ORDER BY question_order ASC
                """,
                this::mapQuestion,
                quizId);
    }

    public int courseId(long quizId) {
        Integer courseId = jdbcTemplate.queryForObject(
                "SELECT course_id FROM quizzes WHERE quiz_id = ?", Integer.class, quizId);
        if (courseId == null) {
            throw new IllegalStateException("测验缺少课程信息: " + quizId);
        }
        return courseId;
    }

    public long saveAttempt(
            long quizId,
            String studentId,
            Object answers,
            int score,
            List<String> weakPoints,
            List<String> mistakePatterns,
            String recommendation) {
        jdbcTemplate.update(
                """
                INSERT INTO quiz_attempts (
                    quiz_id, student_id, answers, score, weak_points, mistake_patterns, recommendation, submitted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                quizId,
                studentId,
                jsonCodec.toJson(answers),
                score,
                jsonCodec.toJson(weakPoints),
                jsonCodec.toJson(mistakePatterns),
                recommendation);
        Long id = jdbcTemplate.queryForObject("SELECT MAX(attempt_id) FROM quiz_attempts", Long.class);
        return id == null ? 0 : id;
    }

    public void saveAttemptItem(
            long attemptId,
            long questionId,
            String submittedAnswer,
            String correctAnswer,
            boolean correct) {
        jdbcTemplate.update(
                """
                INSERT INTO quiz_attempt_items (
                    attempt_id, question_id, submitted_answer, correct_answer, correct, created_at
                ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                attemptId,
                questionId,
                submittedAnswer,
                correctAnswer,
                correct);
    }

    public void saveWrongQuestion(
            String studentId,
            long quizId,
            long questionId,
            String knowledgePoint,
            String mistakePattern) {
        jdbcTemplate.update(
                """
                INSERT INTO wrong_question_book (
                    student_id, quiz_id, question_id, knowledge_point, mistake_pattern, created_at
                ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                studentId,
                quizId,
                questionId,
                knowledgePoint,
                mistakePattern);
    }

    public void updateMastery(String studentId, String knowledgePoint, boolean correct) {
        List<MasteryCounter> existing = jdbcTemplate.query(
                """
                SELECT attempts, correct_count, behavior_weight, behavior_score_sum
                FROM knowledge_mastery
                WHERE student_id = ? AND knowledge_point = ?
                """,
                (rs, rowNum) -> new MasteryCounter(
                        rs.getInt("attempts"),
                        rs.getInt("correct_count"),
                        rs.getInt("behavior_weight"),
                        rs.getInt("behavior_score_sum")),
                studentId,
                knowledgePoint);
        if (!existing.isEmpty()) {
            MasteryCounter counter = existing.get(0);
            int attempts = counter.attempts() + 1;
            int correctCount = counter.correctCount() + (correct ? 1 : 0);
            int totalWeight = attempts * 10 + counter.behaviorWeight();
            int masteryScore = Math.round(
                    (correctCount * 1000.0f + counter.behaviorScoreSum()) / totalWeight);
            jdbcTemplate.update(
                    """
                    UPDATE knowledge_mastery
                    SET attempts = ?,
                        correct_count = ?,
                        mastery_score = ?,
                        last_event_type = 'quiz_result',
                        last_event_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE student_id = ? AND knowledge_point = ?
                    """,
                    attempts,
                    correctCount,
                    masteryScore,
                    studentId,
                    knowledgePoint);
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_mastery (
                    student_id, knowledge_point, attempts, correct_count, mastery_score, updated_at
                ) VALUES (?, ?, 1, ?, ?, CURRENT_TIMESTAMP)
                """,
                studentId,
                knowledgePoint,
                correct ? 1 : 0,
                correct ? 100 : 0);
    }

    private record MasteryCounter(
            int attempts, int correctCount, int behaviorWeight, int behaviorScoreSum) {}

    public List<AttemptRow> attempts(String studentId, int page, int size) {
        return jdbcTemplate.query(
                """
                SELECT attempt_id, quiz_id, student_id, score, weak_points, mistake_patterns, recommendation, submitted_at
                FROM quiz_attempts
                WHERE student_id = ?
                ORDER BY submitted_at DESC
                LIMIT ? OFFSET ?
                """,
                this::mapAttempt,
                studentId,
                size,
                (page - 1) * size);
    }

    public List<WrongQuestionRow> wrongQuestions(String studentId, int page, int size) {
        return jdbcTemplate.query(
                """
                SELECT id, student_id, quiz_id, question_id, knowledge_point, mistake_pattern, created_at
                FROM wrong_question_book
                WHERE student_id = ?
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """,
                this::mapWrongQuestion,
                studentId,
                size,
                (page - 1) * size);
    }

    public List<MasteryRow> mastery(String studentId) {
        return jdbcTemplate.query(
                """
                SELECT student_id, knowledge_point, attempts, correct_count, mastery_score, updated_at
                FROM knowledge_mastery
                WHERE student_id = ?
                ORDER BY mastery_score ASC, updated_at DESC
                """,
                this::mapMastery,
                studentId);
    }

    public long createQuestionBankItem(
            int courseId,
            String knowledgePoint,
            String questionType,
            String difficulty,
            String question,
            List<String> options,
            String answer,
            String explanation,
            String createdBy) {
        jdbcTemplate.update(
                """
                INSERT INTO question_bank (
                    course_id, knowledge_point, question_type, difficulty, question, options,
                    answer, explanation, status, created_by, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, CURRENT_TIMESTAMP)
                """,
                courseId,
                knowledgePoint,
                questionType,
                difficulty,
                question,
                jsonCodec.toJson(options),
                answer,
                explanation,
                createdBy);
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM question_bank", Long.class);
        return id == null ? 0 : id;
    }

    public List<QuestionBankRow> questionBank(Integer courseId, String status, int page, int size) {
        if (courseId != null) {
            return jdbcTemplate.query(
                    """
                    SELECT id, course_id, knowledge_point, question_type, difficulty, question, options,
                           answer, explanation, status, created_by, created_at
                    FROM question_bank
                    WHERE course_id = ? AND (? IS NULL OR status = ?)
                    ORDER BY created_at DESC
                    LIMIT ? OFFSET ?
                    """,
                    this::mapQuestionBank,
                    courseId,
                    status,
                    status,
                    size,
                    (page - 1) * size);
        }
        return jdbcTemplate.query(
                """
                SELECT id, course_id, knowledge_point, question_type, difficulty, question, options,
                       answer, explanation, status, created_by, created_at
                FROM question_bank
                WHERE (? IS NULL OR status = ?)
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """,
                this::mapQuestionBank,
                status,
                status,
                size,
                (page - 1) * size);
    }

    public int updateQuestionBankStatus(long id, String status) {
        return jdbcTemplate.update(
                "UPDATE question_bank SET status = ? WHERE id = ?",
                status,
                id);
    }

    private QuestionRow mapQuestion(ResultSet rs, int rowNum) throws SQLException {
        return new QuestionRow(
                rs.getLong("question_id"),
                rs.getLong("quiz_id"),
                rs.getInt("question_order"),
                rs.getString("question_type"),
                rs.getString("difficulty"),
                rs.getString("knowledge_point"),
                rs.getString("question"),
                jsonCodec.stringList(rs.getString("options")),
                rs.getString("answer"),
                rs.getString("explanation"));
    }

    private AttemptRow mapAttempt(ResultSet rs, int rowNum) throws SQLException {
        return new AttemptRow(
                rs.getLong("attempt_id"),
                rs.getLong("quiz_id"),
                rs.getString("student_id"),
                rs.getInt("score"),
                jsonCodec.stringList(rs.getString("weak_points")),
                jsonCodec.stringList(rs.getString("mistake_patterns")),
                rs.getString("recommendation"),
                toOffsetDateTime(rs.getTimestamp("submitted_at")));
    }

    private WrongQuestionRow mapWrongQuestion(ResultSet rs, int rowNum) throws SQLException {
        return new WrongQuestionRow(
                rs.getLong("id"),
                rs.getString("student_id"),
                rs.getLong("quiz_id"),
                rs.getLong("question_id"),
                rs.getString("knowledge_point"),
                rs.getString("mistake_pattern"),
                toOffsetDateTime(rs.getTimestamp("created_at")));
    }

    private MasteryRow mapMastery(ResultSet rs, int rowNum) throws SQLException {
        return new MasteryRow(
                rs.getString("student_id"),
                rs.getString("knowledge_point"),
                rs.getInt("attempts"),
                rs.getInt("correct_count"),
                rs.getInt("mastery_score"),
                toOffsetDateTime(rs.getTimestamp("updated_at")));
    }

    private QuestionBankRow mapQuestionBank(ResultSet rs, int rowNum) throws SQLException {
        return new QuestionBankRow(
                rs.getLong("id"),
                rs.getInt("course_id"),
                rs.getString("knowledge_point"),
                rs.getString("question_type"),
                rs.getString("difficulty"),
                rs.getString("question"),
                jsonCodec.stringList(rs.getString("options")),
                rs.getString("answer"),
                rs.getString("explanation"),
                rs.getString("status"),
                rs.getString("created_by"),
                toOffsetDateTime(rs.getTimestamp("created_at")));
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.ofHours(8));
    }

    public record QuestionRow(
            long questionId,
            long quizId,
            int questionOrder,
            String questionType,
            String difficulty,
            String knowledgePoint,
            String question,
            List<String> options,
            String answer,
            String explanation) {}

    public record AttemptRow(
            long attemptId,
            long quizId,
            String studentId,
            int score,
            List<String> weakPoints,
            List<String> mistakePatterns,
            String recommendation,
            OffsetDateTime submittedAt) {}

    public record WrongQuestionRow(
            long id,
            String studentId,
            long quizId,
            long questionId,
            String knowledgePoint,
            String mistakePattern,
            OffsetDateTime createdAt) {}

    public record MasteryRow(
            String studentId,
            String knowledgePoint,
            int attempts,
            int correctCount,
            int masteryScore,
            OffsetDateTime updatedAt) {}

    public record QuestionBankRow(
            long id,
            int courseId,
            String knowledgePoint,
            String questionType,
            String difficulty,
            String question,
            List<String> options,
            String answer,
            String explanation,
            String status,
            String createdBy,
            OffsetDateTime createdAt) {}
}
