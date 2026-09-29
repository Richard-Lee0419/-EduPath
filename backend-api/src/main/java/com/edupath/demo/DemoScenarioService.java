package com.edupath.demo;

import com.edupath.auth.AuthContext;
import com.edupath.auth.RoleGuard;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DemoScenarioService {

    private final DemoScenarioRepository repository;
    private final RoleGuard roleGuard;
    private final boolean enabled;

    public DemoScenarioService(
            DemoScenarioRepository repository,
            RoleGuard roleGuard,
            @Value("${edupath.demo-mode.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.roleGuard = roleGuard;
        this.enabled = enabled;
    }

    public DemoStatus status() {
        requireManager();
        DemoScenarioRepository.DemoDataSummary summary = repository.currentSummary();
        return new DemoStatus(
                enabled,
                DemoScenarioRepository.SCENARIO_KEY,
                summary.ready(),
                summary,
                repository.latestRun().orElse(null),
                accounts(),
                steps(),
                summary.ready() ? "ready_for_demo" : "prepare_required");
    }

    @Transactional
    public DemoPreparationResult prepare(DemoPrepareRequest request) {
        requireManager();
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "当前环境已关闭比赛演示数据准备功能");
        }
        String scenarioKey = request == null || request.scenarioKey() == null
                ? DemoScenarioRepository.SCENARIO_KEY
                : request.scenarioKey().trim();
        if (!DemoScenarioRepository.SCENARIO_KEY.equals(scenarioKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的演示场景: " + scenarioKey);
        }
        var principal = AuthContext.requirePrincipal();
        String runId = "demo_run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        DemoScenarioRepository.DemoDataSummary summary = repository.prepare();
        if (!summary.ready()) {
            throw new IllegalStateException("演示数据准备后仍未达到完整性门禁");
        }
        repository.recordCompletedRun(runId, principal.userId(), summary);
        return new DemoPreparationResult(
                runId,
                scenarioKey,
                "ready",
                true,
                summary,
                accounts(),
                steps(),
                "logout_and_login_as_demo_student");
    }

    private void requireManager() {
        roleGuard.requireAny("teacher", "admin");
        var principal = AuthContext.requirePrincipal();
        if ("teacher".equals(principal.role())) {
            roleGuard.requireCourseManager(1);
            roleGuard.requireCourseManager(2);
        }
    }

    private List<DemoAccount> accounts() {
        return List.of(
                new DemoAccount("demo", "student", "学生完整学习闭环与评估结果"),
                new DemoAccount("teacher", "teacher", "班级洞察与资源质量治理"));
    }

    private List<DemoStep> steps() {
        return List.of(
                new DemoStep(1, "profile", "学习画像", "/profile-chat", "student", "展示不少于 8 维画像及薄弱点"),
                new DemoStep(2, "resources", "多智能体资源", "/resource-generate", "student", "展示 6 类资源与质量门禁"),
                new DemoStep(3, "resource_center", "资源中心", "/resources", "student", "打开讲义、导图、题库、代码实验、流程图和拓展阅读"),
                new DemoStep(4, "learning_path", "动态学习路径", "/learning-path", "student", "展示测验驱动的 7 天补救路径"),
                new DemoStep(5, "tutor", "RAG 智能辅导", "/tutor", "student", "展示带课程证据的 Cache 答疑"),
                new DemoStep(6, "evaluation", "测验与评估", "/evaluation", "student", "展示错因、掌握度与下一步建议"),
                new DemoStep(7, "teacher_insights", "教师班级洞察", "/teacher-insights", "teacher", "展示风险分层与班级共性薄弱点"),
                new DemoStep(8, "quality_governance", "资源质量治理", "/resource-generate", "teacher", "展示量化评测、回归、修复和版本发布"));
    }

    public record DemoPrepareRequest(String scenarioKey) {}

    public record DemoAccount(String username, String role, String purpose) {}

    public record DemoStep(
            int order,
            String key,
            String title,
            String route,
            String role,
            String expectedEvidence) {}

    public record DemoStatus(
            boolean enabled,
            String scenarioKey,
            boolean ready,
            DemoScenarioRepository.DemoDataSummary summary,
            DemoScenarioRepository.DemoRunRow latestRun,
            List<DemoAccount> accounts,
            List<DemoStep> steps,
            String nextAction) {}

    public record DemoPreparationResult(
            String runId,
            String scenarioKey,
            String status,
            boolean resetExisting,
            DemoScenarioRepository.DemoDataSummary summary,
            List<DemoAccount> accounts,
            List<DemoStep> steps,
            String nextAction) {}
}
