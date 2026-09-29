package com.edupath.quiz;

import com.edupath.ai.AiAgentClient;
import com.edupath.auth.AuthContext;
import com.edupath.common.ExternalServiceException;
import com.edupath.course.CourseCatalogService;
import com.edupath.path.LearningPathService;
import com.edupath.profile.ProfileService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class QuizService {

    private final CourseCatalogService courseCatalogService;
    private final QuizRepository quizRepository;
    private final ProfileService profileService;
    private final AiAgentClient aiAgentClient;
    private final LearningPathService learningPathService;

    public QuizService(
            CourseCatalogService courseCatalogService,
            QuizRepository quizRepository,
            ProfileService profileService,
            AiAgentClient aiAgentClient,
            LearningPathService learningPathService) {
        this.courseCatalogService = courseCatalogService;
        this.quizRepository = quizRepository;
        this.profileService = profileService;
        this.aiAgentClient = aiAgentClient;
        this.learningPathService = learningPathService;
    }

    @Transactional
    public QuizResponse generate(QuizGenerateRequest request) {
        if (request == null) {
            request = new QuizGenerateRequest(1, List.of(), List.of(), "basic", 3);
        }
        int courseId = request.courseId() == null ? 1 : request.courseId();
        int questionCount = request.questionCount() == null ? 3 : request.questionCount();
        if (questionCount < 1 || questionCount > 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question_count 必须在 1 到 3 之间");
        }
        String difficulty = request.difficulty() == null || request.difficulty().isBlank()
                ? "basic"
                : request.difficulty();
        List<String> knowledgePoints = courseCatalogService.resolveKnowledgePointNames(
                courseId, request.knowledgePointIds(), request.knowledgePoints());
        QuizGenerateRequest normalizedRequest = new QuizGenerateRequest(
                courseId,
                request.knowledgePointIds() == null ? List.of() : request.knowledgePointIds(),
                knowledgePoints,
                difficulty,
                questionCount);
        Map<String, Object> aiResponse = aiAgentClient.generateQuiz(
                normalizedRequest,
                knowledgePoints,
                profileService.currentProfileSnapshot());
        Map<String, Object> safety = requireMap(aiResponse.get("safety"), "小测生成未返回 SafetyAgent 结果");
        if (!Boolean.TRUE.equals(safety.get("passed"))) {
            throw new ExternalServiceException("AI 服务", "小测题未通过 SafetyAgent 审查");
        }
        Map<String, Object> modelRuntime = requireMap(aiResponse.get("model_runtime"), "小测生成未返回模型运行记录");
        String generationMode = requireText(aiResponse.get("generation_mode"), "小测生成未返回 generation_mode");
        Map<String, Object> quizDraft = requireMap(aiResponse.get("quiz"), "小测生成未返回 quiz 结构");
        List<?> rawQuestions = requireList(quizDraft.get("questions"), "小测生成未返回 questions");
        if (rawQuestions.size() != questionCount) {
            throw new ExternalServiceException("AI 服务", "小测题量与 question_count 不一致");
        }
        List<?> evidence = requireList(aiResponse.get("evidence"), "小测生成未返回 RAG 证据");
        Set<String> evidenceIds = evidenceIds(evidence);
        String title = requireText(quizDraft.get("title"), "小测生成未返回 title");
        long quizId = quizRepository.createQuiz(title, courseId, difficulty);
        List<QuizQuestion> questions = new ArrayList<>();
        for (int i = 0; i < rawQuestions.size(); i++) {
            Map<String, Object> item = requireMap(rawQuestions.get(i), "小测包含非法题目结构");
            int order = intValue(item.get("question_order"), -1);
            if (order != i + 1 || !"single_choice".equals(String.valueOf(item.get("type")))) {
                throw new ExternalServiceException("AI 服务", "小测题号或题型不合法");
            }
            String point = requireText(item.get("knowledge_point"), "小测题目缺少 knowledge_point");
            String questionText = requireText(item.get("stem"), "小测题目缺少 stem");
            List<String> options = stringList(item.get("options"));
            if (options.size() != 4 || new HashSet<>(options).size() != 4) {
                throw new ExternalServiceException("AI 服务", "单选题必须包含 4 个不同选项");
            }
            String answer = requireText(item.get("answer"), "小测题目缺少 answer").toUpperCase(Locale.ROOT);
            if (!Set.of("A", "B", "C", "D").contains(answer)) {
                throw new ExternalServiceException("AI 服务", "小测题目 answer 必须为 A/B/C/D");
            }
            String explanation = requireText(item.get("explanation"), "小测题目缺少 explanation");
            List<String> chunkIds = stringList(item.get("evidence_chunk_ids"));
            if (chunkIds.isEmpty() || chunkIds.stream().anyMatch(id -> !evidenceIds.contains(id))) {
                throw new ExternalServiceException("AI 服务", "小测题目引用了本次检索范围外的 chunk_id");
            }
            long questionId = quizRepository.addQuestion(
                    quizId,
                    order,
                    "single_choice",
                    difficulty,
                    point,
                    questionText,
                    options,
                    answer,
                    explanation);
            questions.add(new QuizQuestion(
                    questionId,
                    "single_choice",
                    difficulty,
                    point,
                    questionText,
                    options,
                    chunkIds));
        }
        return new QuizResponse(
                quizId,
                title,
                questions,
                evidence,
                safety,
                generationMode,
                modelRuntime,
                String.valueOf(aiResponse.getOrDefault("task_id", "")));
    }

    @Transactional
    public QuizSubmitResult submit(QuizSubmitRequest request) {
        List<QuizRepository.QuestionRow> questions = quizRepository.questions(request.quizId());
        if (questions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "测验不存在: " + request.quizId());
        }
        if (questions.size() > 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前闭环只允许提交 3 道题以内的小测");
        }
        Map<Long, String> submitted = normalizeAnswers(request.answers());
        List<QuestionResult> questionResults = new ArrayList<>();
        long correct = 0;
        for (QuizRepository.QuestionRow question : questions) {
            String submittedAnswer = submitted.getOrDefault(question.questionId(), "");
            boolean isCorrect = isCorrectAnswer(question, submittedAnswer);
            if (isCorrect) {
                correct++;
            }
            questionResults.add(new QuestionResult(
                    question.questionId(),
                    question.knowledgePoint(),
                    question.question(),
                    submittedAnswer,
                    question.answer(),
                    question.explanation() == null ? "依据课程证据核对答案。" : question.explanation(),
                    isCorrect));
        }
        int score = questions.isEmpty()
                ? 0
                : (int) Math.round(correct * 100.0 / questions.size());
        Map<String, Object> evaluationPayload = new LinkedHashMap<>();
        evaluationPayload.put("quiz_id", request.quizId());
        int courseId = quizRepository.courseId(request.quizId());
        evaluationPayload.put("course_id", courseId);
        evaluationPayload.put("score", score);
        evaluationPayload.put("question_results", questionResults.stream()
                .map(result -> Map.<String, Object>of(
                        "question_id", result.questionId(),
                        "knowledge_point", result.knowledgePoint(),
                        "stem", result.stem(),
                        "submitted_answer", result.submittedAnswer(),
                        "correct_answer", result.correctAnswer(),
                        "explanation", result.explanation(),
                        "correct", result.correct()))
                .toList());
        evaluationPayload.put("student_profile", profileService.currentProfileSnapshot());
        Map<String, Object> aiResponse = aiAgentClient.analyzeEvaluation(evaluationPayload);
        Map<String, Object> safety = requireMap(aiResponse.get("safety"), "评估未返回 SafetyAgent 结果");
        if (!Boolean.TRUE.equals(safety.get("passed"))) {
            throw new ExternalServiceException("AI 服务", "评估未通过 SafetyAgent 审查");
        }
        Map<String, Object> modelRuntime = requireMap(aiResponse.get("model_runtime"), "评估未返回模型运行记录");
        String generationMode = requireText(aiResponse.get("generation_mode"), "评估未返回 generation_mode");
        Map<String, Object> evaluation = requireMap(aiResponse.get("evaluation"), "评估未返回 evaluation 结构");
        if (intValue(evaluation.get("overall_score"), -1) != score) {
            throw new ExternalServiceException("AI 服务", "EvaluationAgent 修改了确定性评分");
        }
        List<String> expectedWeakPoints = questionResults.stream()
                .filter(item -> !item.correct())
                .map(QuestionResult::knowledgePoint)
                .distinct()
                .toList();
        List<String> weakPoints = stringList(evaluation.get("weak_points"));
        if (!new HashSet<>(weakPoints).equals(new HashSet<>(expectedWeakPoints))) {
            throw new ExternalServiceException("AI 服务", "EvaluationAgent 返回的薄弱点与错题不一致");
        }
        List<String> mistakePatterns = stringList(evaluation.get("mistake_patterns"));
        List<String> nextActions = stringList(evaluation.get("next_actions"));
        if (nextActions.isEmpty()) {
            throw new ExternalServiceException("AI 服务", "EvaluationAgent 未返回下一步建议");
        }
        String recommendation = String.join("；", nextActions);
        String studentId = AuthContext.currentStudentId();
        long attemptId = quizRepository.saveAttempt(
                request.quizId(), studentId, request.answers(), score, weakPoints, mistakePatterns, recommendation);
        for (QuestionResult result : questionResults) {
            quizRepository.saveAttemptItem(
                    attemptId, result.questionId(), result.submittedAnswer(), result.correctAnswer(), result.correct());
            quizRepository.updateMastery(studentId, result.knowledgePoint(), result.correct());
            if (!result.correct()) {
                quizRepository.saveWrongQuestion(
                        studentId,
                        request.quizId(),
                        result.questionId(),
                        result.knowledgePoint(),
                        mistakePatterns.isEmpty() ? "concept_confusion" : mistakePatterns.get(0));
            }
        }
        String sourceTaskId = String.valueOf(aiResponse.getOrDefault("task_id", ""));
        Map<String, Object> profileUpdate = profileService.updateAfterQuizResult(
                score, weakPoints, mistakePatterns, recommendation, sourceTaskId);
        Map<String, Object> pathUpdate = learningPathService.replanAfterEvaluation(
                courseId,
                request.quizId(),
                evaluation,
                profileService.currentProfileSnapshot(),
                sourceTaskId);
        return new QuizSubmitResult(
                score,
                (int) correct,
                questions.size(),
                questionResults,
                evaluation,
                profileUpdate,
                pathUpdate,
                aiResponse.getOrDefault("evidence", List.of()),
                safety,
                generationMode,
                modelRuntime,
                sourceTaskId);
    }

    public QuizHistoryResponse history(int page, int size) {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.max(1, Math.min(size, 50));
        return new QuizHistoryResponse(
                quizRepository.attempts(AuthContext.currentStudentId(), normalizedPage, normalizedSize),
                normalizedPage,
                normalizedSize);
    }

    public WrongBookResponse wrongBook(int page, int size) {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.max(1, Math.min(size, 50));
        return new WrongBookResponse(
                quizRepository.wrongQuestions(AuthContext.currentStudentId(), normalizedPage, normalizedSize),
                normalizedPage,
                normalizedSize);
    }

    public MasteryResponse mastery() {
        return new MasteryResponse(quizRepository.mastery(AuthContext.currentStudentId()));
    }

    @Transactional
    public QuizRepository.QuestionBankRow createQuestionBankItem(QuestionBankCreateRequest request) {
        long id = quizRepository.createQuestionBankItem(
                request.courseId(),
                request.knowledgePoint(),
                request.questionType() == null ? "single_choice" : request.questionType(),
                request.difficulty() == null ? "basic" : request.difficulty(),
                request.question(),
                request.options() == null ? List.of() : request.options(),
                request.answer(),
                request.explanation(),
                AuthContext.currentUsername());
        return quizRepository.questionBank(request.courseId(), "active", 1, 100).stream()
                .filter(item -> item.id() == id)
                .findFirst()
                .orElseThrow();
    }

    public QuestionBankResponse questionBank(Integer courseId, String status, int page, int size) {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.max(1, Math.min(size, 100));
        return new QuestionBankResponse(
                quizRepository.questionBank(courseId, status, normalizedPage, normalizedSize),
                normalizedPage,
                normalizedSize);
    }

    public void updateQuestionBankStatus(long id, QuestionBankStatusUpdateRequest request) {
        int updated = quizRepository.updateQuestionBankStatus(id, request.status());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "题目不存在: " + id);
        }
    }

    private Map<Long, String> normalizeAnswers(Object answers) {
        Map<Long, String> normalized = new LinkedHashMap<>();
        if (answers instanceof Map<?, ?> map) {
            map.forEach((key, value) -> normalized.put(parseLong(key), value == null ? "" : value.toString()));
        } else if (answers instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object questionId = map.get("question_id");
                    if (questionId == null) {
                        questionId = map.get("questionId");
                    }
                    Object answer = map.get("answer");
                    normalized.put(parseLong(questionId), answer == null ? "" : answer.toString());
                }
            }
        }
        return normalized;
    }

    private boolean isCorrectAnswer(QuizRepository.QuestionRow question, String submittedAnswer) {
        String submitted = normalizeAnswerText(submittedAnswer);
        if (submitted.isEmpty()) {
            return false;
        }
        if (submitted.equals(normalizeAnswerText(question.answer()))) {
            return true;
        }
        return submitted.equals(normalizeAnswerText(correctOptionText(question)));
    }

    private String correctOptionText(QuizRepository.QuestionRow question) {
        String answer = question.answer();
        List<String> options = question.options() == null ? List.of() : question.options();
        if (answer == null || answer.isBlank() || options.isEmpty()) {
            return "";
        }
        String normalizedAnswer = answer.trim().toUpperCase(Locale.ROOT);
        if (normalizedAnswer.length() == 1) {
            int optionIndex = normalizedAnswer.charAt(0) - 'A';
            if (optionIndex >= 0 && optionIndex < options.size()) {
                return options.get(optionIndex);
            }
        }
        return "";
    }

    private String normalizeAnswerText(String answer) {
        return answer == null ? "" : answer.trim();
    }

    private long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private Map<String, Object> requireMap(Object value, String message) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new ExternalServiceException("AI 服务", message);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        map.forEach((key, item) -> normalized.put(String.valueOf(key), item));
        return normalized;
    }

    private List<?> requireList(Object value, String message) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new ExternalServiceException("AI 服务", message);
        }
        return list;
    }

    private String requireText(Object value, String message) {
        String text = value == null ? "" : value.toString().trim();
        if (text.isBlank()) {
            throw new ExternalServiceException("AI 服务", message);
        }
        return text;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private Set<String> evidenceIds(List<?> evidence) {
        Set<String> ids = new HashSet<>();
        for (Object item : evidence) {
            Map<String, Object> map = requireMap(item, "小测包含非法 RAG 证据");
            String id = String.valueOf(map.getOrDefault("chunk_id", "")).trim();
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            throw new ExternalServiceException("AI 服务", "小测没有可追溯的 RAG chunk_id");
        }
        return ids;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    public record QuizGenerateRequest(
            Integer courseId,
            List<Integer> knowledgePointIds,
            List<String> knowledgePoints,
            String difficulty,
            Integer questionCount) {}

    public record QuizResponse(
            long quizId,
            String title,
            List<QuizQuestion> questions,
            Object evidence,
            Object safety,
            String generationMode,
            Map<String, Object> modelRuntime,
            String sourceTaskId) {}

    public record QuizQuestion(
            long questionId,
            String type,
            String difficulty,
            String knowledgePoint,
            String question,
            List<String> options,
            List<String> evidenceChunkIds) {}

    public record QuizSubmitRequest(long quizId, Object answers) {}

    public record QuizSubmitResult(
            int score,
            int correctCount,
            int totalCount,
            List<QuestionResult> questionResults,
            Map<String, Object> evaluation,
            Map<String, Object> profileUpdate,
            Map<String, Object> pathUpdate,
            Object evidence,
            Object safety,
            String generationMode,
            Map<String, Object> modelRuntime,
            String sourceTaskId) {}

    public record QuestionResult(
            long questionId,
            String knowledgePoint,
            String stem,
            String submittedAnswer,
            String correctAnswer,
            String explanation,
            boolean correct) {}

    public record QuizHistoryResponse(List<QuizRepository.AttemptRow> items, int page, int size) {}

    public record WrongBookResponse(List<QuizRepository.WrongQuestionRow> items, int page, int size) {}

    public record MasteryResponse(List<QuizRepository.MasteryRow> items) {}

    public record QuestionBankCreateRequest(
            int courseId,
            String knowledgePoint,
            String questionType,
            String difficulty,
            String question,
            List<String> options,
            String answer,
            String explanation) {}

    public record QuestionBankStatusUpdateRequest(String status) {}

    public record QuestionBankResponse(List<QuizRepository.QuestionBankRow> items, int page, int size) {}
}
