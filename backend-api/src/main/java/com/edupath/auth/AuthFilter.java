package com.edupath.auth;

import com.edupath.common.ApiResponse;
import com.edupath.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(10)
public class AuthFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/actuator", "/api-docs", "/swagger-ui", "/v3/api-docs", "/swagger-ui.html");

    private final JwtService jwtService;
    private final TokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public AuthFilter(
            JwtService jwtService,
            TokenRepository tokenRepository,
            UserRepository userRepository,
            ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            if (isPublic(request) && !hasAuthentication(request)) {
                filterChain.doFilter(request, response);
                return;
            }
            String token = resolveToken(request);
            JwtService.AuthPrincipal principal = jwtService.verify(token);
            if (tokenRepository.isAccessTokenRevoked(principal.jti())) {
                throw new IllegalArgumentException("登录状态已失效，请重新登录");
            }
            userRepository
                    .findById(principal.userId())
                    .filter(user -> "active".equals(user.status()))
                    .orElseThrow(() -> new IllegalArgumentException("用户不可用"));
            AuthContext.set(principal);
            request.setAttribute("edupath.principal", principal);
            filterChain.doFilter(request, response);
        } catch (IllegalArgumentException exception) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter()
                    .write(objectMapper.writeValueAsString(
                            ApiResponse.error(ErrorCode.UNAUTHORIZED.code(), exception.getMessage())));
        } finally {
            AuthContext.clear();
        }
    }

    private boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        if (PUBLIC_PREFIXES.stream().anyMatch(path::startsWith)) {
            return true;
        }
        if ("/api/auth/login".equals(path)
                || "/api/auth/register".equals(path)
                || "/api/auth/refresh".equals(path)
                || "/api/system/health".equals(path)) {
            return true;
        }
        if ("GET".equalsIgnoreCase(method) && path.equals("/api/storage/signed")) {
            return true;
        }
        if ("GET".equalsIgnoreCase(method)
                && (path.equals("/api/courses")
                        || path.matches("/api/courses/\\d+/knowledge-points")
                        || path.equals("/api/resources")
                        || path.matches("/api/resources/[^/]+"))) {
            return true;
        }
        return !path.startsWith("/api/");
    }

    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length()).trim();
        }
        String queryToken = request.getParameter("access_token");
        if (queryToken != null && !queryToken.isBlank()) {
            return queryToken.trim();
        }
        throw new IllegalArgumentException("请先登录后再继续");
    }

    private boolean hasAuthentication(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return (authorization != null && authorization.startsWith("Bearer "))
                || (request.getParameter("access_token") != null
                        && !request.getParameter("access_token").isBlank());
    }
}
