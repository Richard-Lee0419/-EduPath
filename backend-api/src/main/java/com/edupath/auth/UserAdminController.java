package com.edupath.auth;

import com.edupath.common.ApiResponse;
import com.edupath.demo.DemoClassEnrollmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/users")
@Tag(name = "用户管理")
public class UserAdminController {

    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final RoleGuard roleGuard;
    private final DemoClassEnrollmentService demoClassEnrollmentService;

    public UserAdminController(
            UserRepository userRepository,
            PasswordService passwordService,
            RoleGuard roleGuard,
            DemoClassEnrollmentService demoClassEnrollmentService) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.roleGuard = roleGuard;
        this.demoClassEnrollmentService = demoClassEnrollmentService;
    }

    @GetMapping
    @Operation(summary = "查询用户列表")
    public ApiResponse<UserListResponse> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        roleGuard.requireAdmin();
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.max(1, Math.min(size, 100));
        List<UserDto> users = userRepository
                .list(keyword, role, status, normalizedPage, normalizedSize)
                .stream()
                .map(this::toDto)
                .toList();
        return ApiResponse.success(new UserListResponse(
                users,
                normalizedPage,
                normalizedSize,
                userRepository.count(keyword, role, status)));
    }

    @PostMapping
    @Operation(summary = "创建用户")
    public ApiResponse<UserDto> create(@Valid @RequestBody UserCreateRequest request) {
        roleGuard.requireAdmin();
        String role = normalizeRole(request.role());
        String status = request.status() == null || request.status().isBlank() ? "active" : normalizeStatus(request.status());
        if (userRepository.findByUsername(request.username()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名已存在");
        }
        long id = userRepository.create(
                request.username().trim(),
                passwordService.hash(request.password()),
                role,
                status);
        if ("student".equals(role) && "active".equals(status)) {
            demoClassEnrollmentService.enrollStudent(id);
        }
        return ApiResponse.success(toDto(userRepository.findById(id).orElseThrow()));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "更新用户状态")
    public ApiResponse<UserDto> updateStatus(
            @PathVariable long id,
            @Valid @RequestBody UserStatusUpdateRequest request) {
        roleGuard.requireAdmin();
        int updated = userRepository.updateStatus(id, normalizeStatus(request.status()));
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在: " + id);
        }
        return ApiResponse.success(toDto(userRepository.findById(id).orElseThrow()));
    }

    @PatchMapping("/{id}/role")
    @Operation(summary = "更新用户角色")
    public ApiResponse<UserDto> updateRole(
            @PathVariable long id,
            @Valid @RequestBody UserRoleUpdateRequest request) {
        roleGuard.requireAdmin();
        int updated = userRepository.updateRole(id, normalizeRole(request.role()));
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在: " + id);
        }
        return ApiResponse.success(toDto(userRepository.findById(id).orElseThrow()));
    }

    @PatchMapping("/{id}/password")
    @Operation(summary = "重置用户密码")
    public ApiResponse<UserDto> resetPassword(
            @PathVariable long id,
            @Valid @RequestBody UserPasswordUpdateRequest request) {
        roleGuard.requireAdmin();
        int updated = userRepository.updatePassword(id, passwordService.hash(request.password()));
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在: " + id);
        }
        return ApiResponse.success(toDto(userRepository.findById(id).orElseThrow()));
    }

    @PostMapping("/{id}/course-roles")
    @Operation(summary = "授予用户课程管理权限")
    public ApiResponse<UserDto> grantCourseRole(
            @PathVariable long id,
            @Valid @RequestBody CourseRoleGrantRequest request) {
        roleGuard.requireAdmin();
        userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在: " + id));
        userRepository.grantCourseManager(id, request.courseId());
        return ApiResponse.success(toDto(userRepository.findById(id).orElseThrow()));
    }

    private String normalizeRole(String role) {
        if (!List.of("student", "teacher", "admin").contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色不合法");
        }
        return role;
    }

    private String normalizeStatus(String status) {
        if (!List.of("active", "disabled").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户状态不合法");
        }
        return status;
    }

    private UserDto toDto(UserRepository.UserRecord user) {
        return new UserDto(
                user.id(),
                user.username(),
                user.role(),
                user.status(),
                user.createdAt(),
                user.lastLoginAt());
    }

    public record UserCreateRequest(
            @NotBlank String username,
            @NotBlank String password,
            @NotBlank String role,
            String status) {}

    public record UserStatusUpdateRequest(@NotBlank String status) {}

    public record UserRoleUpdateRequest(@NotBlank String role) {}

    public record UserPasswordUpdateRequest(@NotBlank String password) {}

    public record CourseRoleGrantRequest(long courseId) {}

    public record UserDto(
            long id,
            String username,
            String role,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime lastLoginAt) {}

    public record UserListResponse(List<UserDto> items, int page, int size, long total) {}
}
