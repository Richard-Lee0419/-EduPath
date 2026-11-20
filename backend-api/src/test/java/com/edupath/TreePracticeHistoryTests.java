package com.edupath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.edupath.auth.AuthContext;
import com.edupath.auth.JwtService;
import com.edupath.auth.UserRepository;
import com.edupath.evaluation.PracticeFlow.Citation;
import com.edupath.evaluation.PracticeFlow.Feedback;
import com.edupath.evaluation.TreePracticeService;
import com.edupath.evaluation.TreePracticeService.Submission;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TreePracticeHistoryTests {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private TreePracticeService practice;
    @Autowired private ObjectMapper mapper;

    @Test
    void unfinishedTimingUsesPreorderAndCompletedLegacyInorderCanBeReplayed() throws Exception {
        String username = "timing-history-" + UUID.randomUUID();
        long student = users.create(username, "unused", "student", "active");
        AuthContext.set(new JwtService.AuthPrincipal(student, username, "student", "history-test", Long.MAX_VALUE));
        try {
            List<String> initialAnswer = List.of("B", "D", "A", "E", "C", "F");
            Citation citation = new Citation("EDUPATH-TREE-DFS-V1", "binary-tree-traversal.md",
                    "三种遍历中的访问时机", "前序在两次递归之前访问根节点。", "public_course", "v1");
            insert(student, "tree-v1-initial", initialAnswer, new Feedback<>(false, List.of("A", "B", "D", "C", "E", "F"),
                    "visit_timing", "初次作答用了中序。", citation));

            var pending = practice.read();
            assertThat(pending.stage()).isEqualTo("retest");
            assertThat(pending.nextExercise().id()).isEqualTo("tree-v2-timing");
            assertThat(pending.nextExercise().order()).isEqualTo("preorder");
            assertThat(pending.focus()).contains("下一题继续练前序遍历");

            List<String> legacyAnswer = List.of("H", "J", "L", "M", "R", "T");
            Submission legacySubmission = new Submission("tree-v1-timing", mapper.valueToTree(legacyAnswer));
            assertThatThrownBy(() -> practice.submit(legacySubmission))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("409 CONFLICT");

            insert(student, "tree-v1-timing", legacyAnswer,
                    new Feedback<>(true, legacyAnswer, "", "旧版中序结果正确。", citation));
            var history = practice.read();
            assertThat(history.stage()).isEqualTo("complete");
            assertThat(history.nextExercise()).isNull();
            assertThat(history.retest().exercise().order()).isEqualTo("inorder");
            assertThat(history.retest().answer()).containsExactlyElementsOf(legacyAnswer);
            assertThat(practice.submit(legacySubmission)).isEqualTo(history);
        } finally {
            AuthContext.clear();
        }
    }

    private void insert(long student, String exerciseId, List<String> answer, Feedback<List<String>> feedback)
            throws Exception {
        jdbc.update("""
                INSERT INTO tree_practice_attempts (student_user_id, exercise_id, answer_json, feedback_json)
                VALUES (?, ?, ?, ?)
                """, student, exerciseId, mapper.writeValueAsString(answer), mapper.writeValueAsString(feedback));
    }
}
