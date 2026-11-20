package com.edupath.evaluation;

import com.edupath.common.ApiResponse;
import com.edupath.evaluation.PracticeFlow.State;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluation")
@Tag(name = "学习评估")
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final CachePracticeService cachePracticeService;
    private final TreePracticeService treePracticeService;

    public EvaluationController(EvaluationService evaluationService, CachePracticeService cachePracticeService,
                                TreePracticeService treePracticeService) {
        this.evaluationService = evaluationService;
        this.cachePracticeService = cachePracticeService;
        this.treePracticeService = treePracticeService;
    }

    @GetMapping("/report")
    @Operation(summary = "查询学习评估报告")
    public ApiResponse<Map<String, Object>> report() {
        return ApiResponse.success(evaluationService.report());
    }

    @GetMapping("/cache-practice")
    @Operation(summary = "查看 Cache 专项补救练习")
    public ApiResponse<State<CachePracticeService.Exercise, CachePracticeService.Bits>> cachePractice() {
        return ApiResponse.success(cachePracticeService.read());
    }

    @PostMapping("/cache-practice/attempts")
    @Operation(summary = "提交 Cache 专项作答")
    public ApiResponse<State<CachePracticeService.Exercise, CachePracticeService.Bits>> submitCachePractice(
            @RequestBody CachePracticeService.Submission submission) {
        return ApiResponse.success(cachePracticeService.submit(submission));
    }

    @GetMapping("/tree-practice")
    @Operation(summary = "查看二叉树递归专项补救练习")
    public ApiResponse<State<TreePracticeService.Exercise, List<String>>> treePractice() {
        return ApiResponse.success(treePracticeService.read());
    }

    @PostMapping("/tree-practice/attempts")
    @Operation(summary = "提交二叉树递归专项作答")
    public ApiResponse<State<TreePracticeService.Exercise, List<String>>> submitTreePractice(
            @RequestBody TreePracticeService.Submission submission) {
        return ApiResponse.success(treePracticeService.submit(submission));
    }
}
