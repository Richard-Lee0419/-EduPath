package com.edupath.course;

import com.edupath.auth.RoleGuard;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClassInsightService {

    private final ClassRepository classRepository;
    private final ClassInsightRepository insightRepository;
    private final RoleGuard roleGuard;

    public ClassInsightService(
            ClassRepository classRepository,
            ClassInsightRepository insightRepository,
            RoleGuard roleGuard) {
        this.classRepository = classRepository;
        this.insightRepository = insightRepository;
        this.roleGuard = roleGuard;
    }

    public ClassInsightResponse insights(long classId, Long courseId, int windowDays) {
        ClassRepository.ClassRow classInfo = classRepository.find(classId);
        if (classInfo == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "班级不存在: " + classId);
        }
        List<ClassRepository.ClassCourseRow> courses = classRepository.courses(classId);
        if (courses.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "班级尚未绑定课程，无法生成聚合洞察");
        }
        long selectedCourseId = courseId == null ? courses.get(0).courseId() : courseId;
        ClassRepository.ClassCourseRow selectedCourse = courses.stream()
                .filter(item -> item.courseId() == selectedCourseId)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选课程未绑定到该班级"));
        roleGuard.requireCourseManager(selectedCourseId);

        int normalizedWindow = Math.max(7, Math.min(windowDays, 90));
        OffsetDateTime generatedAt = OffsetDateTime.now(ZoneOffset.ofHours(8));
        OffsetDateTime since = generatedAt.minusDays(normalizedWindow);
        List<ClassInsightRepository.StudentInsightRow> rows =
                insightRepository.students(classId, selectedCourseId, since);
        List<StudentRisk> students = rows.stream()
                .map(this::studentRisk)
                .sorted(Comparator.comparingInt((StudentRisk item) -> riskRank(item.riskLevel()))
                        .thenComparing(StudentRisk::username))
                .toList();
        List<ClassInsightRepository.WeakKnowledgeRow> weakPoints =
                insightRepository.weakKnowledgePoints(classId, selectedCourseId);
        List<ClassInsightRepository.ActivityTrendRow> trend =
                insightRepository.activityTrend(classId, selectedCourseId, since);
        InsightSummary summary = summary(rows, students, trend);
        return new ClassInsightResponse(
                new InsightSource(
                        "class_members + learning_events + knowledge_mastery + quiz_attempts + resource_interactions",
                        normalizedWindow,
                        since,
                        generatedAt),
                classInfo,
                courses,
                selectedCourse,
                summary,
                trend,
                weakPoints,
                students,
                interventions(summary, weakPoints));
    }

    private StudentRisk studentRisk(ClassInsightRepository.StudentInsightRow row) {
        List<String> reasons = new ArrayList<>();
        String level;
        if (row.masteryPoints() == 0 && row.averageQuizScore() == null && row.activityCount() == 0) {
            level = "insufficient_data";
            reasons.add("当前窗口尚无学习、测验或掌握度证据");
        } else if ((row.averageMastery() != null && row.averageMastery() < 60)
                || (row.averageQuizScore() != null && row.averageQuizScore() < 60)) {
            level = "critical";
            if (row.averageMastery() != null && row.averageMastery() < 60) {
                reasons.add("平均掌握度低于 60");
            }
            if (row.averageQuizScore() != null && row.averageQuizScore() < 60) {
                reasons.add("近期测验均分低于 60");
            }
        } else if ((row.averageMastery() != null && row.averageMastery() < 75)
                || (row.averageQuizScore() != null && row.averageQuizScore() < 70)
                || row.activityCount() == 0) {
            level = "attention";
            if (row.averageMastery() != null && row.averageMastery() < 75) {
                reasons.add("掌握度尚未达到稳定区间");
            }
            if (row.averageQuizScore() != null && row.averageQuizScore() < 70) {
                reasons.add("近期测验正确率需要提升");
            }
            if (row.activityCount() == 0) {
                reasons.add("当前窗口没有课程学习事件");
            }
        } else {
            level = "steady";
            reasons.add("掌握度、测验和近期行为未触发风险阈值");
        }
        return new StudentRisk(
                row.userId(),
                row.username(),
                level,
                reasons,
                row.masteryPoints(),
                row.averageMastery(),
                row.averageQuizScore(),
                row.activityCount(),
                row.completedResources(),
                row.lastActiveAt());
    }

    private InsightSummary summary(
            List<ClassInsightRepository.StudentInsightRow> rows,
            List<StudentRisk> students,
            List<ClassInsightRepository.ActivityTrendRow> trend) {
        int studentCount = rows.size();
        int activeStudents = (int) rows.stream().filter(item -> item.activityCount() > 0).count();
        int masteryCovered = (int) rows.stream().filter(item -> item.averageMastery() != null).count();
        double averageMastery = average(rows.stream()
                .map(ClassInsightRepository.StudentInsightRow::averageMastery)
                .filter(java.util.Objects::nonNull)
                .toList());
        double averageQuiz = average(rows.stream()
                .map(ClassInsightRepository.StudentInsightRow::averageQuizScore)
                .filter(java.util.Objects::nonNull)
                .toList());
        int critical = (int) students.stream().filter(item -> "critical".equals(item.riskLevel())).count();
        int attention = (int) students.stream().filter(item -> "attention".equals(item.riskLevel())).count();
        int insufficient = (int) students.stream().filter(item -> "insufficient_data".equals(item.riskLevel())).count();
        int eventCount = trend.stream().mapToInt(ClassInsightRepository.ActivityTrendRow::eventCount).sum();
        int completedResources = rows.stream()
                .mapToInt(ClassInsightRepository.StudentInsightRow::completedResources)
                .sum();
        return new InsightSummary(
                studentCount,
                activeStudents,
                ratio(activeStudents, studentCount),
                masteryCovered,
                ratio(masteryCovered, studentCount),
                averageMastery,
                averageQuiz,
                eventCount,
                completedResources,
                critical,
                attention,
                insufficient);
    }

    private List<String> interventions(
            InsightSummary summary, List<ClassInsightRepository.WeakKnowledgeRow> weakPoints) {
        List<String> items = new ArrayList<>();
        if (summary.studentCount() == 0) {
            items.add("先为班级添加学生成员，再生成班级学习洞察。");
            return items;
        }
        if (summary.insufficientDataStudents() > 0) {
            items.add("为 " + summary.insufficientDataStudents() + " 名证据不足学生安排一次课程诊断小测。");
        }
        if (summary.criticalStudents() > 0) {
            items.add("优先跟进 " + summary.criticalStudents() + " 名高风险学生，并推送基础讲义与补救小测。");
        }
        if (summary.activeRate() < 0.6) {
            items.add("班级活跃率低于 60%，建议设置 15 分钟可完成的学习路径节点并提醒未活跃学生。");
        }
        weakPoints.stream().limit(3).forEach(point -> items.add(
                "围绕“" + point.knowledgePoint() + "”生成班级共性讲义与分层练习，当前均值 "
                        + point.averageMastery() + "。"));
        if (items.isEmpty()) {
            items.add("当前班级整体稳定，可继续按既有路径推进，并保留每周一次诊断小测。");
        }
        return items.stream().limit(5).toList();
    }

    private int riskRank(String level) {
        return switch (level) {
            case "critical" -> 0;
            case "attention" -> 1;
            case "insufficient_data" -> 2;
            default -> 3;
        };
    }

    private double average(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        return rounded(values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : rounded((double) numerator / denominator);
    }

    private double rounded(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    public record InsightSource(
            String sourceTables,
            int windowDays,
            OffsetDateTime since,
            OffsetDateTime generatedAt) {}

    public record InsightSummary(
            int studentCount,
            int activeStudents,
            double activeRate,
            int masteryCoveredStudents,
            double masteryCoverage,
            double averageMastery,
            double averageQuizScore,
            int learningEventCount,
            int completedResources,
            int criticalStudents,
            int attentionStudents,
            int insufficientDataStudents) {}

    public record StudentRisk(
            long userId,
            String username,
            String riskLevel,
            List<String> reasons,
            int masteryPoints,
            Double averageMastery,
            Double averageQuizScore,
            int activityCount,
            int completedResources,
            OffsetDateTime lastActiveAt) {}

    public record ClassInsightResponse(
            InsightSource source,
            ClassRepository.ClassRow classInfo,
            List<ClassRepository.ClassCourseRow> courses,
            ClassRepository.ClassCourseRow selectedCourse,
            InsightSummary summary,
            List<ClassInsightRepository.ActivityTrendRow> activityTrend,
            List<ClassInsightRepository.WeakKnowledgeRow> weakKnowledgePoints,
            List<StudentRisk> studentRisks,
            List<String> recommendedInterventions) {}
}
