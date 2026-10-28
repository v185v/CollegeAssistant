package xyz.v185v.collegeassistant.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import xyz.v185v.collegeassistant.admin.document.AdminDocumentService;
import xyz.v185v.collegeassistant.admin.document.DocumentDto;
import xyz.v185v.collegeassistant.admin.document.DownloadableDocument;
import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.JwtService;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.common.StreamingDownloadSupport;
import xyz.v185v.collegeassistant.config.SecurityConfig;
import xyz.v185v.collegeassistant.document.DocumentContentController;
import xyz.v185v.collegeassistant.document.DocumentReferenceType;
import xyz.v185v.collegeassistant.document.DocumentStatus;

@WebMvcTest({AdminDocumentController.class, DocumentContentController.class})
@Import({SecurityConfig.class, StreamingDownloadSupport.class})
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
class AdminDocumentControllerTest {

    private static final String AUTHORIZATION = "Bearer document-token";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-25T08:00:00Z");
    private static final AuthPrincipal ADMIN =
            new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());
    private static final AuthPrincipal STUDENT =
            new AuthPrincipal(2L, "student", UserRole.STUDENT.name());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminDocumentService service;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void administratorCanFilterBrowseReindexAndDeleteDocuments() throws Exception {
        authenticate(ADMIN);
        DocumentDto document = document();
        AdminJobDto reindex = job(AdminJobType.DOCUMENT_REINDEX, 71L);
        AdminJobDto deletion = job(AdminJobType.DOCUMENT_DELETE, 72L);
        when(service.list(
                ADMIN, 2, 10, 7L, DocumentStatus.ACTIVE, "guide",
                DocumentReferenceType.INTERNAL,
                OffsetDateTime.parse("2026-07-01T00:00:00Z"),
                OffsetDateTime.parse("2026-08-01T00:00:00Z")))
                .thenReturn(new PageResult<>(List.of(document), 11, 2, 10, 2));
        when(service.get(ADMIN, 9L)).thenReturn(document);
        when(service.reindex(ADMIN, 9L)).thenReturn(reindex);
        when(service.requestDeletion(ADMIN, 9L)).thenReturn(deletion);

        mockMvc.perform(get("/api/admin/documents")
                        .header("Authorization", AUTHORIZATION)
                        .param("page", "2")
                        .param("size", "10")
                        .param("sourceId", "7")
                        .param("status", "ACTIVE")
                        .param("title", "guide")
                        .param("referenceType", "INTERNAL")
                        .param("ingestedFrom", "2026-07-01T00:00:00Z")
                        .param("ingestedTo", "2026-08-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(11))
                .andExpect(jsonPath("$.data.items[0].id").value(9))
                .andExpect(jsonPath("$.data.items[0].documentStatus").value("ACTIVE"));
        mockMvc.perform(get("/api/admin/documents/9")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalFilename").value("指南 2026.pdf"));
        mockMvc.perform(post("/api/admin/documents/9/reindex")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobType").value("DOCUMENT_REINDEX"));
        mockMvc.perform(delete("/api/admin/documents/9")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobType").value("DOCUMENT_DELETE"));
    }

    @Test
    void nonAdministratorCannotUseDocumentManagementRoutes() throws Exception {
        authenticate(STUDENT);

        mockMvc.perform(get("/api/admin/documents").header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_REQUIRED));
        mockMvc.perform(post("/api/admin/documents/9/reindex")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/admin/documents/9")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden());

        verify(service, never()).list(any(), any(Integer.class), any(Integer.class),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void authenticatedDownloadStreamsRawBytesWithRfc5987FilenameAndClosesInput() throws Exception {
        authenticate(STUDENT);
        byte[] bytes = "document-body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        TrackingInputStream input = new TrackingInputStream(bytes);
        when(service.openDownload(STUDENT, 9L)).thenReturn(new DownloadableDocument(
                input, "application/pdf", "指南 2026.pdf"));

        MvcResult started = mockMvc.perform(get("/api/documents/9/download")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.containsString("filename*=UTF-8''")))
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(bytes));
        org.assertj.core.api.Assertions.assertThat(input.closed).isTrue();
    }

    @Test
    void anonymousDownloadIsRejectedBeforeStorageIsOpened() throws Exception {
        mockMvc.perform(get("/api/documents/9/download"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"));

        verify(service, never()).openDownload(any(), any());
    }

    private void authenticate(AuthPrincipal principal) {
        when(jwtService.parseToken("document-token")).thenReturn(principal);
    }

    private DocumentDto document() {
        return new DocumentDto(
                9L, 7L, "Student guide", "internal://document/9f735650-c80a-4ce9-a788-03ebfed144ac",
                "Registrar", NOW, "sha256", DocumentReferenceType.INTERNAL,
                "https://example.edu/guide", "指南 2026.pdf", DocumentStatus.ACTIVE,
                true, NOW, NOW, NOW, "UNDERGRADUATE", "Engineering", "North", "2026");
    }

    private AdminJobDto job(AdminJobType type, Long id) {
        return new AdminJobDto(
                id, type, AdminJobStatus.QUEUED, "DOCUMENT", 9L, 1L,
                0, null, null, null, null, null, null, null, null,
                Map.of("documentId", 9L), NOW, NOW, List.of());
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
