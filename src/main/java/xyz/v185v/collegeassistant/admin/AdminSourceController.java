package xyz.v185v.collegeassistant.admin;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.source.AdminSourceService;
import xyz.v185v.collegeassistant.admin.source.SourceDto;
import xyz.v185v.collegeassistant.admin.source.SourceEnabledRequest;
import xyz.v185v.collegeassistant.admin.source.SourcePreviewItem;
import xyz.v185v.collegeassistant.admin.source.SourceRequest;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.ApiResponse;
import xyz.v185v.collegeassistant.common.PageResult;

/** 仅管理员可访问的外部来源与上传来源定义管理接口。 */
@Validated
@RestController
@RequestMapping("/api/admin/sources")
public class AdminSourceController {

    private final AdminSourceService service;

    public AdminSourceController(AdminSourceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<SourceDto>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Min(1) @RequestParam(defaultValue = "1") int page,
            @Min(1) @Max(100) @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(service.list(principal, page, size));
    }

    @PostMapping
    public ApiResponse<SourceDto> create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody SourceRequest request
    ) {
        return ApiResponse.ok(service.create(principal, request));
    }

    @PostMapping("/test")
    public ApiResponse<List<SourcePreviewItem>> test(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody SourceRequest request
    ) {
        return ApiResponse.ok(service.test(principal, request));
    }

    @GetMapping("/{sourceId}")
    public ApiResponse<SourceDto> get(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long sourceId
    ) {
        return ApiResponse.ok(service.get(principal, sourceId));
    }

    @PutMapping("/{sourceId}")
    public ApiResponse<SourceDto> update(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long sourceId,
            @Valid @RequestBody SourceRequest request
    ) {
        return ApiResponse.ok(service.update(principal, sourceId, request));
    }

    @PatchMapping("/{sourceId}/enabled")
    public ApiResponse<SourceDto> setEnabled(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long sourceId,
            @Valid @RequestBody SourceEnabledRequest request
    ) {
        return ApiResponse.ok(service.setEnabled(principal, sourceId, request.enabled()));
    }

    @PostMapping("/{sourceId}/preview")
    public ApiResponse<List<SourcePreviewItem>> preview(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long sourceId
    ) {
        return ApiResponse.ok(service.preview(principal, sourceId));
    }

    @DeleteMapping("/{sourceId}")
    public ApiResponse<AdminJobDto> delete(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long sourceId
    ) {
        return ApiResponse.ok(service.requestDeletion(principal, sourceId));
    }
}
