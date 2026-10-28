package xyz.v185v.collegeassistant.admin;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
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
import jakarta.validation.constraints.Positive;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.ApiResponse;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.ingestion.IngestRunDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunDetailDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunStatus;
import xyz.v185v.collegeassistant.ingestion.application.IngestionService;
import xyz.v185v.collegeassistant.ingestion.RunIngestRequest;

/**
 * 管理端数据摄取接口，提供手动触发任务和查询运行记录的能力。
 */
@Validated
@RestController
@RequestMapping("/api/admin/ingest")
public class AdminIngestController {

    private final IngestionService ingestionService;

    public AdminIngestController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * 异步启动指定数据源的摄取任务。
     *
     * @param principal 当前登录的管理员
     * @param sourceId  数据源编号
     * @param request   本次任务的触发参数
     * @return 已创建的摄取运行记录
     */
    @PostMapping("/{sourceId}/run")
    public ApiResponse<IngestRunDto> run(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long sourceId,
            @Valid @RequestBody RunIngestRequest request
    ) {
        return ApiResponse.ok(ingestionService.start(principal, sourceId, request));
    }

    /**
     * 分页查询摄取运行记录。
     *
     * @param principal 当前登录的管理员
     * @param sourceId  可选的数据源编号过滤条件
     * @param status    可选的运行状态过滤条件
     * @param page      从 1 开始的页码
     * @param size      每页记录数
     * @return 符合条件的运行记录分页结果
     */
    @GetMapping("/runs")
    public ApiResponse<PageResult<IngestRunDto>> runs(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @RequestParam(required = false) Long sourceId,
            @RequestParam(required = false) IngestRunStatus status,
            @Min(1) @RequestParam(defaultValue = "1") int page,
            @Min(1) @Max(100) @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(ingestionService.listRuns(principal, sourceId, status, page, size));
    }

/** 返回包含条目检查点和脱敏运行错误的任务记录。 */
    @GetMapping("/runs/{runId}")
    public ApiResponse<IngestRunDetailDto> runDetail(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long runId
    ) {
        return ApiResponse.ok(ingestionService.getRun(principal, runId));
    }

/** 根据失败或部分完成的任务创建并提交新的尝试。 */
    @PostMapping("/runs/{runId}/retry")
    public ApiResponse<IngestRunDto> retry(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long runId
    ) {
        return ApiResponse.ok(ingestionService.retryFailedRun(principal, runId));
    }
}
