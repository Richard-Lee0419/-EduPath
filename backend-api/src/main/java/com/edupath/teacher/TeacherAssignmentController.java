package com.edupath.teacher;

import com.edupath.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "教师作业闭环")
public class TeacherAssignmentController {

    private final TeacherAssignmentService service;

    public TeacherAssignmentController(TeacherAssignmentService service) {
        this.service = service;
    }

    @PostMapping("/classes/{classId}/assignments")
    @Operation(summary = "教师创建班级作业草稿")
    public ApiResponse<TeacherAssignmentRepository.AssignmentRow> create(
            @PathVariable long classId, @RequestBody TeacherAssignmentService.CreateRequest request) {
        return ApiResponse.success(service.create(classId, request));
    }

    @GetMapping("/classes/{classId}/assignments")
    @Operation(summary = "教师查询班级作业及完成统计")
    public ApiResponse<List<TeacherAssignmentRepository.AssignmentRow>> listForClass(@PathVariable long classId) {
        return ApiResponse.success(service.listForClass(classId));
    }

    @PostMapping("/classes/{classId}/assignments/{assignmentId}/publish")
    @Operation(summary = "教师发布班级作业并生成学生目标记录")
    public ApiResponse<TeacherAssignmentRepository.AssignmentRow> publish(
            @PathVariable long classId, @PathVariable long assignmentId) {
        return ApiResponse.success(service.publish(classId, assignmentId));
    }

    @GetMapping("/classes/{classId}/assignments/{assignmentId}/progress")
    @Operation(summary = "教师查看班级作业完成情况")
    public ApiResponse<List<TeacherAssignmentRepository.ProgressRow>> progress(
            @PathVariable long classId, @PathVariable long assignmentId) {
        return ApiResponse.success(service.progress(classId, assignmentId));
    }

    @GetMapping("/assignments")
    @Operation(summary = "学生查询已发布作业")
    public ApiResponse<List<TeacherAssignmentRepository.StudentAssignmentRow>> studentAssignments(
            @RequestParam(required = false) String status) {
        return ApiResponse.success(service.listForStudent(status));
    }

    @PostMapping("/assignments/{assignmentId}/complete")
    @Operation(summary = "学生提交作业完成状态")
    public ApiResponse<TeacherAssignmentRepository.StudentAssignmentRow> complete(
            @PathVariable long assignmentId, @RequestBody(required = false) CompleteRequest request) {
        return ApiResponse.success(service.complete(assignmentId, request == null ? null : request.score()));
    }

    public record CompleteRequest(Double score) {}
}
