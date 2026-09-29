package com.edupath.learning;

import com.edupath.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/learning")
@Tag(name = "学习行为事件")
public class LearningEventController {

    private final LearningEventService learningEventService;

    public LearningEventController(LearningEventService learningEventService) {
        this.learningEventService = learningEventService;
    }

    @GetMapping("/events")
    @Operation(summary = "查询当前学生的可审计学习事件")
    public ApiResponse<LearningEventService.LearningEventPage> events(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(learningEventService.events(page, size));
    }
}
