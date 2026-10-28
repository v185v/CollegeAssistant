package xyz.v185v.collegeassistant.admin;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Positive;
import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobService;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.ApiResponse;

/** 仅管理员可访问的持久化后台任务检查与显式重试接口。 */
@Validated
@RestController
@RequestMapping("/api/admin/jobs")
public class AdminJobController {

    private final AdminJobService service;

    public AdminJobController(AdminJobService service) {
        this.service = service;
    }

    @GetMapping("/{jobId}")
    public ApiResponse<AdminJobDto> get(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long jobId
    ) {
        return ApiResponse.ok(service.get(principal, jobId));
    }

    @PostMapping("/{jobId}/retry")
    public ApiResponse<AdminJobDto> retry(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long jobId
    ) {
        return ApiResponse.ok(service.retry(principal, jobId));
    }
}
