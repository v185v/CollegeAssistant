package xyz.v185v.collegeassistant.admin;

import java.time.OffsetDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import xyz.v185v.collegeassistant.admin.document.AdminDocumentService;
import xyz.v185v.collegeassistant.admin.document.DocumentDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.ApiResponse;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.document.DocumentReferenceType;
import xyz.v185v.collegeassistant.document.DocumentStatus;

/** 管理员使用的索引文档浏览与生命周期操作接口。 */
@Validated
@RestController
@RequestMapping("/api/admin/documents")
public class AdminDocumentController {

    private final AdminDocumentService service;

    public AdminDocumentController(AdminDocumentService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<DocumentDto>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Min(1) @RequestParam(defaultValue = "1") int page,
            @Min(1) @Max(100) @RequestParam(defaultValue = "20") int size,
            @Positive @RequestParam(required = false) Long sourceId,
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) DocumentReferenceType referenceType,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) OffsetDateTime ingestedFrom,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @RequestParam(required = false) OffsetDateTime ingestedTo
    ) {
        return ApiResponse.ok(service.list(
                principal, page, size, sourceId, status, title, referenceType,
                ingestedFrom, ingestedTo));
    }

    @GetMapping("/{documentId}")
    public ApiResponse<DocumentDto> get(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long documentId
    ) {
        return ApiResponse.ok(service.get(principal, documentId));
    }

    @PostMapping("/{documentId}/reindex")
    public ApiResponse<AdminJobDto> reindex(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long documentId
    ) {
        return ApiResponse.ok(service.reindex(principal, documentId));
    }

    @DeleteMapping("/{documentId}")
    public ApiResponse<AdminJobDto> delete(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long documentId
    ) {
        return ApiResponse.ok(service.requestDeletion(principal, documentId));
    }
}
