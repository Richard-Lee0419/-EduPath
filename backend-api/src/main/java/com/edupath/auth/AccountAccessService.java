package com.edupath.auth;

import com.edupath.course.ClassRepository;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountAccessService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final PasswordService passwords;
    private final ClassRepository classes;
    private final boolean reviewEnabled;
    private final SecureRandom random = new SecureRandom();

    public AccountAccessService(
            JdbcTemplate jdbc,
            UserRepository users,
            PasswordService passwords,
            ClassRepository classes,
            @Value("${edupath.review-access.enabled:false}") boolean reviewEnabled) {
        this.jdbc = jdbc;
        this.users = users;
        this.passwords = passwords;
        this.classes = classes;
        this.reviewEnabled = reviewEnabled;
    }

    public IssuedInvite issueTeacherInvite(long adminId, String email, int hours) {
        String code = newCode("ti_");
        Instant expiry = expiry(hours);
        String boundEmail = email == null || email.isBlank() ? null : email.strip().toLowerCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO teacher_invites (code_hash, email, created_by, expires_at)
                VALUES (?, ?, ?, ?)
                """, TokenRepository.hash(code), boundEmail, adminId, Timestamp.from(expiry));
        return new IssuedInvite(code, expiry);
    }

    @Transactional
    public long registerTeacher(String username, String email, String passwordHash, String code) {
        if (!code.matches("ti_[A-Za-z0-9_-]{43}")) {
            throw invalidInvite();
        }
        int claimed = jdbc.update("""
                UPDATE teacher_invites SET consumed_at = CURRENT_TIMESTAMP
                WHERE code_hash = ? AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP
                  AND (email IS NULL OR email = ?)
                """, TokenRepository.hash(code), email);
        if (claimed == 0) {
            throw invalidInvite();
        }
        return users.create(username, email, passwordHash, "teacher", "active");
    }

    @Transactional
    public IssuedReviewLink issueReviewLink(long adminId, int hours) {
        requireReviewEnabled();
        Instant expiry = expiry(hours);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String className = "评委演示班 " + suffix.substring(0, 6).toUpperCase(Locale.ROOT);
        long teacherId = users.create("review_teacher_" + suffix, passwords.hash(newCode("")), "teacher", "active");
        long studentId = users.create("review_student_" + suffix, passwords.hash(newCode("")), "student", "active");
        long classId = classes.create(className, "用于课程 PDF 发布和学生学习体验。", teacherId);
        classes.addMember(classId, studentId, "student");

        String code = newCode("rv_");
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO review_links (code_hash, teacher_user_id, student_user_id, class_id, created_by, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            statement.setString(1, TokenRepository.hash(code));
            statement.setLong(2, teacherId);
            statement.setLong(3, studentId);
            statement.setLong(4, classId);
            statement.setLong(5, adminId);
            statement.setTimestamp(6, Timestamp.from(expiry));
            return statement;
        }, key);
        return new IssuedReviewLink(key.getKey().longValue(), classId, className, code, expiry);
    }

    public ReviewLinks listReviewLinks() {
        if (!reviewEnabled) {
            return new ReviewLinks(false, List.of());
        }
        List<ReviewLink> links = jdbc.query("""
                SELECT rl.id, rl.class_id, c.name AS class_name, rl.expires_at,
                       CASE WHEN rl.revoked_at IS NULL AND rl.expires_at > CURRENT_TIMESTAMP THEN TRUE ELSE FALSE END AS active
                FROM review_links rl JOIN classes c ON c.id = rl.class_id
                ORDER BY rl.id DESC LIMIT 25
                """, (rs, row) -> new ReviewLink(rs.getLong("id"), rs.getLong("class_id"), rs.getString("class_name"),
                rs.getTimestamp("expires_at").toInstant(), rs.getBoolean("active")));
        return new ReviewLinks(true, links);
    }

    public void revokeReviewLink(long id) {
        requireReviewEnabled();
        jdbc.update("UPDATE review_links SET revoked_at = CURRENT_TIMESTAMP WHERE id = ? AND revoked_at IS NULL", id);
    }

    public ReviewLogin authenticateReviewer(String code, String role) {
        requireReviewEnabled();
        if (!"teacher".equals(role) && !"student".equals(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择教师或学生体验");
        }
        if (!code.matches("rv_[A-Za-z0-9_-]{43}")) {
            throw invalidReview();
        }
        ReviewAccount account = jdbc.query("""
                SELECT teacher_user_id, student_user_id, expires_at
                FROM review_links
                WHERE code_hash = ? AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP
                """, (rs, row) -> new ReviewAccount(rs.getLong("teacher_user_id"), rs.getLong("student_user_id"),
                rs.getTimestamp("expires_at").toInstant()), TokenRepository.hash(code))
                .stream().findFirst().orElseThrow(this::invalidReview);
        long userId = "teacher".equals(role) ? account.teacherId() : account.studentId();
        var user = users.findById(userId)
                .filter(candidate -> "active".equals(candidate.status()) && role.equals(candidate.role()))
                .orElseThrow(this::invalidReview);
        return new ReviewLogin(user, account.expiresAt());
    }

    public boolean isReviewUser(long userId) {
        return hasReviewLink(userId, false);
    }

    public boolean isReviewSessionActive(long userId) {
        return reviewEnabled && hasReviewLink(userId, true);
    }

    public boolean isPairedReviewMember(long teacherId, long classId, long studentId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM review_links
                WHERE teacher_user_id = ? AND class_id = ? AND student_user_id = ?
                  AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP
                """, Long.class, teacherId, classId, studentId);
        return count != null && count > 0;
    }

    private boolean hasReviewLink(long userId, boolean activeOnly) {
        String conditions = activeOnly ? " AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP" : "";
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM review_links WHERE (teacher_user_id = ? OR student_user_id = ?)
                """ + conditions, Long.class, userId, userId);
        return count != null && count > 0;
    }

    private Instant expiry(int hours) {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(hours, ChronoUnit.HOURS);
    }

    private String newCode(String prefix) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void requireReviewEnabled() {
        if (!reviewEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "评委入口未开放");
        }
    }

    private ResponseStatusException invalidInvite() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "教师邀请码无效、已使用、已过期或与邮箱不匹配");
    }

    private ResponseStatusException invalidReview() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "评委链接无效或已过期，请联系参赛团队获取新链接");
    }

    public record IssuedInvite(String code, Instant expiresAt) {}
    public record IssuedReviewLink(long id, long classId, String className, String code, Instant expiresAt) {}
    public record ReviewLink(long id, long classId, String className, Instant expiresAt, boolean active) {}
    public record ReviewLinks(boolean enabled, List<ReviewLink> links) {}
    public record ReviewLogin(UserRepository.UserRecord user, Instant expiresAt) {}
    private record ReviewAccount(long teacherId, long studentId, Instant expiresAt) {}
}
