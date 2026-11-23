package com.edupath.course;

import com.edupath.auth.PasswordService;
import com.edupath.auth.UserRepository;
import com.edupath.profile.ProfileRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewDemoScenarioService {
    private static final String TOPIC = "Cache 地址拆分";
    private final JdbcTemplate jdbc;
    private final CourseRepository courses;
    private final ClassRepository classes;
    private final UserRepository users;
    private final PasswordService passwords;
    private final ProfileRepository profiles;

    public ReviewDemoScenarioService(JdbcTemplate jdbc, CourseRepository courses, ClassRepository classes,
            UserRepository users, PasswordService passwords, ProfileRepository profiles) {
        this.jdbc = jdbc;
        this.courses = courses;
        this.classes = classes;
        this.users = users;
        this.passwords = passwords;
        this.profiles = profiles;
    }

    @Transactional
    public void prepare(long linkId, long teacherId, long classId, UserRepository.UserRecord student) {
        // Concurrent role logins share the same course and roster. Keep initialization local to the database.
        Long preparedCourseId = jdbc.queryForObject(
                "SELECT COALESCE(sample_course_id, 0) FROM review_links WHERE id = ? FOR UPDATE",
                Long.class, linkId);
        if (preparedCourseId != null && preparedCourseId > 0) return;

        long courseId = courses.createCourse("REVIEW-" + linkId, TOPIC + " · 演示课",
                "通过地址拆分和组索引练习，体验从讲义发布到学生学习的过程。",
                teacherId, "class", "draft");
        courses.createKnowledgePoint(courseId, null, TOPIC, "medium", 1);
        courses.createKnowledgePoint(courseId, null, "组相联映射", "medium", 2);
        classes.addCourse(classId, courseId);

        addSampleStudent(linkId, student.id(), "示例学生 A（学生端）");
        long secondStudent = createSampleStudent(linkId, "示例学生 B");
        createSampleStudent(linkId, "示例学生 C");
        classes.addMember(classId, secondStudent, "student");

        recordSampleActivity(linkId, courseId, student.id(), 55);
        recordSampleActivity(linkId, courseId, secondStudent, 85);
        if (profiles.current(student.username()).isEmpty()) {
            profiles.save(student.username(), Map.of(
                    "student_id", student.username(),
                    "student_name", "示例学生 A",
                    "target_courses", List.of("计算机组成原理"),
                    "learning_goal", "掌握 Cache 地址拆分和组索引计算",
                    "weak_points", List.of(TOPIC),
                    "cognitive_style", List.of("例题推导"),
                    "resource_preference", List.of("讲义、分步例题")
            ), null, "评委体验预置的样例画像");
        }
        jdbc.update("UPDATE review_links SET sample_course_id = ? WHERE id = ?", courseId, linkId);
    }

    public List<SampleStudent> students(long teacherId) {
        return jdbc.query("""
                SELECT u.id, u.username, s.display_name FROM review_demo_students s
                JOIN review_links r ON r.id = s.review_link_id
                JOIN users u ON u.id = s.user_id AND u.status = 'active' AND u.role = 'student'
                WHERE r.teacher_user_id = ? AND r.revoked_at IS NULL AND r.expires_at > CURRENT_TIMESTAMP
                ORDER BY s.user_id
                """, (rs, row) -> new SampleStudent(rs.getLong("id"), rs.getString("username"),
                rs.getString("display_name")), teacherId);
    }

    private long createSampleStudent(long linkId, String name) {
        String username = "review_student_" + UUID.randomUUID().toString().replace("-", "");
        long userId = users.create(username, passwords.hash(UUID.randomUUID().toString()), "student", "active");
        addSampleStudent(linkId, userId, name);
        return userId;
    }

    private void addSampleStudent(long linkId, long userId, String name) {
        jdbc.update("INSERT INTO review_demo_students (review_link_id, user_id, display_name) VALUES (?, ?, ?)",
                linkId, userId, name);
    }

    private void recordSampleActivity(long linkId, long courseId, long userId, int score) {
        String eventId = "review_demo_" + linkId + "_" + userId;
        jdbc.update("""
                INSERT INTO learning_events (
                    event_id, idempotency_key, student_id, user_id, event_type, course_id, knowledge_point,
                    source_type, source_id, action, progress_percent, evidence_weight, evidence_score)
                SELECT ?, ?, username, id, 'resource_completed', ?, ?, 'review_demo', ?, 'completed', 100, 4, ?
                FROM users WHERE id = ?
                """, eventId, eventId, courseId, TOPIC, eventId, score, userId);
    }

    public record SampleStudent(long userId, String username, String displayName) {}
}
