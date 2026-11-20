package com.edupath.course;

import com.edupath.auth.AuthContext;
import com.edupath.auth.JwtService.AuthPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CourseAccess {

    private final JdbcTemplate jdbc;

    public CourseAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isClassCourse(long courseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM courses WHERE id = ? AND visibility = 'class')", Boolean.class, courseId));
    }

    public void requireReadable(long courseId) {
        if (!isReadable(courseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "课程不存在或当前账号无权访问");
        }
    }

    public boolean isReadable(long courseId) {
        return isReadable(courseId, AuthContext.current());
    }

    public boolean isReadable(long courseId, AuthPrincipal principal) {
        if (isAdmin(principal)) {
            return true;
        }
        var scope = unreadableCourse("?", principal);
        var args = new ArrayList<Object>();
        args.add(courseId);
        args.addAll(scope.arguments());
        return !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM courses hidden_course WHERE " + scope.sql() + ")",
                Boolean.class, args.toArray()));
    }

    public boolean managesClassCourse(long courseId, long teacherId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM courses WHERE id = ? AND visibility = 'class'
                    AND status <> 'deleted' AND (created_by = ? OR EXISTS (
                        SELECT 1 FROM user_course_roles ucr WHERE ucr.course_id = courses.id
                        AND ucr.user_id = ? AND ucr.role = 'manager'
                    )))
                """, Boolean.class, courseId, teacherId, teacherId));
    }

    public boolean canManage(long courseId) {
        return canManage(courseId, AuthContext.current());
    }

    public boolean canManage(long courseId, AuthPrincipal principal) {
        if (isAdmin(principal)) {
            return true;
        }
        if (principal == null || !"teacher".equals(principal.role())) {
            return false;
        }
        if (isClassCourse(courseId)) {
            return managesClassCourse(courseId, principal.userId());
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM user_course_roles WHERE user_id = ? AND course_id = ?)
                """, Boolean.class, principal.userId(), courseId));
    }

    public static Optional<Scope> readableCourses(String qualifiedCourseId, Long userId, String userRole) {
        if ("admin".equals(userRole)) {
            return Optional.empty();
        }
        var scope = unreadableCourse(qualifiedCourseId, userId, userRole);
        return Optional.of(new Scope("NOT EXISTS (SELECT 1 FROM courses hidden_course WHERE " + scope.sql() + ")",
                scope.arguments()));
    }

    public static Scope manageableCourses(String qualifiedCourseId, long teacherId, boolean allowPublicWithoutRole) {
        String publicCourse = "NOT EXISTS (SELECT 1 FROM courses c WHERE c.visibility = 'class' AND c.id = "
                + qualifiedCourseId + ")";
        String publicRole = "EXISTS (SELECT 1 FROM user_course_roles ucr WHERE ucr.course_id = "
                + qualifiedCourseId + " AND ucr.user_id = ?)";
        String classManager = """
                EXISTS (SELECT 1 FROM courses c WHERE c.visibility = 'class' AND c.status <> 'deleted' AND c.id =
                """ + qualifiedCourseId + """
                 AND (c.created_by = ? OR EXISTS (
                     SELECT 1 FROM user_course_roles ucr WHERE ucr.course_id = c.id AND ucr.user_id = ?
                     AND ucr.role = 'manager'
                 )))
                """;
        if (allowPublicWithoutRole) {
            return new Scope("(" + publicCourse + " OR " + classManager + ")", List.of(teacherId, teacherId));
        }
        return new Scope("((" + publicCourse + " AND " + publicRole + ") OR " + classManager + ")",
                List.of(teacherId, teacherId, teacherId));
    }

    public static Scope unreadableCourse(String qualifiedCourseId, AuthPrincipal principal) {
        return unreadableCourse(qualifiedCourseId, principal == null ? null : principal.userId(),
                principal == null ? null : principal.role());
    }

    private static Scope unreadableCourse(String qualifiedCourseId, Long userId, String userRole) {
        String privateCourse = "hidden_course.id = " + qualifiedCourseId + " AND hidden_course.visibility = 'class'";
        if ("admin".equals(userRole)) {
            return new Scope("1 = 0", List.of());
        }
        if (userId == null) {
            return new Scope(privateCourse, List.of());
        }
        String activeMembership = """
                hidden_course.status = 'active' AND EXISTS (
                    SELECT 1 FROM class_courses cc
                    JOIN classes cl ON cl.id = cc.class_id AND cl.status = 'active'
                    JOIN class_members cm ON cm.class_id = cl.id AND cm.status = 'active'
                    WHERE cc.course_id = hidden_course.id AND cc.status = 'active' AND cm.user_id = ?
                )
                """;
        if (!"teacher".equals(userRole)) {
            return new Scope(privateCourse + " AND NOT (" + activeMembership + ")", List.of(userId));
        }
        String manages = """
                hidden_course.status <> 'deleted' AND (COALESCE(hidden_course.created_by, 0) = ? OR EXISTS (
                    SELECT 1 FROM user_course_roles ucr WHERE ucr.course_id = hidden_course.id
                    AND ucr.user_id = ? AND ucr.role = 'manager'
                ))
                """;
        return new Scope(privateCourse + " AND NOT ((" + manages + ") OR (" + activeMembership + "))",
                List.of(userId, userId, userId));
    }

    private static boolean isAdmin(AuthPrincipal principal) {
        return principal != null && "admin".equals(principal.role());
    }

    public record Scope(String sql, List<Object> arguments) {}
}
