package com.edupath.demo;

import com.edupath.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
@Tag(name = "比赛演示模式")
public class DemoScenarioController {

    private final DemoScenarioService demoScenarioService;
    private final DemoPreflightService demoPreflightService;

    public DemoScenarioController(
            DemoScenarioService demoScenarioService, DemoPreflightService demoPreflightService) {
        this.demoScenarioService = demoScenarioService;
        this.demoPreflightService = demoPreflightService;
    }

    @GetMapping("/status")
    @Operation(summary = "查询软件杯演示数据完整性与推荐动线")
    public ApiResponse<DemoScenarioService.DemoStatus> status() {
        return ApiResponse.success(demoScenarioService.status());
    }

    @GetMapping("/preflight")
    @Operation(summary = "比赛演示前聚合检查与安全降级建议")
    public ApiResponse<DemoPreflightService.DemoPreflightResult> preflight() {
        return ApiResponse.success(demoPreflightService.inspect());
    }

    @PostMapping("/prepare")
    @Operation(summary = "幂等重建专用 demo 账号的软件杯演示数据")
    public ApiResponse<DemoScenarioService.DemoPreparationResult> prepare(
            @RequestBody(required = false) DemoScenarioService.DemoPrepareRequest request) {
        return ApiResponse.success(demoScenarioService.prepare(request));
    }
}
