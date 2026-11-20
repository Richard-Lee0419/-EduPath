package com.edupath.evaluation;

import com.edupath.ai.AiAgentClient;
import com.edupath.auth.AuthContext;
import com.edupath.auth.RoleGuard;
import com.edupath.common.ExternalServiceException;
import com.edupath.evaluation.PracticeFlow.Attempt;
import com.edupath.evaluation.PracticeFlow.Citation;
import com.edupath.evaluation.PracticeFlow.Feedback;
import com.edupath.evaluation.PracticeFlow.FollowUp;
import com.edupath.evaluation.PracticeFlow.Provenance;
import com.edupath.evaluation.PracticeFlow.State;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CachePracticeService {

    private static final Exercise INITIAL = new Exercise("cache-v1-initial", 32, 4096, 16, 4);
    private static final Exercise SETS = new Exercise("cache-v1-sets", 32, 4096, 16, 2);
    private static final Exercise OFFSET = new Exercise("cache-v1-offset", 32, 4096, 32, 4);
    private static final Exercise TRANSFER = new Exercise("cache-v1-transfer", 32, 8192, 32, 4);
    private static final Set<String> MISTAKES = Set.of(
            "index_ignores_associativity", "index_bit_count", "offset_bit_count", "tag_or_multiple_fields");

    private static final PracticeFlow<Exercise, Bits> FLOW = new PracticeFlow<>(INITIAL, (feedback, ignored) ->
            switch (feedback.mistake()) {
                case "index_ignores_associativity", "index_bit_count" ->
                        new FollowUp<>(SETS, "先用总块数除以相联度，再根据组号范围计算 Index。");
                case "offset_bit_count" ->
                        new FollowUp<>(OFFSET, "字节偏移从 0 开始。先写出新题块内最后一个字节的偏移，再计算 Offset 所需位数。");
                default -> new FollowUp<>(TRANSFER, "分别算出组数与块内位置，再从地址宽度扣除 Index 和 Offset。");
            });

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AiAgentClient ai;
    private final RoleGuard roles;

    public CachePracticeService(JdbcTemplate jdbc, ObjectMapper mapper, AiAgentClient ai, RoleGuard roles) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.ai = ai;
        this.roles = roles;
    }

    public State<Exercise, Bits> read() {
        return FLOW.snapshot(attempts(studentId()));
    }

    public State<Exercise, Bits> submit(Submission submission) {
        long student = studentId();
        Bits answer = new Bits(readBits(submission.tag()), readBits(submission.index()), readBits(submission.offset()));
        return FLOW.submit(submission.exerciseId(), answer, () -> attempts(student),
                exercise -> grade(exercise, answer), (exercise, feedback) -> jdbc.update("""
                        INSERT INTO cache_practice_attempts
                            (student_user_id, exercise_id, tag_bits, index_bits, offset_bits, feedback_json)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, student, exercise.id(), answer.tag(), answer.index(), answer.offset(), encode(feedback)));
    }

    private long studentId() {
        roles.requireAny("student");
        return AuthContext.requirePrincipal().userId();
    }

    private Feedback<Bits> grade(Exercise exercise, Bits answer) {
        Map<String, Object> result = ai.diagnoseCachePractice("""
                %d 位地址，按字节寻址，Cache 数据容量 %d B，块大小 %d B，%d 路组相联。
                学生作答：Tag %d 位，Index %d 位，Offset %d 位。
                """.formatted(exercise.addressBits(), exercise.capacityBytes(), exercise.blockBytes(),
                exercise.ways(), answer.tag(), answer.index(), answer.offset()));
        Diagnosis diagnosis;
        try {
            diagnosis = mapper.convertValue(result, Diagnosis.class);
        } catch (IllegalArgumentException invalidResponse) {
            throw new ExternalServiceException("Cache 诊断", "诊断响应格式错误，可重试本题", invalidResponse);
        }
        Bits expected = exercise.expected();
        boolean correct = expected.equals(answer);
        boolean validMistake = correct ? "".equals(diagnosis.mistake())
                : diagnosis.mistake() != null && MISTAKES.contains(diagnosis.mistake());
        Citation citation = diagnosis.citation();
        if (!(correct ? "confirm" : "diagnose").equals(diagnosis.decision()) || !validMistake
                || diagnosis.explanation() == null || diagnosis.explanation().isBlank()
                || citation == null || !"public_course".equals(citation.scope())
                || !"EDUPATH-CACHE-ADDR-V1".equals(citation.marker())
                || citation.excerpt() == null || citation.excerpt().isBlank()) {
            throw new ExternalServiceException("Cache 诊断", "判定或讲义引用校验失败，可重试本题");
        }
        return new Feedback<>(correct, expected, diagnosis.mistake(), diagnosis.explanation(), citation, diagnosis.provenance());
    }

    private Map<String, Attempt<Exercise, Bits>> attempts(long student) {
        return jdbc.query("""
                SELECT exercise_id, tag_bits, index_bits, offset_bits, feedback_json, submitted_at
                FROM cache_practice_attempts WHERE student_user_id = ?
                """, (row, ignored) -> new Attempt<>(
                        exercise(row.getString("exercise_id")),
                        new Bits(row.getInt("tag_bits"), row.getInt("index_bits"), row.getInt("offset_bits")),
                        decode(row.getString("feedback_json")), row.getTimestamp("submitted_at").toInstant()), student)
                .stream().collect(Collectors.toMap(attempt -> attempt.exercise().id(), Function.identity()));
    }

    private static Exercise exercise(String id) {
        return switch (id) {
            case "cache-v1-initial" -> INITIAL;
            case "cache-v1-sets" -> SETS;
            case "cache-v1-offset" -> OFFSET;
            case "cache-v1-transfer" -> TRANSFER;
            default -> throw new IllegalStateException("未知 Cache 练习版本 " + id);
        };
    }

    private static int readBits(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < 0 || value.intValue() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tag、Index、Offset 必须是 0 到 128 的整数");
        }
        return value.intValue();
    }

    private String encode(Feedback<Bits> feedback) {
        try {
            return mapper.writeValueAsString(feedback);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存 Cache 诊断", exception);
        }
    }

    private Feedback<Bits> decode(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Feedback<Bits>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取 Cache 诊断", exception);
        }
    }

    public record Submission(String exerciseId, JsonNode tag, JsonNode index, JsonNode offset) {}

    public record Exercise(String id, int addressBits, int capacityBytes, int blockBytes, int ways) implements PracticeFlow.Exercise {
        Bits expected() {
            int offset = Integer.numberOfTrailingZeros(blockBytes);
            int index = Integer.numberOfTrailingZeros(capacityBytes / blockBytes / ways);
            return new Bits(addressBits - index - offset, index, offset);
        }
    }

    public record Bits(int tag, int index, int offset) {}

    record Diagnosis(String decision, String mistake, String explanation, Citation citation, Provenance provenance) {}
}
