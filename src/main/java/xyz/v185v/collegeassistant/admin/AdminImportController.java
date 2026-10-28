package xyz.v185v.collegeassistant.admin;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import xyz.v185v.collegeassistant.admin.document.ImportMetadata;
import xyz.v185v.collegeassistant.admin.document.ImportService;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.ApiResponse;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.ingestion.IngestRunDetailDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunDto;

/** 仅管理员可访问的 multipart 本地导入接口。 */
@Validated
@RestController
@RequestMapping("/api/admin/imports")
public class AdminImportController {

    private final ImportService service;
    private final ObjectMapper objectMapper;

    public AdminImportController(ImportService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<IngestRunDetailDto> create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam("metadata") String metadataJson,
            @RequestParam("files") List<MultipartFile> files
    ) {
        return ApiResponse.ok(service.start(principal, parseMetadata(metadataJson), files));
    }

    @GetMapping
    public ApiResponse<PageResult<IngestRunDto>> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Min(1) @RequestParam(defaultValue = "1") int page,
            @Min(1) @Max(100) @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(service.list(principal, page, size));
    }

    @GetMapping("/{runId}")
    public ApiResponse<IngestRunDetailDto> get(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long runId
    ) {
        return ApiResponse.ok(service.get(principal, runId));
    }

    @PostMapping("/{runId}/retry")
    public ApiResponse<IngestRunDetailDto> retry(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long runId
    ) {
        return ApiResponse.ok(service.retry(principal, runId));
    }

    private ImportMetadata parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            throw new BusinessException(ErrorCode.REQUEST_BODY_INVALID, "Import metadata is required");
        }
        try {
            return objectMapper.readValue(metadataJson, ImportMetadata.class);
        } catch (JacksonException exception) {
            throw new BusinessException(
                    ErrorCode.REQUEST_BODY_INVALID, "Import metadata is invalid", exception);
        }
    }
}
