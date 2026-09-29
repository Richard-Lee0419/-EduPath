package com.edupath.demo;

import com.edupath.ai.AiAgentClient;
import com.edupath.auth.RoleGuard;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DemoPreflightService {

    private final JdbcTemplate jdbcTemplate;
    private final AiAgentClient aiAgentClient;
    private final DemoScenarioRepository demoScenarioRepository;
    private final RoleGuard roleGuard;

    public DemoPreflightService(
            JdbcTemplate jdbcTemplate,
            AiAgentClient aiAgentClient,
            DemoScenarioRepository demoScenarioRepository,
            RoleGuard roleGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.aiAgentClient = aiAgentClient;
        this.demoScenarioRepository = demoScenarioRepository;
        this.roleGuard = roleGuard;
    }

    public DemoPreflightResult inspect() {
        roleGuard.requireAny("teacher", "admin");
        List<PreflightCheck> checks = new ArrayList<>();
        checks.add(new PreflightCheck(
                "backend", "Java 后端", "ready", true, "统一 API 与鉴权服务可用", 0, Map.of()));

        boolean databaseReady = inspectDatabase(checks);
        boolean demoDataReady = inspectDemoData(checks, databaseReady);
        AiReadiness ai = inspectAi(checks);
        boolean preparedDemoAvailable = databaseReady && demoDataReady;

        String overallStatus;
        String recommendedMode;
        if (!preparedDemoAvailable) {
            overallStatus = "blocked";
            recommendedMode = "fix_before_demo";
        } else if (ai.liveGenerationReady()) {
            overallStatus = "ready";
            recommendedMode = "live_ai";
        } else {
            overallStatus = "degraded";
            recommendedMode = "prepared_data_only";
        }

        List<String> actions = actions(databaseReady, demoDataReady, ai);
        List<String> unavailableCapabilities = ai.liveGenerationReady()
                ? List.of()
                : List.of("实时多智能体资源生成", "实时 RAG 问答", "实时路径重规划");
        return new DemoPreflightResult(
                overallStatus,
                recommendedMode,
                preparedDemoAvailable,
                ai.liveGenerationReady(),
                checks,
                actions,
                unavailableCapabilities,
                OffsetDateTime.now(ZoneOffset.ofHours(8)));
    }

    private boolean inspectDatabase(List<PreflightCheck> checks) {
        long started = System.nanoTime();
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            boolean ready = result != null && result == 1;
            checks.add(new PreflightCheck(
                    "database",
                    "业务数据库",
                    ready ? "ready" : "blocked",
                    true,
                    ready ? "连接与基础查询正常" : "基础查询未返回预期结果",
                    elapsedMillis(started),
                    Map.of()));
            return ready;
        } catch (RuntimeException exception) {
            checks.add(new PreflightCheck(
                    "database",
                    "业务数据库",
                    "blocked",
                    true,
                    "数据库连接或查询失败",
                    elapsedMillis(started),
                    Map.of()));
            return false;
        }
    }

    private boolean inspectDemoData(List<PreflightCheck> checks, boolean databaseReady) {
        if (!databaseReady) {
            checks.add(new PreflightCheck(
                    "demo_data", "一键演示数据", "blocked", true, "数据库不可用，无法检查演示数据", 0, Map.of()));
            return false;
        }
        long started = System.nanoTime();
        try {
            DemoScenarioRepository.DemoDataSummary summary = demoScenarioRepository.currentSummary();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("resources", summary.resourceCount());
            metadata.put("learning_events", summary.learningEventCount());
            metadata.put("mastery_points", summary.masteryPointCount());
            metadata.put("path_versions", summary.learningPathCount());
            checks.add(new PreflightCheck(
                    "demo_data",
                    "一键演示数据",
                    summary.ready() ? "ready" : "blocked",
                    true,
                    summary.ready() ? "学生与教师演示闭环数据完整" : "请先执行一键准备演示数据",
                    elapsedMillis(started),
                    metadata));
            return summary.ready();
        } catch (RuntimeException exception) {
            checks.add(new PreflightCheck(
                    "demo_data", "一键演示数据", "blocked", true, "演示数据完整性检查失败", elapsedMillis(started), Map.of()));
            return false;
        }
    }

    private AiReadiness inspectAi(List<PreflightCheck> checks) {
        long started = System.nanoTime();
        try {
            Map<String, Object> readiness = aiAgentClient.readiness();
            boolean liveReady = booleanValue(readiness.get("live_generation_ready"));
            boolean preparedSupported = booleanValue(readiness.get("prepared_demo_supported"));
            Map<String, Object> rawChecks = mapValue(readiness.get("checks"));
            Map<String, Object> corpus = mapValue(readiness.get("corpus"));
            checks.add(new PreflightCheck(
                    "ai_service",
                    "Python AI 服务",
                    liveReady ? "ready" : preparedSupported ? "degraded" : "blocked",
                    false,
                    liveReady ? "多智能体服务与实时生成配置就绪" : preparedSupported
                            ? "服务可用，但实时模型或正式课程语料配置不完整"
                            : "AI 服务缺少可用检索语料",
                    elapsedMillis(started),
                    Map.of("service_status", String.valueOf(readiness.getOrDefault("status", "unknown")))));
            checks.add(new PreflightCheck(
                    "course_corpus",
                    "两门课程知识库",
                    booleanValue(rawChecks.get("course_corpus_ready")) ? "ready" : "degraded",
                    false,
                    booleanValue(rawChecks.get("course_corpus_ready"))
                            ? "正式课程语料已进入检索索引"
                            : "当前可能仅有内置保底语料",
                    0,
                    Map.of(
                            "chunks", corpus.getOrDefault("chunks", 0),
                            "external_documents", corpus.getOrDefault("external_documents", 0),
                            "mode", corpus.getOrDefault("mode", "unknown"))));
            checks.add(new PreflightCheck(
                    "real_model",
                    "真实大模型",
                    booleanValue(rawChecks.get("llm_configured")) ? "ready" : "degraded",
                    false,
                    booleanValue(rawChecks.get("llm_configured"))
                            ? "模型提供商、模型名与密钥已配置（未消耗额度探测）"
                            : "真实模型配置缺失，现场不要触发实时生成",
                    0,
                    Map.of()));
            return new AiReadiness(true, liveReady, preparedSupported, rawChecks);
        } catch (RuntimeException exception) {
            checks.add(new PreflightCheck(
                    "ai_service",
                    "Python AI 服务",
                    "degraded",
                    false,
                    "AI 服务不可达，可切换到已准备数据演示",
                    elapsedMillis(started),
                    Map.of()));
            return new AiReadiness(false, false, false, Map.of());
        }
    }

    private List<String> actions(boolean databaseReady, boolean demoDataReady, AiReadiness ai) {
        List<String> actions = new ArrayList<>();
        if (!databaseReady) {
            actions.add("检查 MySQL 容器、DB_URL 和数据库账号后重新预检");
        } else if (!demoDataReady) {
            actions.add("点击“一键准备演示数据”，完成后重新预检");
        }
        if (!ai.reachable()) {
            actions.add("启动 Python AI 服务；修复前仅展示已准备资源、路径、辅导记录和评估结果");
        } else if (!ai.liveGenerationReady()) {
            if (!booleanValue(ai.checks().get("course_corpus_ready"))) {
                actions.add("检查两门课程 seed_documents.jsonl 与向量索引加载状态");
            }
            if (!booleanValue(ai.checks().get("llm_configured"))) {
                actions.add("检查 LLM_PROVIDER、LLM_MODEL、LLM_BASE_URL 与 LLM_API_KEY");
            }
        }
        if (actions.isEmpty()) {
            actions.add("预检通过，可按推荐动线进行实时模型与完整闭环演示");
        }
        return actions;
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        raw.forEach((key, item) -> normalized.put(String.valueOf(key), item));
        return normalized;
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private record AiReadiness(
            boolean reachable, boolean liveGenerationReady, boolean preparedSupported, Map<String, Object> checks) {}

    public record PreflightCheck(
            String key,
            String label,
            String status,
            boolean required,
            String detail,
            long latencyMs,
            Map<String, Object> metadata) {}

    public record DemoPreflightResult(
            String overallStatus,
            String recommendedMode,
            boolean preparedDemoAvailable,
            boolean liveAiAvailable,
            List<PreflightCheck> checks,
            List<String> actions,
            List<String> unavailableCapabilities,
            OffsetDateTime checkedAt) {}
}
