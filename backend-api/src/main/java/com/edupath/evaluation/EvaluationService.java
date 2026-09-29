package com.edupath.evaluation;

import com.edupath.auth.AuthContext;
import com.edupath.quiz.QuizRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class EvaluationService {

    private final QuizRepository quizRepository;

    public EvaluationService(QuizRepository quizRepository) {
        this.quizRepository = quizRepository;
    }

    public Map<String, Object> report() {
        String studentId = AuthContext.currentStudentId();
        List<QuizRepository.MasteryRow> masteryRows = quizRepository.mastery(studentId);
        List<QuizRepository.AttemptRow> latestAttempts = quizRepository.attempts(studentId, 1, 1);
        if (!latestAttempts.isEmpty()) {
            return localReport(studentId, masteryRows, latestAttempts.get(0));
        }
        Map<String, Object> emptyReport = new LinkedHashMap<>();
        emptyReport.put("report_id", "");
        emptyReport.put("student_id", studentId);
        emptyReport.put("quiz_id", null);
        emptyReport.put("overall_score", 0);
        emptyReport.put("mastery", List.of());
        emptyReport.put("weak_points", List.of());
        emptyReport.put("mistake_patterns", List.of());
        emptyReport.put("next_actions", List.of());
        emptyReport.put("profile_updated", false);
        return emptyReport;
    }

    private Map<String, Object> localReport(
            String studentId,
            List<QuizRepository.MasteryRow> masteryRows,
            QuizRepository.AttemptRow latestAttempt) {
        List<Map<String, Object>> mastery = masteryRows.stream()
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("knowledge_point", row.knowledgePoint());
                    item.put("mastery_score", row.masteryScore());
                    item.put("level", level(row.masteryScore()));
                    item.put("attempts", row.attempts());
                    item.put("correct_count", row.correctCount());
                    return item;
                })
                .toList();
        List<String> nextActions = latestAttempt.recommendation() == null
                        || latestAttempt.recommendation().isBlank()
                ? List.of()
                : List.of(latestAttempt.recommendation());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("report_id", "eval_" + latestAttempt.attemptId());
        report.put("student_id", studentId);
        report.put("quiz_id", latestAttempt.quizId());
        report.put("overall_score", latestAttempt.score());
        report.put("mastery", mastery);
        report.put("weak_points", latestAttempt.weakPoints());
        report.put("mistake_patterns", latestAttempt.mistakePatterns());
        report.put("next_actions", nextActions);
        report.put("profile_updated", true);
        report.put("submitted_at", latestAttempt.submittedAt());
        return report;
    }

    private String level(int score) {
        if (score >= 85) {
            return "优秀";
        }
        if (score >= 70) {
            return "良好";
        }
        if (score >= 50) {
            return "一般";
        }
        return "待补救";
    }
}
