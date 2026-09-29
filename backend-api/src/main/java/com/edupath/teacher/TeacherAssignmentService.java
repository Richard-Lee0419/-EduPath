package com.edupath.teacher;

import com.edupath.auth.AuthContext;
import com.edupath.auth.RoleGuard;
import com.edupath.course.ClassRepository;
import com.edupath.resource.ResourceService;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TeacherAssignmentService {

    private final TeacherAssignmentRepository repository;
    private final ClassRepository classRepository;
    private final RoleGuard roleGuard;
    private final ResourceService resourceService;

    public TeacherAssignmentService(TeacherAssignmentRepository repository, ClassRepository classRepository,
            RoleGuard roleGuard, ResourceService resourceService) {
        this.repository = repository;
        this.classRepository = classRepository;
        this.roleGuard = roleGuard;
        this.resourceService = resourceService;
    }

    @Transactional
    public TeacherAssignmentRepository.AssignmentRow create(long classId, CreateRequest request) {
        ClassRepository.ClassRow classInfo = requireClassOwner(classId);
        ClassRepository.ClassCourseRow course = requireClassCourse(classId, request.courseId());
        roleGuard.requireCourseManager(course.courseId());
        if (request.resourceId() != null && !request.resourceId().isBlank()) {
            ResourceService.ResourceAccess access = resourceService.access(request.resourceId().trim());
            if (access.courseId() != course.courseId()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "资源不属于所选课程");
            }
        }
        String title = request.title() == null ? "" : request.title().trim();
        if (title.isBlank() || title.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "作业标题不能为空且不能超过 255 个字符");
        }
        long teacherUserId = AuthContext.requirePrincipal().userId();
        return repository.find(repository.create(classId, course.courseId(), teacherUserId, title,
                normalize(request.instructions()), normalize(request.resourceId()), request.dueAt())).orElseThrow();
    }

    public List<TeacherAssignmentRepository.AssignmentRow> listForClass(long classId) {
        requireClassOwner(classId);
        return repository.listByClass(classId);
    }

    @Transactional
    public TeacherAssignmentRepository.AssignmentRow publish(long classId, long assignmentId) {
        TeacherAssignmentRepository.AssignmentRow assignment = requireAssignment(assignmentId);
        requireClassOwnerAndMatch(assignment, classId);
        roleGuard.requireCourseManager(assignment.courseId());
        if ("archived".equals(assignment.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已归档作业不能再次发布");
        }
        repository.publish(assignmentId);
        return requireAssignment(assignmentId);
    }

    public List<TeacherAssignmentRepository.ProgressRow> progress(long classId, long assignmentId) {
        TeacherAssignmentRepository.AssignmentRow assignment = requireAssignment(assignmentId);
        requireClassOwnerAndMatch(assignment, classId);
        roleGuard.requireCourseManager(assignment.courseId());
        return repository.progress(assignmentId);
    }

    public List<TeacherAssignmentRepository.StudentAssignmentRow> listForStudent(String status) {
        roleGuard.requireAny("student");
        return repository.listForStudent(AuthContext.requirePrincipal().userId(), status);
    }

    @Transactional
    public TeacherAssignmentRepository.StudentAssignmentRow complete(long assignmentId, Double score) {
        roleGuard.requireAny("student");
        if (score != null && (score < 0 || score > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "作业得分必须在 0 到 100 之间");
        }
        var principal = AuthContext.requirePrincipal();
        TeacherAssignmentRepository.AssignmentRow assignment = requireAssignment(assignmentId);
        int updated = repository.complete(assignmentId, principal.userId(), score);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前学生不是该作业的目标成员，或作业尚未发布");
        }
        return repository.listForStudent(principal.userId(), null).stream().filter(item -> item.id() == assignment.id()).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "作业状态不存在"));
    }

    private ClassRepository.ClassRow requireClassOwner(long classId) {
        ClassRepository.ClassRow row = classRepository.find(classId);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "班级不存在: " + classId);
        var principal = AuthContext.requirePrincipal();
        if (!"admin".equals(principal.role()) && row.ownerUserId() != principal.userId()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能管理自己负责的班级");
        }
        return row;
    }

    private void requireClassOwnerAndMatch(TeacherAssignmentRepository.AssignmentRow assignment, long classId) {
        if (assignment.classId() != classId) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "作业不属于当前班级");
        }
        requireClassOwner(classId);
    }

    private ClassRepository.ClassCourseRow requireClassCourse(long classId, long courseId) {
        return classRepository.courses(classId).stream().filter(item -> item.courseId() == courseId).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选课程未绑定到该班级"));
    }

    private TeacherAssignmentRepository.AssignmentRow requireAssignment(long assignmentId) {
        return repository.find(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "作业不存在: " + assignmentId));
    }

    private String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record CreateRequest(long courseId, String title, String instructions, String resourceId,
            OffsetDateTime dueAt) {}
}
