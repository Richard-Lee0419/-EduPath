package com.edupath.course;

import com.edupath.auth.AuthContext;
import com.edupath.auth.AccountAccessService;
import com.edupath.auth.RoleGuard;
import com.edupath.auth.UserRepository;
import com.edupath.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/classes")
@Tag(name = "班级管理")
public class ClassController {

    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final RoleGuard roleGuard;
    private final CourseAccess courseAccess;
    private final ClassInsightService classInsightService;
    private final AccountAccessService accountAccess;

    public ClassController(
            ClassRepository classRepository,
            UserRepository userRepository,
            RoleGuard roleGuard,
            CourseAccess courseAccess,
            ClassInsightService classInsightService,
            AccountAccessService accountAccess) {
        this.classRepository = classRepository;
        this.userRepository = userRepository;
        this.roleGuard = roleGuard;
        this.courseAccess = courseAccess;
        this.classInsightService = classInsightService;
        this.accountAccess = accountAccess;
    }

    @GetMapping
    @Operation(summary = "查询班级列表")
    public ApiResponse<List<ClassRepository.ClassRow>> list() {
        roleGuard.requireAny("teacher", "admin");
        var principal = AuthContext.requirePrincipal();
        return ApiResponse.success("admin".equals(principal.role())
                ? classRepository.list()
                : classRepository.listOwnedBy(principal.userId()));
    }

    @PostMapping
    @Transactional
    @Operation(summary = "创建班级")
    public ApiResponse<ClassDetail> create(@Valid @RequestBody ClassCreateRequest request) {
        roleGuard.requireAny("teacher", "admin");
        if (request.courseId() != null) {
            requireLegacyCourseBinding(request.courseId());
            roleGuard.requireCourseManager(request.courseId());
        }
        long ownerUserId = AuthContext.current() == null ? 0 : AuthContext.current().userId();
        long classId = classRepository.create(request.name().trim(), request.description(), ownerUserId);
        if (request.courseId() != null) {
            classRepository.addCourse(classId, request.courseId());
        }
        return ApiResponse.success(detail(classId));
    }

    @GetMapping("/{classId}")
    @Operation(summary = "查询班级详情")
    public ApiResponse<ClassDetail> detailEndpoint(@PathVariable long classId) {
        roleGuard.requireAny("teacher", "admin");
        requireClassOwnerOrAdmin(assertClassExists(classId));
        return ApiResponse.success(detail(classId));
    }

    @PostMapping("/{classId}/courses")
    @Operation(summary = "绑定班级课程")
    public ApiResponse<ClassDetail> addCourse(
            @PathVariable long classId,
            @Valid @RequestBody ClassCourseRequest request) {
        requireClassOwnerOrAdmin(assertClassExists(classId));
        requireLegacyCourseBinding(request.courseId());
        roleGuard.requireCourseManager(request.courseId());
        classRepository.addCourse(classId, request.courseId());
        return ApiResponse.success(detail(classId));
    }

    @PostMapping("/{classId}/members")
    @Operation(summary = "添加班级成员")
    public ApiResponse<List<ClassRepository.ClassMemberRow>> addMember(
            @PathVariable long classId,
            @Valid @RequestBody ClassMemberRequest request) {
        requireClassOwnerOrAdmin(assertClassExists(classId));
        roleGuard.requireAny("teacher", "admin");
        String role = request.normalizedRole();
        var target = request.userId() > 0 ? userRepository.findById(request.userId()) :
                userRepository.findByUsername(request.username() == null ? "" : request.username().strip())
                        .filter(user -> "active".equals(user.status()) && "student".equals(user.role()) && "student".equals(role));
        long userId = target.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到对应的班级成员账号")).id();
        var actor = AuthContext.requirePrincipal();
        if (actor.review() && (!"student".equals(role) || !accountAccess.isPairedReviewMember(actor.userId(), classId, userId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "评委演示班仅包含当前链接分配的学生账号");
        }
        classRepository.addMember(classId, userId, role);
        return ApiResponse.success(classRepository.members(classId));
    }

    @GetMapping("/{classId}/members")
    @Operation(summary = "查询班级成员")
    public ApiResponse<List<ClassRepository.ClassMemberRow>> members(@PathVariable long classId) {
        requireClassOwnerOrAdmin(assertClassExists(classId));
        roleGuard.requireAny("teacher", "admin");
        return ApiResponse.success(classRepository.members(classId));
    }

    @GetMapping("/{classId}/insights")
    @Operation(summary = "查询教师班级聚合学习洞察")
    public ApiResponse<ClassInsightService.ClassInsightResponse> insights(
            @PathVariable long classId,
            @org.springframework.web.bind.annotation.RequestParam(name = "course_id", required = false) Long courseId,
            @org.springframework.web.bind.annotation.RequestParam(name = "window_days", defaultValue = "30") int windowDays) {
        roleGuard.requireAny("teacher", "admin");
        return ApiResponse.success(classInsightService.insights(classId, courseId, windowDays));
    }

    private ClassDetail detail(long classId) {
        ClassRepository.ClassRow row = assertClassExists(classId);
        return new ClassDetail(row, classRepository.courses(classId), classRepository.members(classId));
    }

    private ClassRepository.ClassRow assertClassExists(long classId) {
        ClassRepository.ClassRow row = classRepository.find(classId);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "班级不存在: " + classId);
        }
        return row;
    }

    private void requireClassOwnerOrAdmin(ClassRepository.ClassRow row) {
        var principal = AuthContext.requirePrincipal();
        if ("admin".equals(principal.role()) || row.ownerUserId() == principal.userId()) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能管理自己负责的班级");
    }

    private void requireLegacyCourseBinding(long courseId) {
        if (courseAccess.isClassCourse(courseId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请通过教师课程工作台选择班级并发布课程");
        }
    }

    public record ClassCreateRequest(@NotBlank @Size(max = 255) String name, String description, Long courseId) {}

    public record ClassCourseRequest(long courseId) {}

    public record ClassMemberRequest(long userId, String role, String username) {
        public String normalizedRole() {
            String normalized = role == null || role.isBlank() ? "student" : role.trim();
            if (!java.util.Set.of("student", "assistant").contains(normalized)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "班级成员角色只能是 student 或 assistant");
            }
            return normalized;
        }
    }

    public record ClassDetail(
            ClassRepository.ClassRow classInfo,
            List<ClassRepository.ClassCourseRow> courses,
            List<ClassRepository.ClassMemberRow> members) {}
}
