package com.edupath.evaluation;

import com.edupath.ai.AiAgentClient;
import com.edupath.auth.AuthContext;
import com.edupath.auth.RoleGuard;
import com.edupath.common.ExternalServiceException;
import com.edupath.evaluation.PracticeFlow.Attempt;
import com.edupath.evaluation.PracticeFlow.Citation;
import com.edupath.evaluation.PracticeFlow.Feedback;
import com.edupath.evaluation.PracticeFlow.FollowUp;
import com.edupath.evaluation.PracticeFlow.State;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TreePracticeService {

    private static final Exercise INITIAL = new Exercise("tree-v1-initial", "preorder", List.of(
            new Node("A", "B", "C"), new Node("B", null, "D"), new Node("C", "E", "F"),
            leaf("D"), leaf("E"), leaf("F")));
    private static final List<Node> TIMING_NODES = List.of(
            new Node("M", "J", "R"), new Node("J", "H", "L"), new Node("R", null, "T"),
            leaf("H"), leaf("L"), leaf("T"));
    private static final Exercise LEGACY_TIMING = new Exercise("tree-v1-timing", "inorder", TIMING_NODES);
    private static final Exercise TIMING = new Exercise("tree-v2-timing", "preorder", TIMING_NODES);
    private static final Exercise DIRECTION = new Exercise("tree-v1-direction", "preorder", List.of(
            new Node("P", "Z", "B"), new Node("Z", null, "M"), new Node("B", "R", null), leaf("M"), leaf("R")));
    private static final Exercise COVERAGE = new Exercise("tree-v1-coverage", "postorder", List.of(
            new Node("K", "D", "S"), new Node("D", null, "F"), new Node("S", "P", null), leaf("F"), leaf("P")));
    private static final Exercise TRANSFER = new Exercise("tree-v1-transfer", "postorder", List.of(
            new Node("G", "M", "T"), new Node("M", "B", "Q"), new Node("T", null, "Z"),
            leaf("B"), leaf("Q"), leaf("Z")));
    private static final Map<String, Exercise> EXERCISES = List.of(INITIAL, LEGACY_TIMING, TIMING, DIRECTION, COVERAGE, TRANSFER)
            .stream().collect(Collectors.toUnmodifiableMap(Exercise::id, Function.identity()));
    private static final Map<String, String> CITED_SECTIONS = Map.of(
            "", "三种遍历中的访问时机", "visit_timing", "三种遍历中的访问时机",
            "right_before_left", "固定左右子树顺序", "sequence_order", "固定左右子树顺序",
            "node_coverage", "检查节点是否恰好输出一次");

    private static final PracticeFlow<Exercise, List<String>> FLOW = new PracticeFlow<>(INITIAL, (feedback, completedIds) ->
            switch (feedback.mistake()) {
                case "visit_timing" -> completedIds.contains(LEGACY_TIMING.id())
                        ? new FollowUp<>(LEGACY_TIMING, "这条记录对应旧版中序复测。")
                        : new FollowUp<>(TIMING, "下一题继续练前序遍历。到达一个节点就先写下它，再依次处理左、右子树。");
                case "right_before_left", "sequence_order" -> new FollowUp<>(DIRECTION,
                        "先走完左子树，包括它里面的右孩子，再回到根节点处理右子树。");
                case "node_coverage" -> new FollowUp<>(COVERAGE, "提交前检查每个非空节点是否恰好出现一次。");
                default -> new FollowUp<>(TRANSFER, "这次用后序遍历，核对父节点何时写入序列。");
            });

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AiAgentClient ai;
    private final RoleGuard roles;

    public TreePracticeService(JdbcTemplate jdbc, ObjectMapper mapper, AiAgentClient ai, RoleGuard roles) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.ai = ai;
        this.roles = roles;
    }

    public State<Exercise, List<String>> read() {
        return FLOW.snapshot(attempts(studentId()));
    }

    public State<Exercise, List<String>> submit(Submission submission) {
        long student = studentId();
        List<String> answer = readSequence(submission.sequence());
        return FLOW.submit(submission.exerciseId(), answer, () -> attempts(student),
                exercise -> grade(exercise, answer), (exercise, feedback) -> jdbc.update("""
                        INSERT INTO tree_practice_attempts (student_user_id, exercise_id, answer_json, feedback_json)
                        VALUES (?, ?, ?, ?)
                        """, student, exercise.id(), encode(answer), encode(feedback)));
    }

    private long studentId() {
        roles.requireAny("student");
        return AuthContext.requirePrincipal().userId();
    }

    private Feedback<List<String>> grade(Exercise exercise, List<String> answer) {
        Map<String, Object> result = ai.diagnoseTreePractice(Map.of(
                "topic", "binary_tree", "order", exercise.order(), "nodes", exercise.nodes(), "answer", answer));
        Diagnosis diagnosis;
        try {
            diagnosis = mapper.convertValue(result, Diagnosis.class);
        } catch (IllegalArgumentException invalidResponse) {
            throw new ExternalServiceException("二叉树诊断", "诊断响应格式错误，可重试本题", invalidResponse);
        }
        List<String> expected = exercise.expected();
        boolean correct = expected.equals(answer);
        Citation citation = diagnosis.citation();
        String allowedSection = CITED_SECTIONS.get(diagnosis.mistake() == null ? "invalid" : diagnosis.mistake());
        if (!(correct ? "confirm" : "diagnose").equals(diagnosis.decision())
                || !expected.equals(diagnosis.expected()) || allowedSection == null
                || correct != "".equals(diagnosis.mistake())
                || diagnosis.explanation() == null || diagnosis.explanation().isBlank()
                || citation == null || !"public_course".equals(citation.scope())
                || !"EDUPATH-TREE-DFS-V1".equals(citation.marker())
                || !"binary-tree-traversal.md".equals(citation.document()) || !"v1".equals(citation.sourceVersion())
                || !allowedSection.equals(citation.section()) || citation.excerpt() == null || citation.excerpt().isBlank()) {
            throw new ExternalServiceException("二叉树诊断", "判定或讲义引用校验失败，可重试本题");
        }
        return new Feedback<>(correct, expected, diagnosis.mistake(), diagnosis.explanation(), citation);
    }

    private Map<String, Attempt<Exercise, List<String>>> attempts(long student) {
        return jdbc.query("""
                SELECT exercise_id, answer_json, feedback_json, submitted_at
                FROM tree_practice_attempts WHERE student_user_id = ?
                """, (row, ignored) -> new Attempt<>(
                        EXERCISES.get(row.getString("exercise_id")),
                        decode(row.getString("answer_json"), new TypeReference<List<String>>() {}),
                        decode(row.getString("feedback_json"), new TypeReference<Feedback<List<String>>>() {}),
                        row.getTimestamp("submitted_at").toInstant()), student)
                .stream().collect(Collectors.toMap(attempt -> attempt.exercise().id(), Function.identity()));
    }

    private static List<String> readSequence(JsonNode sequence) {
        if (sequence == null || !sequence.isArray() || sequence.isEmpty() || sequence.size() > 24) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提交 1 至 24 个节点字母");
        }
        List<String> normalized = new ArrayList<>(sequence.size());
        for (JsonNode value : sequence) {
            if (!value.isTextual() || !value.textValue().matches("[A-Za-z]")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每个节点应为单个英文字母");
            }
            normalized.add(value.textValue().toUpperCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    private String encode(Object data) {
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存二叉树练习", exception);
        }
    }

    private <T> T decode(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取二叉树练习", exception);
        }
    }

    private static Node leaf(String label) {
        return new Node(label, null, null);
    }

    public record Node(String id, String left, String right) {}

    public record Exercise(String id, String order, List<Node> nodes) implements PracticeFlow.Exercise {
        List<String> expected() {
            Map<String, Node> byId = nodes.stream().collect(Collectors.toMap(Node::id, Function.identity()));
            List<String> sequence = new ArrayList<>(nodes.size());
            traverse(nodes.get(0), byId, sequence);
            return sequence;
        }

        private void traverse(Node node, Map<String, Node> byId, List<String> sequence) {
            if (node == null) {
                return;
            }
            if ("preorder".equals(order)) {
                sequence.add(node.id());
            }
            traverse(byId.get(node.left()), byId, sequence);
            if ("inorder".equals(order)) {
                sequence.add(node.id());
            }
            traverse(byId.get(node.right()), byId, sequence);
            if ("postorder".equals(order)) {
                sequence.add(node.id());
            }
        }
    }

    public record Submission(String exerciseId, JsonNode sequence) {}

    record Diagnosis(String decision, String mistake, String explanation, List<String> expected, Citation citation) {}
}
