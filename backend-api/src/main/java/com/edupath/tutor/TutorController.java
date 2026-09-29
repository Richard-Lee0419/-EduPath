package com.edupath.tutor;

import com.edupath.agenttask.AgentTaskService;
import com.edupath.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tutor")
@Tag(name = "智能辅导")
public class TutorController {

    private final TutorService tutorService;
    private final AgentTaskService agentTaskService;

    public TutorController(TutorService tutorService, AgentTaskService agentTaskService) {
        this.tutorService = tutorService;
        this.agentTaskService = agentTaskService;
    }

    @PostMapping("/chat")
    @Operation(summary = "创建基于 RAG 证据的辅导答疑任务")
    public ApiResponse<AgentTaskService.TaskCreatedResponse> chat(@RequestBody TutorService.TutorChatRequest request) {
        if (request == null || request.normalizedQuestion().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 或 message 不能为空");
        }
        List<AgentTaskService.AgentStepPlan> plans = List.of(
                new AgentTaskService.AgentStepPlan(
                        "KnowledgeAgent", "正在检索课程证据", "已检索相关证据"),
                new AgentTaskService.AgentStepPlan(
                        "TutorAgent", "正在依据证据生成回答", "已生成结构化辅导回答"),
                new AgentTaskService.AgentStepPlan(
                        "SafetyAgent", "正在检查回答是否引用证据并避免误导", "SafetyAgent 审查通过"));
        return ApiResponse.success(agentTaskService.createObservableWorkflowTask(
                "tutor",
                plans,
                "tutor_chat",
                request,
                progress -> {
                    progress.running("KnowledgeAgent", "正在调用 AI 服务检索课程证据", 18);
                    progress.success("KnowledgeAgent", "已检索课程证据", 38);
                    progress.running("TutorAgent", "正在依据证据生成结构化回答", 48);
                    Object result = tutorService.answer(request);
                    progress.success("TutorAgent", "已生成带引用映射的回答", 78);
                    progress.running("SafetyAgent", "正在检查回答是否引用证据并避免误导", 90);
                    progress.success("SafetyAgent", "SafetyAgent 审查通过", 96);
                    return result;
                }));
    }

    @GetMapping("/sessions")
    @Operation(summary = "查询当前学生辅导会话")
    public ApiResponse<TutorService.SessionListResponse> sessions(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(tutorService.sessions(page, size));
    }
}
