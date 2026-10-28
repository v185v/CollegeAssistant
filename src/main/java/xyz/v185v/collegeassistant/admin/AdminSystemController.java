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
import xyz.v185v.collegeassistant.admin.system.ConsistencyScanService;
import xyz.v185v.collegeassistant.admin.system.DatabaseOverviewDto;
import xyz.v185v.collegeassistant.admin.system.DatabaseOverviewService;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.ApiResponse;

/** 数据库检查与显式只读一致性扫描接口。 */
@Validated
@RestController
@RequestMapping("/api/admin/database")
public class AdminSystemController {

    private final DatabaseOverviewService overviewService;
    private final ConsistencyScanService scanService;

    public AdminSystemController(
            DatabaseOverviewService overviewService,
            ConsistencyScanService scanService
    ) {
        this.overviewService = overviewService;
        this.scanService = scanService;
    }

    @GetMapping("/overview")
    public ApiResponse<DatabaseOverviewDto> overview(
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ApiResponse.ok(overviewService.getOverview(principal));
    }

    @PostMapping("/consistency-scans")
    public ApiResponse<AdminJobDto> startScan(
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ApiResponse.ok(scanService.start(principal));
    }

    @GetMapping("/consistency-scans/{jobId}")
    public ApiResponse<AdminJobDto> getScan(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long jobId
    ) {
        return ApiResponse.ok(scanService.get(principal, jobId));
    }
}
