package xyz.v185v.collegeassistant.admin;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import xyz.v185v.collegeassistant.admin.system.DatabaseOverviewDto;
import xyz.v185v.collegeassistant.admin.system.DatabaseOverviewService;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.ApiResponse;

/** 精简的管理面板概览。 */
@RestController
@RequestMapping("/api/admin/overview")
public class AdminOverviewController {

    private final DatabaseOverviewService service;

    public AdminOverviewController(DatabaseOverviewService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<DatabaseOverviewDto> get(
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ApiResponse.ok(service.getOverview(principal));
    }
}
