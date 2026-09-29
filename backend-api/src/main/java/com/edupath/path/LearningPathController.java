package com.edupath.path;

import com.edupath.agenttask.AgentTaskService;
import com.edupath.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/path")
@Tag(name = "学习路径")
public class LearningPathController {

    private final LearningPathService learningPathService;
    private final AgentTaskService agentTaskService;

    public LearningPathController(LearningPathService learningPathService, AgentTaskService agentTaskService) {
        this.learningPathService = learningPathService;
        this.agentTaskService = agentTaskService;
    }

    @PostMapping("/generate")
    @Operation(summary = "创建学习路径生成任务")
    public ApiResponse<AgentTaskService.TaskCreatedResponse> generate(
            @RequestBody LearningPathService.LearningPathGenerateRequest request) {
        List<AgentTaskService.AgentStepPlan> plans = List.of(
                new AgentTaskService.AgentStepPlan(
                        "ProfileAgent", "正在读取画像目标和薄弱点", "已读取画像目标"),
                new AgentTaskService.AgentStepPlan(
                        "PathAgent", "正在生成按天学习路径", "已生成学习路径草案"),
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
                    progress.success("PathAgent", "已生成并校验学习路径", 86);
                    progress.running("SafetyAgent", "正在检查路径建议是否合理", 92);
                    progress.success("SafetyAgent", "SafetyAgent 审查通过", 96);
                    return result;
                }));
    }

    @GetMapping("/current")
    @Operation(summary = "查询当前学习路径")
    public ApiResponse<Map<String, Object>> current() {
        return ApiResponse.success(learningPathService.currentPath());
    }

    @GetMapping("/adjustment-signal")
    @Operation(summary = "查询行为驱动的路径调整判定")
    public ApiResponse<Map<String, Object>> adjustmentSignal() {
        return ApiResponse.success(learningPathService.adjustmentSignal());
    }

    @PostMapping("/replan/behavior")
    @Operation(summary = "根据确定性行为信号创建受控路径重规划任务")
    public ApiResponse<BehaviorReplanTaskResponse> replanFromBehavior() {
        LearningPathService.BehaviorReplanPreparation preparation =
                learningPathService.prepareBehaviorReplan();
        List<AgentTaskService.AgentStepPlan> plans = List.of(
                new AgentTaskService.AgentStepPlan(
                        "BehaviorSignalAgent", "正在复核确定性行为触发条件", "行为触发条件复核通过"),
                new AgentTaskService.AgentStepPlan(
                        "ProfileAgent", "正在读取最新学习画像", "已读取最新学习画像"),
                new AgentTaskService.AgentStepPlan(
                        "KnowledgeAgent", "正在检索薄弱知识点课程证据", "已检索课程证据"),
                new AgentTaskService.AgentStepPlan(
                        "PathAgent", "正在重规划未来三天学习路径", "已生成新路径候选版本"),
                new AgentTaskService.AgentStepPlan(
                        "SafetyAgent", "正在复核证据、时间预算与调整范围", "SafetyAgent 审查通过"));
        AgentTaskService.IdempotentTaskCreatedResponse created =
                agentTaskService.createIdempotentObservableWorkflowTask(
                        "path",
                        preparation.triggerKey(),
                        plans,
                        "path_behavior_replan",
                        Map.of(
                                "trigger_key", preparation.triggerKey(),
                                "path_id", preparation.pathId(),
                                "path_version", preparation.pathVersion(),
                                "signal", preparation.signal()),
                        progress -> {
                            progress.running("BehaviorSignalAgent", "正在复核确定性行为触发条件", 10);
                            progress.success("BehaviorSignalAgent", "行为触发条件复核通过", 20);
                            progress.running("ProfileAgent", "正在读取最新学习画像", 26);
                            progress.success("ProfileAgent", "已读取最新学习画像", 34);
                            progress.running("KnowledgeAgent", "正在准备薄弱知识点检索范围", 40);
                            progress.success("KnowledgeAgent", "已准备课程证据检索范围", 48);
                            progress.running("PathAgent", "正在调用 AI 服务重规划未来三天路径", 56);
                            Object result = learningPathService.replanAfterBehavior(preparation);
                            progress.success("PathAgent", "已生成并持久化新路径版本", 86);
                            progress.running("SafetyAgent", "正在确认 SafetyAgent 审查记录", 92);
                            progress.success("SafetyAgent", "SafetyAgent 审查通过", 96);
                            return result;
                        });
        return ApiResponse.success(new BehaviorReplanTaskResponse(
                created.taskId(), created.reused(), preparation.triggerKey(), preparation.signal()));
    }

    @PostMapping("/nodes/{day}/complete")
    @Operation(summary = "完成当前路径中的学习节点")
    public ApiResponse<Map<String, Object>> completeNode(@PathVariable int day) {
        return ApiResponse.success(learningPathService.completeDay(day));
    }

    public record BehaviorReplanTaskResponse(
            String taskId, boolean reused, String triggerKey, Map<String, Object> signal) {}
}
