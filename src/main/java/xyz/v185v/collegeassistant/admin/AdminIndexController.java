package xyz.v185v.collegeassistant.admin;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.model.EmbeddingRebuildRequest;
import xyz.v185v.collegeassistant.admin.model.EmbeddingRebuildService;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.ApiResponse;

/** 管理员使用的隔离向量重建、激活与清理生命周期接口。 */
@Validated
@RestController
@RequestMapping("/api/admin/indexes")
public class AdminIndexController {

    private final EmbeddingRebuildService service;

    public AdminIndexController(EmbeddingRebuildService service) {
        this.service = service;
    }

    @GetMapping("/rebuilds")
    public ApiResponse<List<AdminJobDto>> list(
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ApiResponse.ok(service.list(principal));
    }

    @PostMapping("/rebuilds")
    public ApiResponse<AdminJobDto> start(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody EmbeddingRebuildRequest request
    ) {
        return ApiResponse.ok(service.start(principal, request.configurationId()));
    }

    @PostMapping("/{configurationId}/activate")
    public ApiResponse<Void> activate(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable long configurationId
    ) {
        service.activate(principal, configurationId);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{configurationId}")
    public ApiResponse<AdminJobDto> discard(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable long configurationId
    ) {
        return ApiResponse.ok(service.discard(principal, configurationId));
    }
}
