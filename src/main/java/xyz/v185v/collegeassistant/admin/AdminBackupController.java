package xyz.v185v.collegeassistant.admin;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import xyz.v185v.collegeassistant.admin.backup.BackupDownload;
import xyz.v185v.collegeassistant.admin.backup.DatabaseBackupDto;
import xyz.v185v.collegeassistant.admin.backup.DatabaseBackupService;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.ApiResponse;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.common.StreamingDownloadSupport;

/** 管理员使用的有界逻辑备份生命周期接口。 */
@Validated
@RestController
@RequestMapping("/api/admin/backups")
public class AdminBackupController {

    private final DatabaseBackupService service;
    private final StreamingDownloadSupport streaming;

    public AdminBackupController(
            DatabaseBackupService service,
            StreamingDownloadSupport streaming
    ) {
        this.service = service;
        this.streaming = streaming;
    }

    @GetMapping
    public ApiResponse<PageResult<DatabaseBackupDto>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Min(1) @RequestParam(defaultValue = "1") int page,
            @Min(1) @Max(100) @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(service.list(principal, page, size));
    }

    @PostMapping
    public ApiResponse<DatabaseBackupDto> create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody(required = false) CreateBackupRequest request
    ) {
        int retentionDays = request == null || request.retentionDays() == null
                ? service.defaultRetentionDays()
                : request.retentionDays();
        return ApiResponse.ok(service.create(principal, retentionDays));
    }

    @PostMapping("/{backupId}/validate")
    public ApiResponse<DatabaseBackupDto> validate(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long backupId
    ) {
        return ApiResponse.ok(service.validate(principal, backupId));
    }

    @GetMapping("/{backupId}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long backupId
    ) {
        BackupDownload backup = service.openDownload(principal, backupId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(backup.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, backup.contentDisposition())
                .body(streaming.stream(backup.input()));
    }

    @DeleteMapping("/{backupId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long backupId
    ) {
        service.delete(principal, backupId);
        return ApiResponse.ok(null);
    }

    public record CreateBackupRequest(
            @Min(1) @Max(3650) Integer retentionDays
    ) {
    }
}
