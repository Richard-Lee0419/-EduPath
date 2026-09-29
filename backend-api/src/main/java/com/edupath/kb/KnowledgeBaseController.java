package com.edupath.kb;

import com.edupath.agenttask.AgentTaskService;
import com.edupath.auth.RoleGuard;
import com.edupath.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/kb")
@Tag(name = "知识库")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final AgentTaskService agentTaskService;
    private final RoleGuard roleGuard;

    public KnowledgeBaseController(
            KnowledgeBaseService knowledgeBaseService,
            AgentTaskService agentTaskService,
            RoleGuard roleGuard) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.agentTaskService = agentTaskService;
        this.roleGuard = roleGuard;
    }

    @PostMapping("/upload")
    @Operation(summary = "上传知识库文档并预留对象存储位置")
    public ApiResponse<KnowledgeBaseService.DocumentUploadResult> upload(
            @RequestParam long courseId, @RequestPart MultipartFile file) throws IOException {
        roleGuard.requireCourseManager(courseId);
        return ApiResponse.success(
                knowledgeBaseService.upload(courseId, file.getOriginalFilename(), file.getContentType(), file.getBytes()));
    }

    @PostMapping("/search")
    @Operation(summary = "检索课程知识库")
    public ApiResponse<KnowledgeBaseService.KnowledgeSearchResponse> search(
            @Valid @RequestBody KnowledgeBaseService.KnowledgeSearchRequest request) {
        return ApiResponse.success(knowledgeBaseService.search(request));
    }

    @GetMapping("/documents")
    @Operation(summary = "查询知识库文档")
    public ApiResponse<List<KnowledgeDocumentRepository.DocumentRow>> documents(
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(knowledgeBaseService.documents(courseId, status));
    }

    @GetMapping("/documents/{documentId}/download-url")
    @Operation(summary = "获取知识库文档签名下载链接")
    public ApiResponse<KnowledgeBaseService.DocumentDownloadUrl> downloadUrl(@PathVariable long documentId) {
        roleGuard.requireCourseManager(knowledgeBaseService.documentCourseId(documentId));
        return ApiResponse.success(knowledgeBaseService.downloadUrl(documentId));
    }

    @PatchMapping("/documents/{documentId}/status")
    @Operation(summary = "更新文档解析/索引状态")
    public ApiResponse<KnowledgeDocumentRepository.DocumentRow> updateStatus(
            @PathVariable long documentId,
            @RequestBody KnowledgeBaseService.DocumentStatusUpdateRequest request) {
        roleGuard.requireAny("teacher", "admin");
        return ApiResponse.success(knowledgeBaseService.updateDocumentStatus(documentId, request));
    }

    @PostMapping("/documents/{documentId}/reindex")
    @Operation(summary = "重建文档索引任务")
    public ApiResponse<AgentTaskService.TaskCreatedResponse> reindex(@PathVariable long documentId) {
        roleGuard.requireAny("teacher", "admin");
        var plans = List.of(
                new AgentTaskService.AgentStepPlan("KnowledgeAgent", "正在读取文档元数据", "已读取文档元数据"),
                new AgentTaskService.AgentStepPlan("KnowledgeAgent", "正在重建向量索引", "已重建向量索引"),
                new AgentTaskService.AgentStepPlan("SafetyAgent", "正在检查索引结果", "索引检查通过"));
        AgentTaskService.TaskCreatedResponse response = agentTaskService.createObservableWorkflowTask(
                "kb_index",
                plans,
                "kb_reindex",
                java.util.Map.of("document_id", documentId),
                progress -> {
                    progress.running("KnowledgeAgent", "正在读取文档对象和元数据", 12);
                    progress.success("KnowledgeAgent", "已读取文档对象和元数据", 24);
                    progress.running("KnowledgeAgent", "正在调用 AI 服务解析、切分并写入向量索引", 36);
                    Object result = knowledgeBaseService.reindexDocument(documentId);
                    progress.success("KnowledgeAgent", "已完成解析、语义切分、Embedding 和索引写入", 82);
                    progress.running("SafetyAgent", "正在检查索引结果", 90);
                    progress.success("SafetyAgent", "索引检查通过", 96);
                    return result;
                });
        knowledgeBaseService.createIndexJob(documentId, response.taskId(), "queued", "等待重建索引任务执行");
        return ApiResponse.success(response);
    }

    @DeleteMapping("/documents/{documentId}")
    @Operation(summary = "删除知识库文档")
    public ApiResponse<Void> delete(@PathVariable long documentId) {
        roleGuard.requireAny("teacher", "admin");
        knowledgeBaseService.deleteDocument(documentId);
        return ApiResponse.success(null);
    }
}
