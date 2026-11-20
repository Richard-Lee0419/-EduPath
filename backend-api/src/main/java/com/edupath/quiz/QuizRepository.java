package com.edupath.quiz;

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
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
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
        return insert("quiz_id",
                """
                INSERT INTO quizzes (title, course_id, difficulty, status, created_at)
                VALUES (?, ?, ?, 'active', CURRENT_TIMESTAMP)
                """,
                title,
                courseId,
                difficulty);
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
        return insert("question_id",
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

    public Optional<Integer> findQuizCourse(long quizId) {
        return jdbcTemplate.queryForList("SELECT course_id FROM quizzes WHERE quiz_id = ?", Integer.class, quizId)
                .stream().findFirst();
    }

    public long saveAttempt(
            long quizId,
            String studentId,
            Object answers,
            int score,
            List<String> weakPoints,
            List<String> mistakePatterns,
            String recommendation) {
        return insert("attempt_id",
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
        return attempts(studentId, page, size, null);
    }

    public List<AttemptRow> attempts(String studentId, int page, int size, Integer courseId) {
        return attempts(studentId, page, size, courseId, false);
    }

    public List<AttemptRow> attempts(String studentId, int page, int size, Integer courseId, boolean publicByDefault) {
        var viewer = AuthContext.current();
        var scope = CourseAccess.readableCourses("q.course_id", viewer == null ? null : viewer.userId(),
                viewer == null ? null : viewer.role());
        List<Object> arguments = new ArrayList<>();
        arguments.add(studentId);
        String filter = "";
        if (courseId != null) {
            filter = " AND q.course_id = ?";
            arguments.add(courseId);
        } else if (publicByDefault) {
            filter = " AND NOT EXISTS (SELECT 1 FROM courses c WHERE c.id = q.course_id AND c.visibility = 'class')";
        }
        scope.ifPresent(value -> arguments.addAll(value.arguments()));
        arguments.add(size);
        arguments.add((page - 1) * size);
        return jdbcTemplate.query(
                """
                SELECT qa.attempt_id, qa.quiz_id, qa.student_id, qa.score, qa.weak_points, qa.mistake_patterns,
                       qa.recommendation, qa.submitted_at
                FROM quiz_attempts qa LEFT JOIN quizzes q ON q.quiz_id = qa.quiz_id
                WHERE qa.student_id = ?
                """ + filter + sql(scope) + """
                 ORDER BY qa.submitted_at DESC
                LIMIT ? OFFSET ?
                """,
                this::mapAttempt,
                arguments.toArray());
    }

    public List<WrongQuestionRow> wrongQuestions(String studentId, int page, int size) {
        var viewer = AuthContext.current();
        var scope = CourseAccess.readableCourses("q.course_id", viewer == null ? null : viewer.userId(),
                viewer == null ? null : viewer.role());
        return jdbcTemplate.query(
                """
                SELECT w.id, w.student_id, w.quiz_id, w.question_id, w.knowledge_point, w.mistake_pattern, w.created_at
                FROM wrong_question_book w LEFT JOIN quizzes q ON q.quiz_id = w.quiz_id
                WHERE w.student_id = ?
                """ + sql(scope) + """
                 ORDER BY w.created_at DESC
                LIMIT ? OFFSET ?
                """,
                this::mapWrongQuestion,
                pageArguments(studentId, scope, page, size));
    }

    private String sql(Optional<CourseAccess.Scope> scope) {
        return scope.map(value -> " AND " + value.sql()).orElse("");
    }

    private Object[] pageArguments(Object filter, Optional<CourseAccess.Scope> scope, int page, int size) {
        List<Object> arguments = new ArrayList<>();
        arguments.add(filter);
        scope.ifPresent(value -> arguments.addAll(value.arguments()));
        arguments.add(size);
        arguments.add((page - 1) * size);
        return arguments.toArray();
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

    public List<MasteryRow> classMastery(String studentId, int courseId) {
        return jdbcTemplate.query("""
                SELECT student_id, knowledge_point, attempts, correct_count, mastery_score, updated_at
                FROM class_course_mastery WHERE student_id = ? AND course_id = ?
                ORDER BY mastery_score ASC, updated_at DESC
                """, this::mapMastery, studentId, courseId);
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
        return insert("id",
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
    }

    private long insert(String keyColumn, String sql, Object... values) {
        var keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[] {keyColumn});
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            return statement;
        }, keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("无法读取新建测验记录的主键");
        }
        return id.longValue();
    }

    public List<QuestionBankRow> questionBank(Integer courseId, String status, int page, int size) {
        StringBuilder filter = new StringBuilder();
        List<Object> arguments = new ArrayList<>();
        if (courseId != null) {
            filter.append(" AND course_id = ?");
            arguments.add(courseId);
        }
        if (status != null) {
            filter.append(" AND status = ?");
            arguments.add(status);
        }
        var principal = AuthContext.requirePrincipal();
        if ("teacher".equals(principal.role())) {
            var scope = CourseAccess.manageableCourses("question_bank.course_id", principal.userId(), true);
            filter.append(" AND ").append(scope.sql());
            arguments.addAll(scope.arguments());
        }
        arguments.add(size);
        arguments.add((page - 1) * size);
        return jdbcTemplate.query(
                """
                SELECT id, course_id, knowledge_point, question_type, difficulty, question, options,
                       answer, explanation, status, created_by, created_at
                FROM question_bank
                WHERE 1 = 1
                """ + filter + """
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """,
                this::mapQuestionBank,
                arguments.toArray());
    }

    Optional<Integer> findQuestionBankCourse(long id) {
        return jdbcTemplate.queryForList("SELECT course_id FROM question_bank WHERE id = ?", Integer.class, id)
                .stream().findFirst();
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
