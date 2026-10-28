package xyz.v185v.collegeassistant.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;
import xyz.v185v.collegeassistant.admin.document.ImportFileMetadata;
import xyz.v185v.collegeassistant.admin.document.ImportMetadata;
import xyz.v185v.collegeassistant.admin.document.ImportService;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.JwtService;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.config.SecurityConfig;
import xyz.v185v.collegeassistant.ingestion.IngestRunDetailDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunItemDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunItemStatus;
import xyz.v185v.collegeassistant.ingestion.IngestRunStatus;

@WebMvcTest(AdminImportController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
class AdminImportControllerTest {

    private static final String AUTHORIZATION = "Bearer import-token";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-25T00:00:00Z");
    private static final AuthPrincipal ADMIN =
            new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());
    private static final AuthPrincipal STUDENT =
            new AuthPrincipal(2L, "student", UserRole.STUDENT.name());

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    ImportService service;

    @MockitoBean
    JwtService jwtService;

    @Test
    void administratorCanCreateListInspectAndRetryImports() throws Exception {
        authenticate(ADMIN);
        ImportMetadata metadata = metadata();
        when(service.start(eq(ADMIN), eq(metadata), anyList())).thenReturn(detail(80L));
        when(service.list(ADMIN, 1, 20)).thenReturn(new PageResult<>(
                List.of(run(80L)), 1, 1, 20, 1));
        when(service.get(ADMIN, 80L)).thenReturn(detail(80L));
        when(service.retry(ADMIN, 80L)).thenReturn(detail(81L));

        mockMvc.perform(multipart("/api/admin/imports")
                        .file(file("handbook.txt", "campus handbook"))
                        .param("metadata", objectMapper.writeValueAsString(metadata))
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].itemKey").value("client-a"));
        mockMvc.perform(get("/api/admin/imports")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/admin/imports/80")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.triggerType").value("IMPORT"));
        mockMvc.perform(post("/api/admin/imports/80/retry")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(81));
    }

    @Test
    void studentCannotReachImportRoutes() throws Exception {
        authenticate(STUDENT);

        mockMvc.perform(get("/api/admin/imports").header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/admin/imports")
                        .file(file("handbook.txt", "body"))
                        .param("metadata", objectMapper.writeValueAsString(metadata()))
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden());

        verify(service, never()).list(any(), anyInt(), anyInt());
        verify(service, never()).start(any(), any(), anyList());
    }

    @Test
    void malformedMetadataAndRejectedFileUseBadRequest() throws Exception {
        authenticate(ADMIN);
        mockMvc.perform(multipart("/api/admin/imports")
                        .file(file("handbook.txt", "body"))
                        .param("metadata", "{not-json")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.REQUEST_BODY_INVALID));

        when(service.start(eq(ADMIN), eq(metadata()), anyList())).thenThrow(
                new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Executable content is not allowed for import"));
        mockMvc.perform(multipart("/api/admin/imports")
                        .file(file("renamed.pdf", "MZ executable"))
                        .param("metadata", objectMapper.writeValueAsString(metadata()))
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));
    }

    private void authenticate(AuthPrincipal principal) {
        when(jwtService.parseToken("import-token")).thenReturn(principal);
    }

    private ImportMetadata metadata() {
        return new ImportMetadata(10L, List.of(new ImportFileMetadata(
                "client-a", "Handbook", null, null,
                "GENERAL", "GENERAL", "GENERAL", "GENERAL")));
    }

    private MockMultipartFile file(String filename, String content) {
        return new MockMultipartFile(
                "files", filename, "application/octet-stream",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private IngestRunDto run(Long id) {
        return new IngestRunDto(
                id, 10L, IngestRunStatus.RUNNING, 0, 0, 0, 0, null, NOW, null);
    }

    private IngestRunDetailDto detail(Long id) {
        IngestRunItemDto item = new IngestRunItemDto(
                id + 10, id, "client-a", "FILE", IngestRunItemStatus.QUEUED,
                null, 0, null, null, null, Map.of("clientId", "client-a"),
                0, NOW, NOW);
        return new IngestRunDetailDto(
                id, 10L, IngestRunStatus.RUNNING, "IMPORT", null, 1,
                0, 0, 0, 0, 0, "worker", NOW, NOW,
                null, null, NOW, null, List.of(item));
    }
}
