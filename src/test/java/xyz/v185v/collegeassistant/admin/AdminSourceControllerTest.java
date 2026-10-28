package xyz.v185v.collegeassistant.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;
import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.admin.source.AdminSourceService;
import xyz.v185v.collegeassistant.admin.source.SourceConnectorType;
import xyz.v185v.collegeassistant.admin.source.SourceDto;
import xyz.v185v.collegeassistant.admin.source.SourcePreviewItem;
import xyz.v185v.collegeassistant.admin.source.SourceRequest;
import xyz.v185v.collegeassistant.admin.source.SourceScheduleType;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.JwtService;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.config.SecurityConfig;

@WebMvcTest(AdminSourceController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
class AdminSourceControllerTest {

    private static final String AUTHORIZATION = "Bearer source-token";
    private static final AuthPrincipal ADMIN =
            new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());
    private static final AuthPrincipal STUDENT =
            new AuthPrincipal(2L, "student", UserRole.STUDENT.name());

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AdminSourceService service;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void studentCannotCreateOrListSources() throws Exception {
        authenticate(STUDENT);

        mockMvc.perform(get("/api/admin/sources").header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_REQUIRED));
        mockMvc.perform(post("/api/admin/sources")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isForbidden());

        verify(service, never()).list(any(), anyInt(), anyInt());
        verify(service, never()).create(any(), any());
    }

    @Test
    void administratorCanCreateASource() throws Exception {
        authenticate(ADMIN);
        when(service.create(ADMIN, request())).thenReturn(dto());

        mockMvc.perform(post("/api/admin/sources")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.connectorType").value("SINGLE_URL"));
    }

    @Test
    void administratorCanUseEverySourceLifecycleRoute() throws Exception {
        authenticate(ADMIN);
        SourcePreviewItem preview = new SourcePreviewItem(
                "https://catalog.example.edu", "catalog.html", "text/html", 1200);
        AdminJobDto deletion = new AdminJobDto(
                40L, AdminJobType.SOURCE_DELETE, AdminJobStatus.QUEUED,
                "SOURCE", 10L, 1L, 0, null, null, null, null, null, null,
                null, null, Map.of("sourceId", 10L),
                OffsetDateTime.parse("2026-07-25T00:00:00Z"),
                OffsetDateTime.parse("2026-07-25T00:00:00Z"), List.of());
        when(service.get(ADMIN, 10L)).thenReturn(dto());
        when(service.update(ADMIN, 10L, request())).thenReturn(dto());
        when(service.setEnabled(ADMIN, 10L, false)).thenReturn(dto());
        when(service.preview(ADMIN, 10L)).thenReturn(List.of(preview));
        when(service.test(ADMIN, request())).thenReturn(List.of(preview));
        when(service.requestDeletion(ADMIN, 10L)).thenReturn(deletion);

        mockMvc.perform(get("/api/admin/sources/10").header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(10));
        mockMvc.perform(put("/api/admin/sources/10")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/admin/sources/10/enabled")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/sources/10/preview")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].filename").value("catalog.html"));
        mockMvc.perform(post("/api/admin/sources/test")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].byteSize").value(1200));
        mockMvc.perform(delete("/api/admin/sources/10")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobType").value("SOURCE_DELETE"));
    }

    private void authenticate(AuthPrincipal principal) {
        when(jwtService.parseToken("source-token")).thenReturn(principal);
    }

    private SourceRequest request() {
        return new SourceRequest(
                "Registrar", "OFFICIAL", SourceConnectorType.SINGLE_URL,
                "https://catalog.example.edu", true, SourceScheduleType.MANUAL,
                "Asia/Shanghai", null, true, 3, 500, 4, List.of(), List.of());
    }

    private SourceDto dto() {
        return new SourceDto(
                10L, "Registrar", "OFFICIAL", SourceConnectorType.SINGLE_URL,
                "https://catalog.example.edu", true, SourceScheduleType.MANUAL,
                "Asia/Shanghai", null, true, 3, 500, 4, List.of(), List.of(), "NONE",
                null, null, null, OffsetDateTime.parse("2026-07-25T00:00:00Z"),
                OffsetDateTime.parse("2026-07-25T00:00:00Z"));
    }
}
