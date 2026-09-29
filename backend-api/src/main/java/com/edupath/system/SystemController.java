package com.edupath.system;

import com.edupath.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
@Tag(name = "系统")
public class SystemController {

    @GetMapping("/health")
    @Operation(summary = "服务健康检查")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.success(
                Map.of(
                        "service", "backend-api",
                        "status", "ok",
                        "time", Instant.now().toString()));
    }

    @GetMapping("/runtime")
    @Operation(summary = "服务运行摘要")
    public ApiResponse<Map<String, Object>> runtime() {
        Runtime runtime = Runtime.getRuntime();
        return ApiResponse.success(Map.of(
                "service",
                "backend-api",
                "status",
                "ok",
                "uptime_ms",
                ManagementFactory.getRuntimeMXBean().getUptime(),
                "available_processors",
                runtime.availableProcessors(),
                "memory_used_bytes",
                runtime.totalMemory() - runtime.freeMemory(),
                "memory_max_bytes",
                runtime.maxMemory()));
    }
}
