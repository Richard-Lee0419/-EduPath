package com.edupath.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final String USER_COLUMNS =
            "id, username, email, email_verified, email_verification_sent_at, password_hash, role, status, created_at, last_login_at";

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserRecord> findByUsername(String username) {
        return jdbcTemplate
                .query(
                        "SELECT " + USER_COLUMNS + " FROM users WHERE username = ?",
                        this::mapUser,
                        username)
                .stream()
                .findFirst();
    }

    public Optional<UserRecord> findByEmail(String email) {
        return jdbcTemplate
                .query(
                        "SELECT " + USER_COLUMNS + " FROM users WHERE email = ?",
                        this::mapUser,
                        email)
                .stream()
                .findFirst();
    }

    public Optional<UserRecord> findByUsernameOrEmail(String usernameOrEmail) {
        return jdbcTemplate
                .query(
                        "SELECT " + USER_COLUMNS + " FROM users WHERE username = ? OR email = ?",
                        this::mapUser,
                        usernameOrEmail,
                        usernameOrEmail)
                .stream()
                .findFirst();
    }

    public Optional<UserRecord> findById(long id) {
        return jdbcTemplate
                .query(
                        "SELECT " + USER_COLUMNS + " FROM users WHERE id = ?",
                        this::mapUser,
                        id)
                .stream()
                .findFirst();
    }

    public void updateLastLogin(long id) {
        jdbcTemplate.update("UPDATE users SET last_login_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    public long create(String username, String passwordHash, String role, String status) {
        return create(username, null, passwordHash, role, status);
    }

    public long create(String username, String email, String passwordHash, String role, String status) {
        jdbcTemplate.update(
                """
                INSERT INTO users (username, email, password_hash, role, status, created_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                username,
                email,
                passwordHash,
                role,
                status);
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM users", Long.class);
        return id == null ? 0 : id;
    }

    public List<UserRecord> list(String keyword, String role, String status, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT " + USER_COLUMNS + " FROM users WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (username LIKE ? OR email LIKE ?)");
            String pattern = "%" + keyword.trim() + "%";
            args.add(pattern);
            args.add(pattern);
        }
        if (role != null && !role.isBlank()) {
            sql.append(" AND role = ?");
            args.add(role.trim());
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim());
        }
        sql.append(" ORDER BY id ASC LIMIT ? OFFSET ?");
        args.add(size);
        args.add((page - 1) * size);
        return jdbcTemplate.query(sql.toString(), this::mapUser, args.toArray());
    }

    public long count(String keyword, String role, String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM users WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (username LIKE ? OR email LIKE ?)");
            String pattern = "%" + keyword.trim() + "%";
            args.add(pattern);
            args.add(pattern);
        }
        if (role != null && !role.isBlank()) {
            sql.append(" AND role = ?");
            args.add(role.trim());
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim());
        }
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public int updateStatus(long id, String status) {
        return jdbcTemplate.update("UPDATE users SET status = ? WHERE id = ?", status, id);
    }

    public int updateRole(long id, String role) {
        return jdbcTemplate.update("UPDATE users SET role = ? WHERE id = ?", role, id);
    }

    public int updatePassword(long id, String passwordHash) {
        return jdbcTemplate.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordHash, id);
    }

    public int markEmailVerificationSent(long id) {
        return jdbcTemplate.update("UPDATE users SET email_verification_sent_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    public boolean hasCourseRole(long userId, long courseId) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM user_course_roles
                WHERE user_id = ? AND course_id = ?
                """,
                Long.class,
                userId,
                courseId);
        return count != null && count > 0;
    }

    public List<CourseRoleRecord> listCourseRoles(long courseId) {
        return jdbcTemplate.query(
                """
                SELECT ucr.user_id, u.username, u.role AS user_role, ucr.course_id, ucr.role AS course_role, ucr.created_at
                FROM user_course_roles ucr
                JOIN users u ON u.id = ucr.user_id
                WHERE ucr.course_id = ?
                ORDER BY ucr.created_at ASC, ucr.user_id ASC
                """,
                (rs, rowNum) -> new CourseRoleRecord(
                        rs.getLong("user_id"),
                        rs.getString("username"),
                        rs.getString("user_role"),
                        rs.getLong("course_id"),
                        rs.getString("course_role"),
                        toOffsetDateTime(rs.getTimestamp("created_at"))),
                courseId);
    }

    public void grantCourseRole(long userId, long courseId, String role) {
        String normalizedRole = role == null || role.isBlank() ? "manager" : role.trim();
        Long existing = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM user_course_roles
                WHERE user_id = ? AND course_id = ? AND role = ?
                """,
                Long.class,
                userId,
                courseId,
                normalizedRole);
        if (existing != null && existing > 0) {
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO user_course_roles (user_id, course_id, role, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """,
                userId,
                courseId,
                normalizedRole);
    }

    public void grantCourseManager(long userId, long courseId) {
        grantCourseRole(userId, courseId, "manager");
    }

    public int revokeCourseRole(long userId, long courseId, String role) {
        return jdbcTemplate.update(
                """
                DELETE FROM user_course_roles
                WHERE user_id = ? AND course_id = ? AND role = ?
                """,
                userId,
                courseId,
                role == null || role.isBlank() ? "manager" : role.trim());
    }

    private UserRecord mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new UserRecord(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("email"),
                rs.getBoolean("email_verified"),
                toOffsetDateTime(rs.getTimestamp("email_verification_sent_at")),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getString("status"),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("last_login_at")));
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.ofHours(8));
    }

    public record UserRecord(
            long id,
            String username,
            String email,
            boolean emailVerified,
            OffsetDateTime emailVerificationSentAt,
            String passwordHash,
            String role,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime lastLoginAt) {}

    public record CourseRoleRecord(
            long userId,
            String username,
            String userRole,
            long courseId,
            String courseRole,
            OffsetDateTime createdAt) {}
}
