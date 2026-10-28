package xyz.v185v.collegeassistant.ugc;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.ApiResponse;
import xyz.v185v.collegeassistant.common.PageResult;

/** UGC 提交、查询、点赞与举报接口。 */
@RestController
@RequestMapping("/api/ugc")
public class UgcController {

    private final UgcService ugcService;

    public UgcController(UgcService ugcService) {
        this.ugcService = ugcService;
    }

    /** 创建待审核的校园经验内容。 */
    @PostMapping
    public ApiResponse<UgcPostDto> create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreateUgcPostRequest request
    ) {
        return ApiResponse.ok(ugcService.create(principal, request));
    }

    /** 按可见性规则分页查询 UGC。 */
    @GetMapping
    public ApiResponse<PageResult<UgcPostDto>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(required = false) UgcStatus status,
            @RequestParam(required = false) String tag
    ) {
        return ApiResponse.ok(ugcService.list(principal, page, size, mine, status, tag));
    }

    /** 查询当前用户有权查看的指定 UGC。 */
    @GetMapping("/{id}")
    public ApiResponse<UgcPostDto> get(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long id
    ) {
        return ApiResponse.ok(ugcService.get(principal, id));
    }

    /** 为已审核公开的 UGC 增加一次点赞计数。 */
    @PostMapping("/{id}/like")
    public ApiResponse<UgcPostDto> like(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long id
    ) {
        return ApiResponse.ok(ugcService.like(principal, id));
    }

    /** 举报已审核公开的 UGC。 */
    @PostMapping("/{id}/report")
    public ApiResponse<Void> report(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody ReportUgcRequest request
    ) {
        ugcService.report(principal, id, request);
        return ApiResponse.ok();
    }
}
