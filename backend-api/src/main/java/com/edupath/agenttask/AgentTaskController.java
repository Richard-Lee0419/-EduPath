package com.edupath.agenttask;

import com.edupath.ai.AiAgentClient;
import com.edupath.common.ApiResponse;
import com.edupath.kb.KnowledgeBaseService;
import com.edupath.path.LearningPathService;
import com.edupath.profile.ProfileService;
import com.edupath.resource.ResourceService;
import com.edupath.resource.ResourceQualityRegressionService;
import com.edupath.resource.ResourceTaskRequest;
import com.edupath.tutor.TutorService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/agent")
@Tag(name = "Agent 任务")
public class AgentTaskController {

    private final AgentTaskService agentTaskService;
    private final ResourceService resourceService;
    private final ResourceQualityRegressionService resourceQualityRegressionService;
    private final ProfileService profileService;
    private final LearningPathService learningPathService;
    private final TutorService tutorService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AiAgentClient aiAgentClient;
    private final ObjectMapper objectMapper;

    public AgentTaskController(
            AgentTaskService agentTaskService,
            ResourceService resourceService,
            ResourceQualityRegressionService resourceQualityRegressionService,
            ProfileService profileService,
            LearningPathService learningPathService,
            TutorService tutorService,
            KnowledgeBaseService knowledgeBaseService,
            AiAgentClient aiAgentClient,
            ObjectMapper objectMapper) {
        this.agentTaskService = agentTaskService;
        this.resourceService = resourceService;
        this.resourceQualityRegressionService = resourceQualityRegressionService;
        this.profileService = profileService;
        this.learningPathService = learningPathService;
        this.tutorService = tutorService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.aiAgentClient = aiAgentClient;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/resource-task")
    @Operation(summary = "创建资源生成长任务")
    public ApiResponse<AgentTaskService.TaskCreatedResponse> createResourceTask(
            @Valid @RequestBody ResourceTaskRequest request) {
        List<AgentTaskService.AgentStepPlan> plans = resourcePlans(request.normalizedResourceTypes());
        List<String> plannedAgents = plans.stream().map(AgentTaskService.AgentStepPlan::agent).toList();
        Map<String, Object> profileSnapshot = profileService.currentProfileSnapshot();
        return ApiResponse.success(agentTaskService.createObservableWorkflowTask(
                "resource",
                plans,
                "resource_task",
                request,
                progress -> {
                    Map<String, String> observedStatuses = new HashMap<>();
                    AiAgentClient.AiResourceGenerateResult aiResult =
                            aiAgentClient.generateResourceAsync(
                                    request,
                                    plannedAgents,
                                    profileSnapshot,
                                    snapshot -> applyAiProgress(progress, snapshot, observedStatuses));
                    Object result = resourceService.generateFromTask(request, aiResult);
                    return result;
                }));
    }

    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "查询任务状态")
    public ApiResponse<AgentTaskService.AgentTaskSnapshot> getTask(@PathVariable String taskId) {
        return ApiResponse.success(agentTaskService.getTask(taskId));
    }

    @GetMapping("/tasks")
    @Operation(summary = "查询当前用户的任务列表")
    public ApiResponse<AgentTaskService.TaskListResponse> listTasks(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(agentTaskService.listTasks(status, page, size));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    @Operation(summary = "取消任务")
    public ApiResponse<AgentTaskService.TaskCreatedResponse> cancelTask(@PathVariable String taskId) {
        return ApiResponse.success(agentTaskService.cancelTask(taskId));
    }

    @PostMapping("/tasks/{taskId}/retry")
    @Operation(summary = "重试失败任务")
    public ApiResponse<AgentTaskService.TaskCreatedResponse> retryTask(@PathVariable String taskId) {
        AgentTaskRepository.TaskMetadata metadata = agentTaskService.getTaskMetadata(taskId);
        if (!"failed".equals(metadata.status()) && !"cancelled".equals(metadata.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有失败或已取消任务可以重试");
        }
        try {
            return switch (metadata.operationType()) {
                case "resource_task" -> createResourceTask(
                        objectMapper.readValue(metadata.requestPayload(), ResourceTaskRequest.class));
                case "profile_chat" -> retryProfileChat(
                        objectMapper.readValue(metadata.requestPayload(), ProfileService.ProfileChatRequest.class));
                case "path_generate" -> retryPathGenerate(
                        objectMapper.readValue(metadata.requestPayload(), LearningPathService.LearningPathGenerateRequest.class));
                case "tutor_chat" -> retryTutorChat(
                        objectMapper.readValue(metadata.requestPayload(), TutorService.TutorChatRequest.class));
                case "kb_reindex" -> retryKbReindex(metadata.requestPayload());
                case "quality_regression" -> ApiResponse.success(resourceQualityRegressionService.createTask(
                        objectMapper.readValue(
                                metadata.requestPayload(),
                                ResourceQualityRegressionService.QualityRegressionRequest.class)));
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前任务类型暂不支持自动重试");
            };
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务原始请求无法解析", exception);
        }
    }

    @GetMapping("/tasks/{taskId}/stream")
    @Operation(summary = "SSE 流式任务进度")
    public SseEmitter streamTask(@PathVariable String taskId) {
        SseEmitter emitter = new SseEmitter(30_000L);
        agentTaskService.streamTask(taskId, emitter);
        return emitter;
    }

    private List<AgentTaskService.AgentStepPlan> resourcePlans(List<String> resourceTypes) {
        List<AgentTaskService.AgentStepPlan> plans = new ArrayList<>();
        plans.add(new AgentTaskService.AgentStepPlan(
                "ProfileAgent", "正在读取学生画像和学习偏好", "已读取学生画像"));
        plans.add(new AgentTaskService.AgentStepPlan(
                "KnowledgeAgent", "正在检索课程知识库证据", "已检索课程知识库"));
        plans.add(new AgentTaskService.AgentStepPlan(
                "ResourcePlannerAgent", "正在规划资源组合和难度梯度", "已完成资源规划"));
        Set<String> resourceAgents = new LinkedHashSet<>();
        for (String type : resourceTypes) {
            resourceAgents.add(agentForResourceType(type));
        }
        for (String agent : resourceAgents) {
            plans.add(new AgentTaskService.AgentStepPlan(
                    agent,
                    "正在调用真实模型生成结构化内容",
                    "已完成模型生成和结构校验"));
        }
        plans.add(new AgentTaskService.AgentStepPlan(
                "SafetyAgent", "正在执行证据一致性和安全审查", "SafetyAgent 审查通过"));
        return plans;
    }

    private String agentForResourceType(String type) {
        return switch (type) {
            case "mindmap" -> "MindmapAgent";
            case "quiz" -> "QuizAgent";
            case "codelab" -> "CodelabAgent";
            case "animation_script" -> "AnimationScriptAgent";
            case "flowchart" -> "MindmapAgent";
            case "reading" -> "LectureAgent";
            default -> "LectureAgent";
        };
    }

    private void applyAiProgress(
            AgentTaskService.TaskProgress progress,
            Map<String, Object> snapshot,
            Map<String, String> observedStatuses) {
        int progressValue = snapshot.get("progress") instanceof Number number ? number.intValue() : 3;
        Object rawSteps = snapshot.get("steps");
        if (!(rawSteps instanceof List<?> steps)) {
            return;
        }
        for (Object rawStep : steps) {
            if (!(rawStep instanceof Map<?, ?> step)) {
                continue;
            }
            String agent = String.valueOf(step.get("agent"));
            String status = String.valueOf(step.get("status"));
            String message = String.valueOf(step.get("message"));
            if (status.equals(observedStatuses.get(agent))) {
                continue;
            }
            if ("running".equals(status)) {
                progress.running(agent, message, progressValue);
                observedStatuses.put(agent, status);
            } else if ("success".equals(status)) {
                progress.success(agent, message, progressValue);
                observedStatuses.put(agent, status);
            }
        }
    }

    private ApiResponse<AgentTaskService.TaskCreatedResponse> retryProfileChat(ProfileService.ProfileChatRequest request) {
        var plans = List.of(
                new AgentTaskService.AgentStepPlan(
                        "ProfileAgent", "正在抽取学习目标、基础和偏好", "已生成结构化画像增量"),
                new AgentTaskService.AgentStepPlan(
                        "KnowledgeAgent", "正在匹配课程知识点和薄弱项", "已匹配课程知识点"),
                new AgentTaskService.AgentStepPlan(
                        "SafetyAgent", "正在检查画像结论是否过度推断", "SafetyAgent 审查通过"));
        return ApiResponse.success(agentTaskService.createObservableWorkflowTask(
                "profile",
                plans,
                "profile_chat",
                request,
                progress -> {
                    progress.running("ProfileAgent", "正在调用 AI 服务抽取画像维度", 18);
                    Object result = profileService.applyChat(request);
                    progress.success("ProfileAgent", "已生成结构化画像增量", 64);
                    progress.running("KnowledgeAgent", "正在匹配课程知识点和薄弱项", 74);
                    progress.success("KnowledgeAgent", "已匹配课程知识点", 84);
                    progress.running("SafetyAgent", "正在检查画像结论是否过度推断", 92);
                    progress.success("SafetyAgent", "SafetyAgent 审查通过", 96);
                    return result;
                }));
    }

    private ApiResponse<AgentTaskService.TaskCreatedResponse> retryPathGenerate(
            LearningPathService.LearningPathGenerateRequest request) {
        var plans = List.of(
                new AgentTaskService.AgentStepPlan(
                        "ProfileAgent", "正在读取画像目标和薄弱点", "已读取画像目标"),
                new AgentTaskService.AgentStepPlan(
                        "PathAgent", "正在生成按天学习路径", "已生成学习路径草案"),
                new AgentTaskService.AgentStepPlan(
                        "ResourcePlannerAgent", "正在绑定资源和测验任务", "已绑定学习资源"),
                new AgentTaskService.AgentStepPlan(
                        "SafetyAgent", "正在检查路径建议是否合理", "SafetyAgent 审查通过"));
        return ApiResponse.success(agentTaskService.createObservableWorkflowTask(
                "path",
                plans,
                "path_generate",
                request,
                progress -> {
                    progress.running("ProfileAgent", "正在读取画像目标和薄弱点", 14);
                    progress.success("ProfileAgent", "已读取画像目标", 24);
                    progress.running("PathAgent", "正在调用 AI 服务生成按天学习路径", 36);
                    Object result = learningPathService.generate(request);
                    progress.success("PathAgent", "已生成学习路径草案", 70);
                    progress.running("ResourcePlannerAgent", "正在绑定资源和测验任务", 78);
                    progress.success("ResourcePlannerAgent", "已绑定学习资源", 86);
                    progress.running("SafetyAgent", "正在检查路径建议是否合理", 92);
                    progress.success("SafetyAgent", "SafetyAgent 审查通过", 96);
                    return result;
                }));
    }

    private ApiResponse<AgentTaskService.TaskCreatedResponse> retryTutorChat(TutorService.TutorChatRequest request) {
        var plans = List.of(
                new AgentTaskService.AgentStepPlan(
                        "TutorAgent", "正在解析问题和回答模式", "已生成分步辅导方案"),
                new AgentTaskService.AgentStepPlan(
                        "KnowledgeAgent", "正在检索课程证据", "已检索相关证据"),
                new AgentTaskService.AgentStepPlan(
                        "SafetyAgent", "正在检查回答是否引用证据并避免误导", "SafetyAgent 审查通过"));
        return ApiResponse.success(agentTaskService.createObservableWorkflowTask(
                "tutor",
                plans,
                "tutor_chat",
                request,
                progress -> {
                    progress.running("TutorAgent", "正在解析问题和回答模式", 18);
                    progress.success("TutorAgent", "已解析问题和回答模式", 28);
                    progress.running("KnowledgeAgent", "正在调用 AI 服务检索课程证据并生成回答", 42);
                    Object result = tutorService.answer(request);
                    progress.success("KnowledgeAgent", "已检索相关证据并生成回答", 78);
                    progress.running("SafetyAgent", "正在检查回答是否引用证据并避免误导", 90);
                    progress.success("SafetyAgent", "SafetyAgent 审查通过", 96);
                    return result;
                }));
    }

    private ApiResponse<AgentTaskService.TaskCreatedResponse> retryKbReindex(String requestPayload)
            throws JsonProcessingException {
        Map<?, ?> payload = objectMapper.readValue(requestPayload, Map.class);
        long documentId = Long.parseLong(String.valueOf(payload.get("document_id")));
        var plans = List.of(
                new AgentTaskService.AgentStepPlan("KnowledgeAgent", "正在读取文档元数据", "已读取文档元数据"),
                new AgentTaskService.AgentStepPlan("KnowledgeAgent", "正在重建向量索引", "已重建向量索引"),
                new AgentTaskService.AgentStepPlan("SafetyAgent", "正在检查索引结果", "索引检查通过"));
        var response = agentTaskService.createObservableWorkflowTask(
                "kb_index",
                plans,
                "kb_reindex",
                Map.of("document_id", documentId),
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
        knowledgeBaseService.createIndexJob(documentId, response.taskId(), "queued", "重试重建索引");
        return ApiResponse.success(response);
    }
}
