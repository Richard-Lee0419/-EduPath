package com.edupath.course;

import com.edupath.auth.RoleGuard;
import com.edupath.auth.UserRepository;
import com.edupath.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/courses")
@Tag(name = "课程目录")
public class CourseController {

    private final CourseCatalogService courseCatalogService;
    private final RoleGuard roleGuard;
    private final UserRepository userRepository;

    public CourseController(
            CourseCatalogService courseCatalogService,
            RoleGuard roleGuard,
            UserRepository userRepository) {
        this.courseCatalogService = courseCatalogService;
        this.roleGuard = roleGuard;
        this.userRepository = userRepository;
    }

    @GetMapping
    @Operation(summary = "查询课程列表")
    public ApiResponse<List<CourseCatalogService.CourseDto>> listCourses() {
        return ApiResponse.success(courseCatalogService.listCourses());
    }

    @GetMapping("/{courseId}/knowledge-points")
    @Operation(summary = "查询课程知识点树")
    public ApiResponse<List<CourseCatalogService.KnowledgePointDto>> listKnowledgePoints(
            @PathVariable int courseId) {
        return ApiResponse.success(courseCatalogService.listKnowledgePoints(courseId));
    }

    @PostMapping
    @Operation(summary = "创建课程")
    public ApiResponse<CourseCatalogService.CourseDto> createCourse(
            @Valid @RequestBody CourseCatalogService.CourseCreateRequest request) {
        roleGuard.requireAdmin();
        return ApiResponse.success(courseCatalogService.createCourse(request));
    }

    @PatchMapping("/{courseId}")
    @Operation(summary = "更新课程")
    public ApiResponse<CourseCatalogService.CourseDto> updateCourse(
            @PathVariable long courseId,
            @RequestBody CourseCatalogService.CourseUpdateRequest request) {
        roleGuard.requireAdmin();
        return ApiResponse.success(courseCatalogService.updateCourse(courseId, request));
    }

    @GetMapping("/{courseId}/team-members")
    @Operation(summary = "查询课程团队成员")
    public ApiResponse<List<UserRepository.CourseRoleRecord>> listTeamMembers(@PathVariable long courseId) {
        roleGuard.requireCourseManager(courseId);
        return ApiResponse.success(userRepository.listCourseRoles(courseId));
    }

    @PostMapping("/{courseId}/team-members")
    @Operation(summary = "添加课程团队成员")
    public ApiResponse<List<UserRepository.CourseRoleRecord>> addTeamMember(
            @PathVariable long courseId,
            @Valid @RequestBody CourseTeamMemberRequest request) {
        roleGuard.requireCourseManager(courseId);
        userRepository.findById(request.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在: " + request.userId()));
        userRepository.grantCourseRole(request.userId(), courseId, request.normalizedRole());
        return ApiResponse.success(userRepository.listCourseRoles(courseId));
    }

    @DeleteMapping("/{courseId}/team-members/{userId}")
    @Operation(summary = "移除课程团队成员")
    public ApiResponse<List<UserRepository.CourseRoleRecord>> removeTeamMember(
            @PathVariable long courseId,
            @PathVariable long userId) {
        roleGuard.requireCourseManager(courseId);
        userRepository.revokeCourseRole(userId, courseId, "manager");
        return ApiResponse.success(userRepository.listCourseRoles(courseId));
    }

    @PostMapping("/{courseId}/knowledge-points")
    @Operation(summary = "创建课程知识点")
    public ApiResponse<CourseCatalogService.KnowledgePointDto> createKnowledgePoint(
            @PathVariable long courseId,
            @RequestBody CourseCatalogService.KnowledgePointCreateRequest request) {
        roleGuard.requireAdmin();
        return ApiResponse.success(courseCatalogService.createKnowledgePoint(courseId, request));
    }

    @PatchMapping("/knowledge-points/{pointId}")
    @Operation(summary = "更新课程知识点")
    public ApiResponse<CourseCatalogService.KnowledgePointDto> updateKnowledgePoint(
            @PathVariable long pointId,
            @RequestBody CourseCatalogService.KnowledgePointUpdateRequest request) {
        roleGuard.requireAdmin();
        return ApiResponse.success(courseCatalogService.updateKnowledgePoint(pointId, request));
    }

    public record CourseTeamMemberRequest(long userId, String role) {
        public String normalizedRole() {
            return role == null || role.isBlank() ? "manager" : role;
        }
    }
}
