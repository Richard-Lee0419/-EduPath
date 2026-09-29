package com.edupath.tutor;

import com.edupath.ai.AiAgentClient;
import com.edupath.auth.AuthContext;
import com.edupath.common.ExternalServiceException;
import com.edupath.course.CourseCatalogService;
import com.edupath.learning.LearningEventService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TutorService {

    private final AiAgentClient aiAgentClient;
    private final TutorRepository tutorRepository;
    private final CourseCatalogService courseCatalogService;
    private final LearningEventService learningEventService;

    public TutorService(
            AiAgentClient aiAgentClient,
            TutorRepository tutorRepository,
            CourseCatalogService courseCatalogService,
            LearningEventService learningEventService) {
        this.aiAgentClient = aiAgentClient;
        this.tutorRepository = tutorRepository;
        this.courseCatalogService = courseCatalogService;
        this.learningEventService = learningEventService;
    }

    @Transactional
    public Map<String, Object> answer(TutorChatRequest request) {
        String question = request.normalizedQuestion();
        if (question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 或 message 不能为空");
        }
        String studentId = AuthContext.currentStudentId();
        long courseId = request.courseId() == null ? 1 : request.courseId();
        Map<String, Object> aiResponse = aiAgentClient.chatTutor(request);
        validateAiResponse(aiResponse);

        boolean followUp = request.sessionId() != null && !request.sessionId().isBlank();
        String sessionId;
        if (followUp) {
            TutorRepository.SessionRow session = tutorRepository.findSession(request.sessionId().trim())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "辅导会话不存在"));
            if (!studentId.equals(session.studentId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该辅导会话");
            }
            if (session.courseId() != courseId) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "追问课程与原辅导会话不一致");
            }
            sessionId = session.sessionId();
        } else {
            sessionId = tutorRepository.createSession(
                    studentId, courseId, question.length() > 30 ? question.substring(0, 30) : question);
        }
        tutorRepository.addMessage(sessionId, "user", question, null);
        tutorRepository.addMessage(
                sessionId,
                "assistant",
                String.valueOf(aiResponse.get("answer_markdown")),
                Map.of(
                        "evidence", aiResponse.get("evidence"),
                        "citations", aiResponse.get("citations")));
        Map<String, Object> response = new LinkedHashMap<>(aiResponse);
        response.put("session_id", sessionId);
        response.put("student_id", studentId);
        response.put("source_task_id", String.valueOf(aiResponse.getOrDefault("task_id", "")));
        String knowledgePoint = resolveKnowledgePoint((int) courseId, question);
        LearningEventService.LearningUpdateResult learningUpdate = learningEventService.record(List.of(
                new LearningEventService.LearningEventCommand(
                        followUp ? "tutor_follow_up" : "tutor_question",
                        (int) courseId,
                        knowledgePoint,
                        "tutor_session",
                        sessionId,
                        followUp ? "follow_up" : "ask",
                        null,
                        null,
                        followUp ? 1 : 0,
                        followUp ? 60 : 0,
                        "tutor:" + sessionId + ":" + Integer.toUnsignedString(question.hashCode()),
                        Map.of("answer_mode", request.normalizedMode()))));
        response.put("learning_update", learningUpdate);
        return response;
    }

    private String resolveKnowledgePoint(int courseId, String question) {
        List<String> names = new ArrayList<>();
        for (CourseCatalogService.KnowledgePointDto point : courseCatalogService.listKnowledgePoints(courseId)) {
            collectKnowledgePoints(point, names);
        }
        return names.stream()
                .filter(name -> question.contains(name))
                .max((left, right) -> Integer.compare(left.length(), right.length()))
                .orElseGet(() -> courseCatalogService.normalizeCourseTopic(courseId));
    }

    private void collectKnowledgePoints(CourseCatalogService.KnowledgePointDto point, List<String> names) {
        names.add(point.name());
        point.children().forEach(child -> collectKnowledgePoints(child, names));
    }

    private void validateAiResponse(Map<String, Object> aiResponse) {
        Map<String, Object> safety = requireMap(aiResponse.get("safety"), "辅导回答未返回 SafetyAgent 结果");
        if (!Boolean.TRUE.equals(safety.get("passed"))) {
            throw new ExternalServiceException("AI 服务", "辅导回答未通过 SafetyAgent 审查");
        }
        requireMap(aiResponse.get("model_runtime"), "辅导回答未返回模型运行记录");
        String generationMode = String.valueOf(aiResponse.getOrDefault("generation_mode", ""));
        if (generationMode.isBlank()) {
            throw new ExternalServiceException("AI 服务", "辅导回答未返回 generation_mode");
        }
        String answerMarkdown = String.valueOf(aiResponse.getOrDefault("answer_markdown", ""));
        if (answerMarkdown.isBlank()) {
            throw new ExternalServiceException("AI 服务", "辅导回答未返回 answer_markdown");
        }
        List<?> evidence = requireList(aiResponse.get("evidence"), "辅导回答未返回 RAG 证据");
        List<?> citations = requireList(aiResponse.get("citations"), "辅导回答未返回证据映射");
        Set<String> evidenceIds = new HashSet<>();
        for (Object item : evidence) {
            Map<String, Object> map = requireMap(item, "辅导回答包含非法 RAG 证据");
            String chunkId = String.valueOf(map.getOrDefault("chunk_id", ""));
            if (!chunkId.isBlank()) {
                evidenceIds.add(chunkId);
            }
        }
        if (evidenceIds.isEmpty()) {
            throw new ExternalServiceException("AI 服务", "辅导回答没有可追溯的 RAG chunk_id");
        }
        for (Object item : citations) {
            Map<String, Object> citation = requireMap(item, "辅导回答包含非法证据映射");
            String fragment = String.valueOf(citation.getOrDefault("answer_fragment", ""));
            if (fragment.isBlank() || !answerMarkdown.contains(fragment)) {
                throw new ExternalServiceException("AI 服务", "辅导回答的证据片段未出现在 answer_markdown 中");
            }
            List<?> chunkIds = requireList(citation.get("evidence_chunk_ids"), "辅导回答的证据映射缺少 chunk_id");
            if (chunkIds.isEmpty() || chunkIds.stream().map(String::valueOf).anyMatch(id -> !evidenceIds.contains(id))) {
                throw new ExternalServiceException("AI 服务", "辅导回答引用了本次检索范围外的 chunk_id");
            }
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

    public SessionListResponse sessions(int page, int size) {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.max(1, Math.min(size, 50));
        return new SessionListResponse(
                tutorRepository.sessions(AuthContext.currentStudentId(), normalizedPage, normalizedSize),
                normalizedPage,
                normalizedSize);
    }

    public record TutorChatRequest(
            Integer courseId, String question, String answerMode, String message, String mode, String sessionId) {
        public String normalizedQuestion() {
            if (question != null && !question.isBlank()) {
                return question.trim();
            }
            return message == null ? "" : message.trim();
        }

        public String normalizedMode() {
            String candidate = answerMode != null && !answerMode.isBlank()
                    ? answerMode.trim()
                    : mode == null || mode.isBlank() ? "step_by_step" : mode.trim();
            return switch (candidate) {
                case "hint", "socratic", "summary", "code_first", "step_by_step" -> candidate;
                default -> "step_by_step";
            };
        }
    }

    public record SessionListResponse(List<TutorRepository.SessionRow> items, int page, int size) {}
}
