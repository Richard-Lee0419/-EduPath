package com.edupath.resource;

import com.edupath.agenttask.AgentTaskService;
import com.edupath.common.ApiResponse;
import com.edupath.auth.RoleGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resources")
@Tag(name = "资源管理")
public class ResourceController {

    private final ResourceService resourceService;
    private final ResourceQualityMetricsService resourceQualityMetricsService;
    private final ResourceQualityRegressionService resourceQualityRegressionService;
    private final ResourceQualityRepairService resourceQualityRepairService;
    private final RoleGuard roleGuard;

    public ResourceController(
            ResourceService resourceService,
            ResourceQualityMetricsService resourceQualityMetricsService,
            ResourceQualityRegressionService resourceQualityRegressionService,
            ResourceQualityRepairService resourceQualityRepairService,
            RoleGuard roleGuard) {
        this.resourceService = resourceService;
        this.resourceQualityMetricsService = resourceQualityMetricsService;
        this.resourceQualityRegressionService = resourceQualityRegressionService;
        this.resourceQualityRepairService = resourceQualityRepairService;
        this.roleGuard = roleGuard;
    }

    @GetMapping
    @Operation(summary = "查询资源列表")
    public ApiResponse<ResourceService.ResourceListResponse> listResources(
            @RequestParam(required = false) Integer courseId,
            @RequestParam(name = "course_id", required = false) Integer courseIdSnake,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "created_at") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Integer resolvedCourseId = courseId != null ? courseId : courseIdSnake;
        return ApiResponse.success(resourceService.listResources(resolvedCourseId, type, status, keyword, sort, page, size));
    }

    @GetMapping("/quality-metrics")
    @Operation(summary = "查询资源质量趋势、分布和回归告警")
    public ApiResponse<ResourceQualityMetricsService.QualityMetricsResponse> qualityMetrics(
            @RequestParam(name = "course_id", required = false) Integer courseId,
            @RequestParam(name = "resource_type", required = false) String resourceType,
            @RequestParam(name = "generation_mode", required = false) String generationMode,
            @RequestParam(name = "window_days", defaultValue = "30") int windowDays) {
        return ApiResponse.success(
                resourceQualityMetricsService.metrics(courseId, resourceType, generationMode, windowDays));
    }

    @PostMapping("/quality-regression-tasks")
    @Operation(summary = "创建告警驱动的小批量资源质量回归任务")
    public ApiResponse<AgentTaskService.TaskCreatedResponse> createQualityRegressionTask(
            @RequestBody ResourceQualityRegressionService.QualityRegressionRequest request) {
        return ApiResponse.success(resourceQualityRegressionService.createTask(request));
    }

    @GetMapping("/quality-repairs")
    @Operation(summary = "查询当前用户可见的资源质量修复队列")
    public ApiResponse<ResourceQualityRepairService.RepairListResponse> listQualityRepairs(
            @RequestParam(name = "course_id", required = false) Integer courseId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(resourceQualityRepairService.list(courseId, status, page, size));
    }

    @PatchMapping("/quality-repairs/{repairId}/decision")
    @Operation(summary = "人工批准或驳回资源质量修复项")
    public ApiResponse<ResourceQualityRepairService.RepairDecisionResult> decideQualityRepair(
            @PathVariable String repairId,
            @RequestBody ResourceQualityRepairService.RepairDecisionRequest request) {
        return ApiResponse.success(resourceQualityRepairService.decide(repairId, request));
    }

    @PostMapping("/quality-repairs/{repairId}/execute")
    @Operation(summary = "为已批准质量修复项创建单资源受控修复任务")
    public ApiResponse<ResourceQualityRepairService.RepairExecutionCreated> executeQualityRepair(
            @PathVariable String repairId) {
        return ApiResponse.success(resourceQualityRepairService.startExecution(repairId));
    }

    @GetMapping("/quality-repairs/{repairId}/comparison")
    @Operation(summary = "对比原资源与修复候选版本")
    public ApiResponse<ResourceQualityRepairService.RepairComparison> compareQualityRepair(
            @PathVariable String repairId) {
        return ApiResponse.success(resourceQualityRepairService.comparison(repairId));
    }

    @PostMapping("/quality-repairs/{repairId}/publish")
    @Operation(summary = "人工确认后原子发布修复候选版本")
    public ApiResponse<ResourceQualityRepairService.RepairPublicationResult> publishQualityRepair(
            @PathVariable String repairId,
            @RequestBody ResourceQualityRepairService.RepairPublishRequest request) {
        return ApiResponse.success(resourceQualityRepairService.publish(repairId, request));
    }

    @PostMapping("/quality-repairs/{repairId}/rollback")
    @Operation(summary = "回滚已发布的修复候选并生成新版本")
    public ApiResponse<ResourceQualityRepairService.RepairPublicationResult> rollbackQualityRepair(
            @PathVariable String repairId,
            @RequestBody(required = false) ResourceQualityRepairService.RepairRollbackRequest request) {
        return ApiResponse.success(resourceQualityRepairService.rollback(repairId, request));
    }

    @GetMapping("/{resourceId}")
    @Operation(summary = "查询资源详情")
    public ApiResponse<ResourceService.ResourceDetail> getResource(@PathVariable String resourceId) {
        return ApiResponse.success(resourceService.getResource(resourceId));
    }

    @PostMapping("/{resourceId}/interactions")
    @Operation(summary = "记录当前用户的资源学习行为")
    public ApiResponse<ResourceService.ResourceInteractionResult> recordInteraction(
            @PathVariable String resourceId,
            @RequestBody ResourceService.ResourceInteractionRequest request) {
        return ApiResponse.success(resourceService.recordInteraction(resourceId, request));
    }

    @GetMapping("/{resourceId}/download-url")
    @Operation(summary = "获取资源签名下载链接")
    public ApiResponse<ResourceService.ResourceDownloadUrl> downloadUrl(@PathVariable String resourceId) {
        return ApiResponse.success(resourceService.downloadUrl(resourceId));
    }

    @PatchMapping("/{resourceId}/status")
    @Operation(summary = "更新资源状态")
    public ApiResponse<ResourceService.ResourceSummary> updateStatus(
            @PathVariable String resourceId,
            @RequestBody ResourceService.ResourceStatusUpdateRequest request) {
        ResourceService.ResourceAccess access = resourceService.access(resourceId);
        roleGuard.requireCourseManagerOrOwner(access.courseId(), access.ownerUserId());
        return ApiResponse.success(resourceService.updateStatus(resourceId, request));
    }

    @PatchMapping("/{resourceId}")
    @Operation(summary = "更新资源内容并生成版本记录")
    public ApiResponse<ResourceService.ResourceSummary> updateResource(
            @PathVariable String resourceId,
            @RequestBody ResourceService.ResourceUpdateRequest request) {
        ResourceService.ResourceAccess access = resourceService.access(resourceId);
        roleGuard.requireCourseManagerOrOwner(access.courseId(), access.ownerUserId());
        return ApiResponse.success(resourceService.updateResource(resourceId, request));
    }

    @GetMapping("/{resourceId}/versions")
    @Operation(summary = "查询资源版本记录")
    public ApiResponse<ResourceService.ResourceVersionsResponse> versions(@PathVariable String resourceId) {
        roleGuard.requireAny("teacher", "admin");
        return ApiResponse.success(resourceService.versions(resourceId));
    }

    @PostMapping("/{resourceId}/archive")
    @Operation(summary = "归档资源")
    public ApiResponse<ResourceService.ResourceSummary> archive(@PathVariable String resourceId) {
        ResourceService.ResourceAccess access = resourceService.access(resourceId);
        roleGuard.requireCourseManagerOrOwner(access.courseId(), access.ownerUserId());
        return ApiResponse.success(resourceService.archive(resourceId));
    }

    @DeleteMapping("/{resourceId}")
    @Operation(summary = "删除资源")
    public ApiResponse<Void> delete(@PathVariable String resourceId) {
        ResourceService.ResourceAccess access = resourceService.access(resourceId);
        roleGuard.requireCourseManagerOrOwner(access.courseId(), access.ownerUserId());
        resourceService.delete(resourceId);
        return ApiResponse.success(null);
    }
}
