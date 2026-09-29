package com.edupath.observability;

import com.edupath.auth.RoleGuard;
import com.edupath.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/audit-logs")
@Tag(name = "审计日志")
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;
    private final RoleGuard roleGuard;

    public AuditLogController(AuditLogRepository auditLogRepository, RoleGuard roleGuard) {
        this.auditLogRepository = auditLogRepository;
        this.roleGuard = roleGuard;
    }

    @GetMapping
    @Operation(summary = "查询审计日志")
    public ApiResponse<AuditLogListResponse> list(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        roleGuard.requireAdmin();
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.max(1, Math.min(size, 200));
        return ApiResponse.success(new AuditLogListResponse(
                auditLogRepository.list(actor, path, status, normalizedPage, normalizedSize),
                normalizedPage,
                normalizedSize));
    }

    public record AuditLogListResponse(List<AuditLogRepository.AuditLogRow> items, int page, int size) {}
}
