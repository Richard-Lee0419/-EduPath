package com.edupath.profile;

import com.edupath.agenttask.AgentTaskService;
import com.edupath.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/profile")
@Tag(name = "学习画像")
public class ProfileController {

    private final ProfileService profileService;
    private final AgentTaskService agentTaskService;

    public ProfileController(ProfileService profileService, AgentTaskService agentTaskService) {
        this.profileService = profileService;
        this.agentTaskService = agentTaskService;
    }

    @PostMapping("/chat")
    @Operation(summary = "通过画像对话创建画像抽取任务")
    public ApiResponse<AgentTaskService.TaskCreatedResponse> chat(
            @RequestBody ProfileService.ProfileChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        List<AgentTaskService.AgentStepPlan> plans = List.of(
                new AgentTaskService.AgentStepPlan(
                        "ProfileAgent", "正在抽取学习目标、基础和偏好", "已生成结构化画像增量"),
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
                    progress.success("ProfileAgent", "已生成并校验结构化画像增量", 82);
                    progress.running("SafetyAgent", "正在检查画像结论是否过度推断", 92);
                    progress.success("SafetyAgent", "SafetyAgent 审查通过", 96);
                    return result;
                }));
    }

    @GetMapping("/current")
    @Operation(summary = "查询当前学习画像")
    public ApiResponse<Map<String, Object>> current() {
        return ApiResponse.success(profileService.currentProfile());
    }
}
