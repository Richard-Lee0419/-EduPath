package com.edupath.quiz;

import com.edupath.agenttask.AgentTaskService;
import com.edupath.auth.RoleGuard;
import com.edupath.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/quiz")
@Tag(name = "测验评估")
public class QuizController {

    private final QuizService quizService;
    private final RoleGuard roleGuard;
    private final AgentTaskService agentTaskService;

    public QuizController(QuizService quizService, RoleGuard roleGuard, AgentTaskService agentTaskService) {
        this.quizService = quizService;
        this.roleGuard = roleGuard;
        this.agentTaskService = agentTaskService;
    }

    @PostMapping("/generate")
    @Operation(summary = "生成测验并落库题目")
    public ApiResponse<AgentTaskService.TaskCreatedResponse> generate(@RequestBody QuizService.QuizGenerateRequest request) {
        if (request == null || (request.questionCount() != null && (request.questionCount() < 1 || request.questionCount() > 3))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question_count 必须在 1 到 3 之间");
        }
        List<AgentTaskService.AgentStepPlan> plans = List.of(
                new AgentTaskService.AgentStepPlan("KnowledgeAgent", "正在检索课程证据", "已检索课程证据"),
                new AgentTaskService.AgentStepPlan("QuizAgent", "正在生成 3 题以内小测", "已生成结构化小测"),
                new AgentTaskService.AgentStepPlan("SafetyAgent", "正在逐题核对答案与证据", "SafetyAgent 审查通过"));
        return ApiResponse.success(agentTaskService.createObservableWorkflowTask(
                "quiz",
                plans,
                "quiz_generate",
                request,
                progress -> {
                    progress.running("KnowledgeAgent", "正在调用 AI 服务检索课程证据", 16);
                    Object result = quizService.generate(request);
                    progress.success("KnowledgeAgent", "已检索课程证据", 42);
                    progress.running("QuizAgent", "正在校验题量、选项、答案和解析", 58);
                    progress.success("QuizAgent", "已生成并保存 3 题以内小测", 82);
                    progress.running("SafetyAgent", "正在逐题核对答案与证据", 92);
                    progress.success("SafetyAgent", "SafetyAgent 审查通过", 96);
                    return result;
                }));
    }

    @PostMapping("/submit")
    @Operation(summary = "提交测验并记录答题历史")
    public ApiResponse<AgentTaskService.TaskCreatedResponse> submit(@RequestBody QuizService.QuizSubmitRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quiz_id 和 answers 不能为空");
        }
        List<AgentTaskService.AgentStepPlan> plans = List.of(
                new AgentTaskService.AgentStepPlan("ScoringEngine", "正在执行确定性评分", "已完成确定性评分"),
                new AgentTaskService.AgentStepPlan("KnowledgeAgent", "正在检索错题知识证据", "已检索错题知识证据"),
                new AgentTaskService.AgentStepPlan("EvaluationAgent", "正在生成结构化评估", "已生成结构化评估"),
                new AgentTaskService.AgentStepPlan("SafetyAgent", "正在核对评估与真实答题结果", "SafetyAgent 审查通过"),
                new AgentTaskService.AgentStepPlan("ProfileAgent", "正在回写学习画像", "已创建画像新版本"),
                new AgentTaskService.AgentStepPlan("PathAgent", "正在重规划未来三天", "已创建学习路径新版本"));
        return ApiResponse.success(agentTaskService.createObservableWorkflowTask(
                "evaluation",
                plans,
                "quiz_submit",
                request,
                progress -> {
                    progress.running("ScoringEngine", "正在执行确定性评分", 12);
                    progress.success("ScoringEngine", "已完成确定性评分", 24);
                    progress.running("KnowledgeAgent", "正在检索错题知识证据", 34);
                    Object result = quizService.submit(request);
                    progress.success("KnowledgeAgent", "已检索错题知识证据", 48);
                    progress.success("EvaluationAgent", "已生成结构化评估", 72);
                    progress.success("SafetyAgent", "SafetyAgent 审查通过", 88);
                    progress.success("ProfileAgent", "已创建画像新版本", 92);
                    progress.success("PathAgent", "已重规划未来三天并通过路径复核", 96);
                    return result;
                }));
    }

    @GetMapping("/history")
    @Operation(summary = "查询当前学生测验历史")
    public ApiResponse<QuizService.QuizHistoryResponse> history(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(quizService.history(page, size));
    }

    @GetMapping("/wrong-book")
    @Operation(summary = "查询当前学生错题本")
    public ApiResponse<QuizService.WrongBookResponse> wrongBook(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(quizService.wrongBook(page, size));
    }

    @GetMapping("/mastery")
    @Operation(summary = "查询当前学生知识点掌握度")
    public ApiResponse<QuizService.MasteryResponse> mastery() {
        return ApiResponse.success(quizService.mastery());
    }

    @GetMapping("/questions")
    @Operation(summary = "查询题库")
    public ApiResponse<QuizService.QuestionBankResponse> questionBank(
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer courseId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String status,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        roleGuard.requireAny("teacher", "admin");
        return ApiResponse.success(quizService.questionBank(courseId, status, page, size));
    }

    @PostMapping("/questions")
    @Operation(summary = "新增题库题目")
    public ApiResponse<QuizRepository.QuestionBankRow> createQuestion(
            @RequestBody QuizService.QuestionBankCreateRequest request) {
        roleGuard.requireCourseManager(request.courseId());
        return ApiResponse.success(quizService.createQuestionBankItem(request));
    }

    @PatchMapping("/questions/{id}/status")
    @Operation(summary = "更新题库题目状态")
    public ApiResponse<Void> updateQuestionStatus(
            @PathVariable long id,
            @RequestBody QuizService.QuestionBankStatusUpdateRequest request) {
        roleGuard.requireAny("teacher", "admin");
        quizService.updateQuestionBankStatus(id, request);
        return ApiResponse.success(null);
    }
}
