package com.edupath.demo;

import com.edupath.common.JsonCodec;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DemoScenarioRepository {

    public static final String SCENARIO_KEY = "software_cup_a3";
    private static final String DEMO_STUDENT = "demo";
    private static final String DEMO_SOURCE = "demo_prepare";
    private static final String DEMO_CLASS = "测试2班";
    private static final String DEMO_QUIZ_TITLE = "【演示】Cache 映射方式诊断小测";

    private final JdbcTemplate jdbcTemplate;
    private final JsonCodec jsonCodec;

    public DemoScenarioRepository(JdbcTemplate jdbcTemplate, JsonCodec jsonCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
    }

    public DemoDataSummary prepare() {
        long demoUserId = requiredUserId(DEMO_STUDENT);
        long teacherUserId = requiredUserId("teacher");
        long classId = ensureClass(demoUserId, teacherUserId);
        resetManagedData(demoUserId);
        prepareProfile();
        prepareResources(demoUserId);
        prepareMastery();
        prepareLearningEvents(demoUserId);
        long quizId = prepareQuiz();
        prepareLearningPath();
        prepareTutorSession();
        prepareResourceInteractions(demoUserId);
        return summary(classId, quizId);
    }

    public DemoDataSummary currentSummary() {
        Long classId = jdbcTemplate.query(
                        "SELECT id FROM classes WHERE name = ? AND status = 'active' ORDER BY id LIMIT 1",
                        (rs, rowNum) -> rs.getLong("id"),
                        DEMO_CLASS)
                .stream()
                .findFirst()
                .orElse(0L);
        Long quizId = jdbcTemplate.query(
                        "SELECT quiz_id FROM quizzes WHERE title = ? AND status = 'active' ORDER BY quiz_id DESC LIMIT 1",
                        (rs, rowNum) -> rs.getLong("quiz_id"),
                        DEMO_QUIZ_TITLE)
                .stream()
                .findFirst()
                .orElse(0L);
        return summary(classId, quizId);
    }

    public void recordCompletedRun(String runId, long preparedBy, DemoDataSummary summary) {
        jdbcTemplate.update(
                """
                INSERT INTO demo_scenario_runs (
                    run_id, scenario_key, status, reset_existing, prepared_by,
                    summary, started_at, completed_at
                ) VALUES (?, ?, 'ready', TRUE, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                runId,
                SCENARIO_KEY,
                preparedBy,
                jsonCodec.toJson(summary));
    }

    public Optional<DemoRunRow> latestRun() {
        return jdbcTemplate.query(
                        """
                        SELECT run_id, scenario_key, status, prepared_by, summary, completed_at
                        FROM demo_scenario_runs
                        WHERE scenario_key = ?
                        ORDER BY completed_at DESC, id DESC
                        LIMIT 1
                        """,
                        (rs, rowNum) -> new DemoRunRow(
                                rs.getString("run_id"),
                                rs.getString("scenario_key"),
                                rs.getString("status"),
                                rs.getLong("prepared_by"),
                                jsonCodec.map(rs.getString("summary")),
                                toOffsetDateTime(rs.getTimestamp("completed_at"))),
                        SCENARIO_KEY)
                .stream()
                .findFirst();
    }

    private long ensureClass(long demoUserId, long teacherUserId) {
        Long classId = jdbcTemplate.query(
                        "SELECT id FROM classes WHERE name = ? ORDER BY id LIMIT 1",
                        (rs, rowNum) -> rs.getLong("id"),
                        DEMO_CLASS)
                .stream()
                .findFirst()
                .orElse(null);
        if (classId == null) {
            jdbcTemplate.update(
                    """
                    INSERT INTO classes (name, description, owner_user_id, status, created_at, updated_at)
                    VALUES (?, '用于软件杯完整学习闭环演示', ?, 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    DEMO_CLASS,
                    teacherUserId);
            classId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM classes", Long.class);
        } else {
            jdbcTemplate.update(
                    "UPDATE classes SET owner_user_id = ?, status = 'active', updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    teacherUserId,
                    classId);
        }
        ensureClassMember(classId, demoUserId);
        ensureClassCourse(classId, 1);
        ensureClassCourse(classId, 2);
        return classId;
    }

    private void ensureClassMember(long classId, long demoUserId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM class_members WHERE class_id = ? AND user_id = ? AND role = 'student'",
                Long.class,
                classId,
                demoUserId);
        if (count != null && count > 0) {
            jdbcTemplate.update(
                    "UPDATE class_members SET status = 'active' WHERE class_id = ? AND user_id = ? AND role = 'student'",
                    classId,
                    demoUserId);
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO class_members (class_id, user_id, role, status, created_at) VALUES (?, ?, 'student', 'active', CURRENT_TIMESTAMP)",
                classId,
                demoUserId);
    }

    private void ensureClassCourse(long classId, long courseId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM class_courses WHERE class_id = ? AND course_id = ?",
                Long.class,
                classId,
                courseId);
        if (count != null && count > 0) {
            jdbcTemplate.update(
                    "UPDATE class_courses SET status = 'active' WHERE class_id = ? AND course_id = ?",
                    classId,
                    courseId);
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO class_courses (class_id, course_id, status, created_at) VALUES (?, ?, 'active', CURRENT_TIMESTAMP)",
                classId,
                courseId);
    }

    private void resetManagedData(long demoUserId) {
        jdbcTemplate.update(
                "DELETE FROM resource_interactions WHERE user_id = ? AND resource_id LIKE 'demo_showcase_%'",
                demoUserId);
        jdbcTemplate.update("DELETE FROM resource_versions WHERE resource_id LIKE 'demo_showcase_%'");
        jdbcTemplate.update("DELETE FROM resource_quality_repairs WHERE resource_id LIKE 'demo_showcase_%'");
        jdbcTemplate.update("DELETE FROM resources WHERE resource_id LIKE 'demo_showcase_%'");
        jdbcTemplate.update(
                "DELETE FROM learning_events WHERE student_id = ? AND idempotency_key LIKE 'demo_prepare:%'",
                DEMO_STUDENT);
        jdbcTemplate.update(
                "DELETE FROM knowledge_mastery WHERE student_id = ? AND knowledge_point IN (?, ?, ?)",
                DEMO_STUDENT,
                "二叉树递归遍历",
                "图的遍历",
                "Cache 映射方式");
        jdbcTemplate.update(
                "DELETE FROM profile_versions WHERE student_id = ? AND source_task_id = ?",
                DEMO_STUDENT,
                DEMO_SOURCE);
        jdbcTemplate.update(
                "DELETE FROM learning_paths WHERE student_id = ? AND source_task_id = ?",
                DEMO_STUDENT,
                DEMO_SOURCE);
        List<Long> quizIds = jdbcTemplate.query(
                "SELECT quiz_id FROM quizzes WHERE title = ?",
                (rs, rowNum) -> rs.getLong("quiz_id"),
                DEMO_QUIZ_TITLE);
        for (Long quizId : quizIds) {
            jdbcTemplate.update(
                    "DELETE FROM quiz_attempt_items WHERE attempt_id IN (SELECT attempt_id FROM quiz_attempts WHERE quiz_id = ? AND student_id = ?)",
                    quizId,
                    DEMO_STUDENT);
            jdbcTemplate.update(
                    "DELETE FROM wrong_question_book WHERE quiz_id = ? AND student_id = ?",
                    quizId,
                    DEMO_STUDENT);
            jdbcTemplate.update(
                    "DELETE FROM quiz_attempts WHERE quiz_id = ? AND student_id = ?",
                    quizId,
                    DEMO_STUDENT);
            jdbcTemplate.update("DELETE FROM quiz_questions WHERE quiz_id = ?", quizId);
            jdbcTemplate.update("DELETE FROM quizzes WHERE quiz_id = ?", quizId);
        }
        jdbcTemplate.update("DELETE FROM tutor_messages WHERE session_id = 'demo_tutor_software_cup'");
        jdbcTemplate.update("DELETE FROM tutor_sessions WHERE session_id = 'demo_tutor_software_cup'");
    }

    private void prepareProfile() {
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM profile_versions WHERE student_id = ?",
                Integer.class,
                DEMO_STUDENT);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("student_id", DEMO_STUDENT);
        profile.put("major", "计算机科学与技术");
        profile.put("grade", "大二");
        profile.put("learning_goal", "在两周内掌握二叉树递归与 Cache 映射核心题型");
        profile.put("target_courses", List.of("data_structures_algorithms", "computer_organization"));
        profile.put("weak_points", List.of("递归调用栈", "Cache 映射方式"));
        profile.put("strong_points", List.of("基础代码阅读", "顺序结构理解"));
        profile.put("resource_preference", List.of("mindmap", "codelab", "quiz"));
        profile.put("preferred_difficulty", "medium");
        profile.put("daily_minutes", 45);
        profile.put("learning_style", "图解 + 代码实践 + 短测验");
        profile.put("confidence_score", 0.91);
        profile.put("updated_reason", "软件杯演示场景：测验与学习行为驱动画像更新");
        jdbcTemplate.update(
                """
                INSERT INTO profile_versions (
                    student_id, version, profile_payload, source_task_id, updated_reason, created_at
                ) VALUES (?, ?, ?, ?, '一键准备软件杯演示画像', CURRENT_TIMESTAMP)
                """,
                DEMO_STUDENT,
                (maxVersion == null ? 0 : maxVersion) + 1,
                jsonCodec.toJson(profile),
                DEMO_SOURCE);
    }

    private void prepareResources(long demoUserId) {
        List<DemoResource> resources = List.of(
                new DemoResource(
                        "demo_showcase_lecture",
                        "递归调用栈个性化讲义",
                        "lecture",
                        1,
                        List.of("二叉树递归遍历", "递归调用栈"),
                        "medium",
                        "从函数帧、终止条件和返回路径理解二叉树递归。",
                        "markdown",
                        "## 递归调用栈\n\n每次递归调用都会创建独立栈帧。以前序遍历为例：先访问根节点，再递归左子树，最后递归右子树。\n\n### 例题\n给定 `1(2,3)`，调用顺序为 `visit(1) → visit(2) → visit(3)`。",
                        94.0,
                        18),
                new DemoResource(
                        "demo_showcase_mindmap",
                        "二叉树遍历知识导图",
                        "mindmap",
                        1,
                        List.of("二叉树递归遍历"),
                        "basic",
                        "对比前序、中序、后序遍历的访问时机。",
                        "mermaid",
                        "mindmap\n  root((二叉树遍历))\n    前序\n      根-左-右\n    中序\n      左-根-右\n    后序\n      左-右-根\n    递归调用栈\n      入栈\n      终止条件\n      返回",
                        91.0,
                        12),
                new DemoResource(
                        "demo_showcase_quiz",
                        "递归与 Cache 分层小测",
                        "quiz",
                        1,
                        List.of("二叉树递归遍历", "Cache 映射方式"),
                        "medium",
                        "用于定位递归出口和 Cache 地址拆分错误。",
                        "markdown",
                        "## 分层小测\n\n1. 前序遍历的访问顺序是什么？\n2. 直接映射 Cache 中主存块可映射到几个 Cache 行？\n3. 为什么递归函数必须设置终止条件？",
                        88.0,
                        15),
                new DemoResource(
                        "demo_showcase_codelab",
                        "二叉树遍历代码实验",
                        "codelab",
                        1,
                        List.of("二叉树递归遍历"),
                        "medium",
                        "补全遍历函数并观察调用栈变化。",
                        "markdown",
                        "## 实验目标\n补全前序遍历并记录调用栈。\n\n```java\nvoid preorder(Node node) {\n  if (node == null) return;\n  System.out.println(node.value);\n  preorder(node.left);\n  preorder(node.right);\n}\n```\n\n验收：空树、单节点和三层完全二叉树均输出正确。",
                        93.0,
                        25),
                new DemoResource(
                        "demo_showcase_animation",
                        "Cache 直接映射动画脚本",
                        "animation_script",
                        2,
                        List.of("Cache 映射方式"),
                        "basic",
                        "用地址标签、行号和块内偏移演示直接映射冲突。",
                        "markdown",
                        "## 分镜\n\n- 0–10 秒：拆分主存地址为 Tag、Index、Offset。\n- 10–25 秒：Index 定位唯一 Cache 行。\n- 25–40 秒：比较 Tag，演示命中与冲突不命中。\n- 40–55 秒：用两个相同 Index 的主存块展示抖动。",
                        90.0,
                        10),
                new DemoResource(
                        "demo_showcase_reading",
                        "Cache 映射方式拓展阅读",
                        "reading",
                        2,
                        List.of("Cache 映射方式", "存储系统与 Cache"),
                        "advanced",
                        "从冲突率与硬件开销理解三种映射策略的权衡。",
                        "markdown",
                        "## 阅读问题\n\n1. 为什么直接映射硬件简单但冲突率较高？\n2. 组相联如何在比较器数量和冲突率之间折中？\n3. 替换算法只在哪些映射方式中需要？",
                        87.0,
                        16));
        for (DemoResource resource : resources) {
            insertResource(resource, demoUserId);
        }
    }

    private void insertResource(DemoResource resource, long demoUserId) {
        Map<String, Object> evidence = Map.of(
                "chunk_id", "demo_chunk_" + resource.resourceId(),
                "title", resource.knowledgePoints().get(0) + "课程证据",
                "content", "来自已导入课程课件的概念、边界和例题证据。",
                "score", 0.92,
                "source", resource.courseId() == 1
                        ? "data_structures_algorithms/courseware"
                        : "computer_organization/courseware");
        Map<String, Object> safety = Map.of(
                "passed", true,
                "risk_level", "low",
                "issues", List.of(),
                "suggestions", List.of("保留课程证据引用"),
                "confidence", 0.96);
        Map<String, Object> quality = qualityEvaluation(resource.score());
        jdbcTemplate.update(
                """
                INSERT INTO resources (
                    resource_id, title, resource_type, course_id, knowledge_points, difficulty,
                    summary, status, content_format, content, evidence, safety, storage_status,
                    tags, version, created_by, owner_user_id, source_task_id, personalized_reason,
                    estimated_minutes, profile_fingerprint, quality_evaluation, generation_mode,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'published', ?, ?, ?, ?, 'seeded_demo', ?, 1,
                          ?, ?, ?, ?, ?, 'software-cup-demo-profile', ?, 'deterministic_fallback',
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                resource.resourceId(),
                resource.title(),
                resource.resourceType(),
                resource.courseId(),
                jsonCodec.toJson(resource.knowledgePoints()),
                resource.difficulty(),
                resource.summary(),
                resource.contentFormat(),
                resource.content(),
                jsonCodec.toJson(List.of(evidence)),
                jsonCodec.toJson(safety),
                jsonCodec.toJson(resource.knowledgePoints()),
                demoUserId,
                demoUserId,
                DEMO_SOURCE,
                "根据演示学生画像中的薄弱点和资源偏好准备",
                resource.estimatedMinutes(),
                jsonCodec.toJson(quality));
    }

    private Map<String, Object> qualityEvaluation(double totalScore) {
        Map<String, Object> dimensions = new LinkedHashMap<>();
        dimensions.put("evidence_coverage", dimension(Math.min(98, totalScore + 2), 0.30));
        dimensions.put("structural_completeness", dimension(totalScore, 0.20));
        dimensions.put("knowledge_consistency", dimension(Math.min(99, totalScore + 3), 0.20));
        dimensions.put("difficulty_alignment", dimension(Math.max(80, totalScore - 4), 0.15));
        dimensions.put("safety_compliance", dimension(98, 0.15));
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("evaluator_version", "resource-quality-v1");
        quality.put("total_score", totalScore);
        quality.put("grade", totalScore >= 90 ? "A" : "B");
        quality.put("gate_passed", true);
        quality.put("dimensions", dimensions);
        quality.put("issues", List.of());
        quality.put("recommendations", List.of("继续结合测验反馈动态更新资源"));
        return quality;
    }

    private Map<String, Object> dimension(double score, double weight) {
        return Map.of("score", score, "weight", weight, "passed", score >= 60, "findings", List.of());
    }

    private void prepareMastery() {
        insertMastery("二叉树递归遍历", 5, 3, 66, 4, 270, "quiz_result");
        insertMastery("图的遍历", 4, 3, 76, 2, 150, "resource_completion");
        insertMastery("Cache 映射方式", 6, 3, 58, 5, 280, "quiz_result");
    }

    private void insertMastery(
            String point,
            int attempts,
            int correct,
            int score,
            int behaviorWeight,
            int behaviorScoreSum,
            String lastEventType) {
        jdbcTemplate.update(
                """
                INSERT INTO knowledge_mastery (
                    student_id, knowledge_point, attempts, correct_count, mastery_score,
                    behavior_event_count, behavior_weight, behavior_score_sum,
                    last_event_type, last_event_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 3, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                DEMO_STUDENT,
                point,
                attempts,
                correct,
                score,
                behaviorWeight,
                behaviorScoreSum,
                lastEventType);
    }

    private void prepareLearningEvents(long demoUserId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8));
        List<DemoEvent> events = List.of(
                new DemoEvent("profile", 1, "二叉树递归遍历", "profile", "demo-profile", "update", 70, 2, now.minusDays(6)),
                new DemoEvent("lecture", 1, "二叉树递归遍历", "resource", "demo_showcase_lecture", "complete", 72, 2, now.minusDays(5)),
                new DemoEvent("mindmap", 1, "二叉树递归遍历", "resource", "demo_showcase_mindmap", "complete", 78, 2, now.minusDays(4)),
                new DemoEvent("codelab", 1, "图的遍历", "resource", "demo_showcase_codelab", "complete", 80, 3, now.minusDays(3)),
                new DemoEvent("cache-reading", 2, "Cache 映射方式", "resource", "demo_showcase_reading", "start", 55, 1, now.minusDays(2)),
                new DemoEvent("cache-animation", 2, "Cache 映射方式", "resource", "demo_showcase_animation", "complete", 68, 2, now.minusDays(1)),
                new DemoEvent("quiz", 2, "Cache 映射方式", "quiz", "demo-cache-quiz", "submit", 58, 3, now.minusHours(8)),
                new DemoEvent("path", 1, "二叉树递归遍历", "learning_path", "demo_path_software_cup", "complete", 74, 2, now.minusHours(2)));
        int sequence = 1;
        for (DemoEvent event : events) {
            jdbcTemplate.update(
                    """
                    INSERT INTO learning_events (
                        event_id, idempotency_key, student_id, user_id, event_type, course_id,
                        knowledge_point, source_type, source_id, action, progress_percent,
                        sequence_no, evidence_weight, evidence_score, metadata, occurred_at
                    ) VALUES (?, ?, ?, ?, 'demo_learning', ?, ?, ?, ?, ?, 100, ?, ?, ?, ?, ?)
                    """,
                    "demo_prepare_event_" + event.key(),
                    "demo_prepare:" + event.key(),
                    DEMO_STUDENT,
                    demoUserId,
                    event.courseId(),
                    event.knowledgePoint(),
                    event.sourceType(),
                    event.sourceId(),
                    event.action(),
                    sequence++,
                    event.weight(),
                    event.score(),
                    jsonCodec.toJson(Map.of("scenario", SCENARIO_KEY, "prepared", true)),
                    Timestamp.from(event.occurredAt().toInstant()));
        }
    }

    private long prepareQuiz() {
        jdbcTemplate.update(
                "INSERT INTO quizzes (title, course_id, difficulty, status, created_at) VALUES (?, 2, 'medium', 'active', CURRENT_TIMESTAMP)",
                DEMO_QUIZ_TITLE);
        Long quizId = jdbcTemplate.queryForObject("SELECT MAX(quiz_id) FROM quizzes", Long.class);
        if (quizId == null) {
            throw new IllegalStateException("演示测验创建失败");
        }
        List<DemoQuestion> questions = List.of(
                new DemoQuestion(1, "Cache 映射方式", "直接映射中，一个主存块可映射到几个 Cache 行？", List.of("1 个", "2 个", "任意行", "一组中的任意行"), "1 个", true),
                new DemoQuestion(2, "Cache 映射方式", "组相联映射的地址中，组号用于完成什么操作？", List.of("选择组", "选择主存", "选择字节", "判断写策略"), "选择组", false),
                new DemoQuestion(3, "二叉树递归遍历", "前序遍历的访问顺序是？", List.of("根-左-右", "左-根-右", "左-右-根", "右-根-左"), "根-左-右", true));
        List<Long> questionIds = new java.util.ArrayList<>();
        for (DemoQuestion question : questions) {
            jdbcTemplate.update(
                    """
                    INSERT INTO quiz_questions (
                        quiz_id, question_order, question_type, difficulty, knowledge_point,
                        question, options, answer, explanation
                    ) VALUES (?, ?, 'single_choice', 'medium', ?, ?, ?, ?, ?)
                    """,
                    quizId,
                    question.order(),
                    question.knowledgePoint(),
                    question.question(),
                    jsonCodec.toJson(question.options()),
                    question.answer(),
                    "依据课程知识库中的定义、地址拆分规则和例题判断。");
            questionIds.add(jdbcTemplate.queryForObject("SELECT MAX(question_id) FROM quiz_questions", Long.class));
        }
        jdbcTemplate.update(
                """
                INSERT INTO quiz_attempts (
                    quiz_id, student_id, answers, score, weak_points, mistake_patterns,
                    recommendation, submitted_at
                ) VALUES (?, ?, ?, 67, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                quizId,
                DEMO_STUDENT,
                jsonCodec.toJson(List.of("1 个", "选择字节", "根-左-右")),
                jsonCodec.toJson(List.of("Cache 映射方式")),
                jsonCodec.toJson(List.of("地址字段混淆", "组号与块内偏移混淆")),
                "先查看 Cache 地址拆分流程图，再完成 3 道组相联专项题。");
        Long attemptId = jdbcTemplate.queryForObject("SELECT MAX(attempt_id) FROM quiz_attempts", Long.class);
        for (int index = 0; index < questions.size(); index++) {
            DemoQuestion question = questions.get(index);
            String submitted = question.correct() ? question.answer() : "选择字节";
            jdbcTemplate.update(
                    """
                    INSERT INTO quiz_attempt_items (
                        attempt_id, question_id, submitted_answer, correct_answer, correct, created_at
                    ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """,
                    attemptId,
                    questionIds.get(index),
                    submitted,
                    question.answer(),
                    question.correct());
            if (!question.correct()) {
                jdbcTemplate.update(
                        """
                        INSERT INTO wrong_question_book (
                            student_id, quiz_id, question_id, knowledge_point, mistake_pattern, created_at
                        ) VALUES (?, ?, ?, ?, '地址字段混淆', CURRENT_TIMESTAMP)
                        """,
                        DEMO_STUDENT,
                        quizId,
                        questionIds.get(index),
                        question.knowledgePoint());
            }
        }
        return quizId;
    }

    private void prepareLearningPath() {
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM learning_paths WHERE student_id = ?",
                Integer.class,
                DEMO_STUDENT);
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("path_id", "demo_path_software_cup");
        path.put("path_title", "递归与 Cache 薄弱点 7 天强化路径");
        path.put("target", "掌握二叉树递归调用栈与 Cache 地址映射");
        path.put("daily_minutes", 45);
        path.put("version", (maxVersion == null ? 0 : maxVersion) + 1);
        path.put("previous_version", Math.max(0, maxVersion == null ? 0 : maxVersion));
        path.put("replan_trigger", "quiz_evaluation");
        path.put("source_evaluation_task_id", DEMO_SOURCE);
        path.put("personalization_summary", "依据 Cache 小测错因和资源偏好，先图解再代码实践，最后用分层小测验证。");
        path.put("adjustment_strategy", "Cache 映射掌握度低于 70%，插入流程拆解与专项小测；递归部分保持代码实验。");
        path.put("evidence_chunk_ids", List.of("demo_chunk_recursive", "demo_chunk_cache"));
        path.put("completed_days", List.of(1));
        path.put("daily_plan", List.of(
                pathDay(1, "递归调用栈诊断", "复盘函数帧和终止条件", "lecture", "递归调用栈个性化讲义", 35, "basic"),
                pathDay(2, "二叉树递归图解", "能手工画出三层调用栈", "mindmap", "二叉树遍历知识导图", 40, "medium"),
                pathDay(3, "递归代码实验", "独立完成三种遍历", "codelab", "二叉树遍历代码实验", 50, "medium"),
                pathDay(4, "Cache 地址拆分", "区分 Tag、Index、Offset", "flowchart", "Cache 地址拆分流程图", 40, "basic"),
                pathDay(5, "映射策略对比", "解释冲突率与硬件开销权衡", "reading", "Cache 映射方式拓展阅读", 45, "advanced"),
                pathDay(6, "Cache 专项补救", "修正组号与偏移混淆", "quiz", "Cache 映射方式诊断小测", 35, "medium"),
                pathDay(7, "综合迁移评估", "完成递归与 Cache 综合题", "quiz", "递归与 Cache 分层小测", 45, "medium")));
        jdbcTemplate.update(
                """
                INSERT INTO learning_paths (
                    path_id, student_id, version, path_payload, source_task_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                "demo_path_software_cup_" + ((maxVersion == null ? 0 : maxVersion) + 1),
                DEMO_STUDENT,
                (maxVersion == null ? 0 : maxVersion) + 1,
                jsonCodec.toJson(path),
                DEMO_SOURCE);
    }

    private Map<String, Object> pathDay(
            int day,
            String theme,
            String outcome,
            String type,
            String title,
            int minutes,
            String difficulty) {
        return Map.of(
                "day", day,
                "theme", theme,
                "tasks", List.of(Map.of("type", type, "title", title, "estimated_minutes", minutes)),
                "expected_outcome", outcome,
                "reason", day <= 3 ? "匹配图解与代码偏好" : "由 Cache 测验错因触发补救",
                "difficulty", difficulty,
                "evidence_chunk_ids", List.of(day <= 3 ? "demo_chunk_recursive" : "demo_chunk_cache"));
    }

    private void prepareTutorSession() {
        jdbcTemplate.update(
                """
                INSERT INTO tutor_sessions (session_id, student_id, course_id, title, created_at, updated_at)
                VALUES ('demo_tutor_software_cup', ?, 2, 'Cache 地址拆分答疑', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                DEMO_STUDENT);
        jdbcTemplate.update(
                "INSERT INTO tutor_messages (session_id, role, content, evidence, created_at) VALUES ('demo_tutor_software_cup', 'user', '直接映射 Cache 的地址为什么要拆成三段？', '[]', CURRENT_TIMESTAMP)");
        jdbcTemplate.update(
                """
                INSERT INTO tutor_messages (session_id, role, content, evidence, created_at)
                VALUES ('demo_tutor_software_cup', 'assistant', ?, ?, CURRENT_TIMESTAMP)
                """,
                "Tag 用于确认当前行保存的是哪个主存块，Index 用于定位唯一 Cache 行，Offset 用于选择块内字节。三段共同完成命中判断与数据定位。",
                jsonCodec.toJson(List.of(Map.of(
                        "chunk_id", "demo_chunk_cache",
                        "title", "Cache 地址映射课程证据",
                        "score", 0.94,
                        "source", "computer_organization/courseware"))));
    }

    private void prepareResourceInteractions(long demoUserId) {
        List<String> completed = List.of(
                "demo_showcase_lecture",
                "demo_showcase_mindmap",
                "demo_showcase_codelab",
                "demo_showcase_animation");
        for (String resourceId : completed) {
            jdbcTemplate.update(
                    """
                    INSERT INTO resource_interactions (
                        resource_id, user_id, action, rating, progress_percent, created_at
                    ) VALUES (?, ?, 'complete', 5, 100, CURRENT_TIMESTAMP)
                    """,
                    resourceId,
                    demoUserId);
        }
        jdbcTemplate.update(
                """
                INSERT INTO resource_interactions (
                    resource_id, user_id, action, rating, progress_percent, created_at
                ) VALUES ('demo_showcase_reading', ?, 'start', NULL, 45, CURRENT_TIMESTAMP)
                """,
                demoUserId);
    }

    private DemoDataSummary summary(long classId, long quizId) {
        int resources = count("SELECT COUNT(*) FROM resources WHERE source_task_id = ?", DEMO_SOURCE);
        int profiles = count(
                "SELECT COUNT(*) FROM profile_versions WHERE student_id = ? AND source_task_id = ?",
                DEMO_STUDENT,
                DEMO_SOURCE);
        int paths = count(
                "SELECT COUNT(*) FROM learning_paths WHERE student_id = ? AND source_task_id = ?",
                DEMO_STUDENT,
                DEMO_SOURCE);
        int attempts = count(
                "SELECT COUNT(*) FROM quiz_attempts qa JOIN quizzes q ON q.quiz_id = qa.quiz_id WHERE qa.student_id = ? AND q.title = ?",
                DEMO_STUDENT,
                DEMO_QUIZ_TITLE);
        int events = count(
                "SELECT COUNT(*) FROM learning_events WHERE student_id = ? AND idempotency_key LIKE 'demo_prepare:%'",
                DEMO_STUDENT);
        int mastery = count(
                "SELECT COUNT(*) FROM knowledge_mastery WHERE student_id = ? AND knowledge_point IN (?, ?, ?)",
                DEMO_STUDENT,
                "二叉树递归遍历",
                "图的遍历",
                "Cache 映射方式");
        int tutorMessages = count(
                "SELECT COUNT(*) FROM tutor_messages WHERE session_id = 'demo_tutor_software_cup'");
        boolean ready = classId > 0
                && resources >= 6
                && profiles >= 1
                && paths >= 1
                && attempts >= 1
                && events >= 8
                && mastery >= 3
                && tutorMessages >= 2;
        return new DemoDataSummary(
                ready,
                classId,
                quizId,
                resources,
                profiles,
                paths,
                attempts,
                events,
                mastery,
                tutorMessages,
                OffsetDateTime.now(ZoneOffset.ofHours(8)));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private long requiredUserId(String username) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ? AND status = 'active'",
                Long.class,
                username);
        if (value == null) {
            throw new IllegalStateException("缺少演示账号: " + username);
        }
        return value;
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.ofHours(8));
    }

    private record DemoResource(
            String resourceId,
            String title,
            String resourceType,
            int courseId,
            List<String> knowledgePoints,
            String difficulty,
            String summary,
            String contentFormat,
            String content,
            double score,
            int estimatedMinutes) {}

    private record DemoEvent(
            String key,
            int courseId,
            String knowledgePoint,
            String sourceType,
            String sourceId,
            String action,
            int score,
            int weight,
            OffsetDateTime occurredAt) {}

    private record DemoQuestion(
            int order,
            String knowledgePoint,
            String question,
            List<String> options,
            String answer,
            boolean correct) {}

    public record DemoDataSummary(
            boolean ready,
            long classId,
            long quizId,
            int resourceCount,
            int profileVersionCount,
            int learningPathCount,
            int quizAttemptCount,
            int learningEventCount,
            int masteryPointCount,
            int tutorMessageCount,
            OffsetDateTime checkedAt) {}

    public record DemoRunRow(
            String runId,
            String scenarioKey,
            String status,
            long preparedBy,
            Map<String, Object> summary,
            OffsetDateTime completedAt) {}
}
