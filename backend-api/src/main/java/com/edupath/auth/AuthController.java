package com.edupath.auth;

import com.edupath.common.ApiResponse;
import com.edupath.demo.DemoClassEnrollmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "认证")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final JwtService jwtService;
    private final TokenRepository tokenRepository;
    private final EmailService emailService;
    private final DemoClassEnrollmentService demoClassEnrollmentService;
    private final long refreshTtlSeconds;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthController(
            UserRepository userRepository,
            PasswordService passwordService,
            JwtService jwtService,
            TokenRepository tokenRepository,
            EmailService emailService,
            DemoClassEnrollmentService demoClassEnrollmentService,
            @Value("${edupath.jwt.refresh-ttl-seconds:604800}") long refreshTtlSeconds) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.jwtService = jwtService;
        this.tokenRepository = tokenRepository;
        this.emailService = emailService;
        this.demoClassEnrollmentService = demoClassEnrollmentService;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    @PostMapping("/login")
    @Operation(summary = "用户登录并返回 JWT")
    public ApiResponse<LoginResult> login(@Valid @RequestBody LoginRequest request) {
        String loginName = request.username().trim();
        UserRepository.UserRecord user = userRepository
                .findByUsernameOrEmail(loginName)
                .filter(candidate -> "active".equals(candidate.status()))
                .filter(candidate -> passwordService.verify(request.password(), candidate.passwordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名、邮箱或密码错误"));
        userRepository.updateLastLogin(user.id());
        return ApiResponse.success(issueLoginResult(user));
    }

    @PostMapping("/register")
    @Operation(summary = "公开注册学生账号并返回 JWT")
    public ApiResponse<LoginResult> register(@Valid @RequestBody RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮箱已注册");
        }
        String username = uniqueUsernameForEmail(email);
        long id = userRepository.create(username, email, passwordService.hash(request.password()), "student", "active");
        demoClassEnrollmentService.enrollStudent(id);
        UserRepository.UserRecord user = userRepository.findById(id).orElseThrow();
        EmailService.EmailDelivery emailDelivery = emailService.sendRegistrationEmail(user.email(), user.username());
        if (emailDelivery.sent()) {
            userRepository.markEmailVerificationSent(user.id());
        }
        return ApiResponse.success(issueLoginResult(user, emailDelivery));
    }

    @PostMapping("/logout")
    @Operation(summary = "退出登录")
    public ApiResponse<Void> logout() {
        JwtService.AuthPrincipal principal = AuthContext.requirePrincipal();
        tokenRepository.revokeAccessToken(principal);
        tokenRepository.revokeAllRefreshTokens(principal.userId());
        return ApiResponse.success(null);
    }

    @PostMapping("/refresh")
    @Operation(summary = "使用 refresh token 换取新的登录令牌")
    public ApiResponse<LoginResult> refresh(@Valid @RequestBody RefreshRequest request) {
        TokenRepository.RefreshTokenRecord refreshToken = tokenRepository
                .findActiveRefreshToken(request.refreshToken())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录状态已失效，请重新登录"));
        UserRepository.UserRecord user = userRepository
                .findById(refreshToken.userId())
                .filter(candidate -> "active".equals(candidate.status()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不可用"));
        tokenRepository.revokeRefreshToken(refreshToken.id());
        return ApiResponse.success(issueLoginResult(user));
    }

    @PostMapping("/verify")
    @Operation(summary = "校验当前登录态")
    public ApiResponse<UserDto> verify() {
        JwtService.AuthPrincipal principal = AuthContext.current();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名、邮箱或密码错误");
        }
        UserRepository.UserRecord user = userRepository
                .findById(principal.userId())
                .filter(candidate -> "active".equals(candidate.status()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不可用"));
        return ApiResponse.success(toDto(user));
    }

    private LoginResult issueLoginResult(UserRepository.UserRecord user) {
        return issueLoginResult(user, null);
    }

    private LoginResult issueLoginResult(UserRepository.UserRecord user, EmailService.EmailDelivery emailDelivery) {
        String accessToken = jwtService.issue(user);
        String refreshToken = newRefreshToken();
        Instant refreshExpiresAt = Instant.now().plusSeconds(refreshTtlSeconds);
        tokenRepository.saveRefreshToken(refreshToken, user.id(), refreshExpiresAt);
        return new LoginResult(
                accessToken,
                refreshToken,
                OffsetDateTime.ofInstant(refreshExpiresAt, ZoneOffset.ofHours(8)),
                toDto(user),
                emailDelivery);
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private UserDto toDto(UserRepository.UserRecord user) {
        return new UserDto(user.id(), user.username(), user.role(), user.email());
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String uniqueUsernameForEmail(String email) {
        int atIndex = email.indexOf('@');
        String localPart = (atIndex > 0 ? email.substring(0, atIndex) : email).replaceAll("[^A-Za-z0-9._-]", "_");
        if (localPart.isBlank()) {
            localPart = "student";
        }
        String base = localPart.length() > 48 ? localPart.substring(0, 48) : localPart;
        String candidate = base;
        int suffix = 1;
        while (userRepository.findByUsername(candidate).isPresent()) {
            String tail = "_" + suffix++;
            int maxBaseLength = Math.max(1, 64 - tail.length());
            candidate = base.substring(0, Math.min(base.length(), maxBaseLength)) + tail;
        }
        return candidate;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record RegisterRequest(@NotBlank @Email String email, @NotBlank String password, String name) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record LoginResult(
            String token,
            String refreshToken,
            OffsetDateTime expiresAt,
            UserDto user,
            EmailService.EmailDelivery emailDelivery) {}

    public record UserDto(long id, String username, String role, String email) {}
}
