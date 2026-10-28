package xyz.v185v.collegeassistant.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.admin.system.ConsistencyScanService;
import xyz.v185v.collegeassistant.admin.system.DatabaseOverviewDto;
import xyz.v185v.collegeassistant.admin.system.DatabaseOverviewDto.ModelStatus;
import xyz.v185v.collegeassistant.admin.system.DatabaseOverviewService;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.JwtService;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.config.SecurityConfig;

@WebMvcTest({AdminOverviewController.class, AdminSystemController.class})
@Import(SecurityConfig.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
class AdminOverviewControllerTest {

    private static final String AUTHORIZATION = "Bearer system-token";
    private static final AuthPrincipal ADMIN =
            new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());
    private static final AuthPrincipal STUDENT =
            new AuthPrincipal(2L, "student", UserRole.STUDENT.name());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DatabaseOverviewService overviewService;

    @MockitoBean
    private ConsistencyScanService scanService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void administratorCanReadBothOverviewRoutes() throws Exception {
        authenticate(ADMIN);
        when(overviewService.getOverview(ADMIN)).thenReturn(overview());

        mockMvc.perform(get("/api/admin/overview").header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.databaseVersion").value("PostgreSQL 18"))
                .andExpect(jsonPath("$.data.tableCounts.document").value(5))
                .andExpect(jsonPath("$.data.modelStatus.provider").value("openai"))
                .andExpect(jsonPath("$.data.modelStatus.apiKey").doesNotExist());
        mockMvc.perform(get("/api/admin/database/overview")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pgvectorVersion").value("0.8.1"))
                .andExpect(jsonPath("$.data.storageAvailable").value(true));
    }

    @Test
    void administratorCanStartAndInspectConsistencyScan() throws Exception {
        authenticate(ADMIN);
        when(scanService.start(ADMIN)).thenReturn(job(AdminJobStatus.QUEUED));
        when(scanService.get(ADMIN, 41L)).thenReturn(job(AdminJobStatus.SUCCEEDED));

        mockMvc.perform(post("/api/admin/database/consistency-scans")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(41))
                .andExpect(jsonPath("$.data.jobType").value("CONSISTENCY_SCAN"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
        mockMvc.perform(get("/api/admin/database/consistency-scans/41")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        verify(scanService).start(ADMIN);
        verify(scanService).get(ADMIN, 41L);
    }

    @Test
    void studentCannotUseSystemAdministrationRoutes() throws Exception {
        authenticate(STUDENT);

        mockMvc.perform(get("/api/admin/overview").header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_REQUIRED));
        mockMvc.perform(get("/api/admin/database/overview")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_REQUIRED));
        mockMvc.perform(post("/api/admin/database/consistency-scans")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_REQUIRED));
        mockMvc.perform(get("/api/admin/database/consistency-scans/41")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_REQUIRED));

        verify(overviewService, never()).getOverview(any());
        verify(scanService, never()).start(any());
        verify(scanService, never()).get(any(), any());
    }

    private void authenticate(AuthPrincipal principal) {
        when(jwtService.parseToken("system-token")).thenReturn(principal);
    }

    private DatabaseOverviewDto overview() {
        return new DatabaseOverviewDto(
                "PostgreSQL 18",
                "0.8.1",
                "13",
                1_024L,
                256L,
                Map.of("document", 5L),
                2,
                true,
                new ModelStatus("openai", "text-embedding-3-small", true, 1536),
                List.of());
    }

    private AdminJobDto job(AdminJobStatus status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-25T00:00:00Z");
        return new AdminJobDto(
                41L,
                AdminJobType.CONSISTENCY_SCAN,
                status,
                "DATABASE",
                null,
                1L,
                0,
                null,
                null,
                null,
                null,
                null,
                status == AdminJobStatus.QUEUED ? null : now,
                null,
                null,
                Map.of(),
                now,
                now,
                List.of());
    }
}
