package xyz.v185v.collegeassistant.document;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import jakarta.validation.constraints.Positive;
import xyz.v185v.collegeassistant.admin.document.AdminDocumentService;
import xyz.v185v.collegeassistant.admin.document.DownloadableDocument;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.StreamingDownloadSupport;

/** 需要认证的原始内容接口；成功响应特意绕过 ApiResponse 包装。 */
@Validated
@RestController
@RequestMapping("/api/documents")
public class DocumentContentController {

    private final AdminDocumentService service;
    private final StreamingDownloadSupport streaming;

    public DocumentContentController(
            AdminDocumentService service,
            StreamingDownloadSupport streaming
    ) {
        this.service = service;
        this.streaming = streaming;
    }

    @GetMapping("/{documentId}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Positive @PathVariable Long documentId
    ) {
        DownloadableDocument document = service.openDownload(principal, documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, document.contentDisposition())
                .body(streaming.stream(document.input()));
    }
}
