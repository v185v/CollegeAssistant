package xyz.v185v.collegeassistant.admin;

import java.time.OffsetDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditFilter;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditLogDto;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditResult;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditService;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.ApiResponse;
import xyz.v185v.collegeassistant.common.PageResult;

/** 仅管理员可访问的脱敏治理审计记录查询接口。 */
@Validated
@RestController
@RequestMapping("/api/admin/audit-logs")
public class AdminAuditController {

    private final AdminAuditService service;

    public AdminAuditController(AdminAuditService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminAuditLogDto>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Min(1) @RequestParam(defaultValue = "1") int page,
            @Min(1) @Max(100) @RequestParam(defaultValue = "20") int size,
            @Positive @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @Positive @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) AdminAuditResult result,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) OffsetDateTime createdFrom,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) OffsetDateTime createdTo
    ) {
        return ApiResponse.ok(service.list(
                principal,
                new AdminAuditFilter(
                        actorId, action, targetType, targetId, result, createdFrom, createdTo),
                page,
                size));
    }
}
