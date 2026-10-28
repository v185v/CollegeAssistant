package xyz.v185v.collegeassistant.admin;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.ApiResponse;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.ugc.ReviewUgcRequest;
import xyz.v185v.collegeassistant.ugc.UgcPostDto;
import xyz.v185v.collegeassistant.ugc.UgcReportDto;
import xyz.v185v.collegeassistant.ugc.UgcReportStatus;
import xyz.v185v.collegeassistant.ugc.UgcService;

/**
 * 管理端 UGC 审核接口。
 *
 * <p>访问权限由安全配置统一限制为管理员角色。</p>
 */
@RestController
@RequestMapping("/api/admin/ugc")
public class AdminUgcController {

    private final UgcService ugcService;

    public AdminUgcController(UgcService ugcService) {
        this.ugcService = ugcService;
    }

    /**
     * 审核指定的用户贡献内容。
     *
     * @param principal 当前登录的管理员
     * @param id        UGC 帖子编号
     * @param request   审核决定及原因
     * @return 审核后的帖子信息
     */
    @PostMapping("/{id}/review")
    public ApiResponse<UgcPostDto> review(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody ReviewUgcRequest request
    ) {
        return ApiResponse.ok(ugcService.review(principal, id, request));
    }

/** 列出已有 UGC 帖子的持久化举报详情。 */
    @GetMapping("/{postId}/reports")
    public ApiResponse<PageResult<UgcReportDto>> reports(
            @PathVariable Long postId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.ok(ugcService.listReports(postId, page, size));
    }

/** 将一篇帖子从公开可见范围及所有向量索引版本中移除。 */
    @PostMapping("/{id}/remove")
    public ApiResponse<UgcPostDto> remove(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long id
    ) {
        return ApiResponse.ok(ugcService.remove(principal, id));
    }

/** 处理或驳回一条未关闭的举报。 */
    @PatchMapping("/{postId}/reports/{reportId}")
    public ApiResponse<UgcReportDto> resolveReport(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable Long postId,
            @PathVariable Long reportId,
            @Valid @RequestBody ReportResolutionRequest request
    ) {
        return ApiResponse.ok(ugcService.resolveReport(
                principal, postId, reportId, request.status()));
    }

    public record ReportResolutionRequest(@NotNull UgcReportStatus status) {
    }
}
