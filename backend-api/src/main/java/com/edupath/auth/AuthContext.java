package com.edupath.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class AuthContext {

    private static final ThreadLocal<JwtService.AuthPrincipal> CURRENT = new ThreadLocal<>();

    private AuthContext() {}

    public static void set(JwtService.AuthPrincipal principal) {
        CURRENT.set(principal);
    }

    public static JwtService.AuthPrincipal current() {
        return CURRENT.get();
    }

    public static JwtService.AuthPrincipal requirePrincipal() {
        JwtService.AuthPrincipal principal = current();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return principal;
    }

    public static String currentUsername() {
        JwtService.AuthPrincipal principal = current();
        return principal == null ? null : principal.username();
    }

    public static String currentStudentId() {
        return requirePrincipal().username();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
