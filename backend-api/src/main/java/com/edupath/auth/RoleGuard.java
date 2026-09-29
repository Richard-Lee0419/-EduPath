package com.edupath.auth;

import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RoleGuard {

    private final UserRepository userRepository;

    public RoleGuard(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public void requireAny(String... roles) {
        JwtService.AuthPrincipal principal = AuthContext.current();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        boolean allowed = Arrays.stream(roles).anyMatch(role -> role.equals(principal.role()));
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色无权执行该操作");
        }
    }

    public void requireAdmin() {
        requireAny("admin");
    }

    public void requireCourseManager(long courseId) {
        JwtService.AuthPrincipal principal = AuthContext.requirePrincipal();
        if (canManageCourse(principal, courseId)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色无权管理该课程");
    }

    public void requireCourseManagerOrOwner(long courseId, long ownerUserId) {
        JwtService.AuthPrincipal principal = AuthContext.requirePrincipal();
        if (canManageCourse(principal, courseId) || (ownerUserId > 0 && principal.userId() == ownerUserId)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色既不是课程管理员，也不是资源 owner");
    }

    private boolean canManageCourse(JwtService.AuthPrincipal principal, long courseId) {
        if ("admin".equals(principal.role())) {
            return true;
        }
        return "teacher".equals(principal.role()) && userRepository.hasCourseRole(principal.userId(), courseId);
    }
}
