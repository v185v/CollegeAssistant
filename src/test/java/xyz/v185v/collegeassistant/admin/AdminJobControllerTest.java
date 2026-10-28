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
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobService;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.JwtService;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.config.SecurityConfig;

@WebMvcTest(AdminJobController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
class AdminJobControllerTest {

    private static final String AUTHORIZATION = "Bearer job-token";
    private static final AuthPrincipal ADMIN =
            new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());
    private static final AuthPrincipal STUDENT =
            new AuthPrincipal(2L, "student", UserRole.STUDENT.name());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminJobService service;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void studentCannotInspectOrRetryJob() throws Exception {
        authenticate(STUDENT);

        mockMvc.perform(get("/api/admin/jobs/41").header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_REQUIRED));
        mockMvc.perform(post("/api/admin/jobs/41/retry").header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_REQUIRED));

        verify(service, never()).get(any(), any());
        verify(service, never()).retry(any(), any());
    }

    @Test
    void administratorCanInspectAndRetryJob() throws Exception {
        authenticate(ADMIN);
        when(service.get(ADMIN, 41L)).thenReturn(job(AdminJobStatus.FAILED, 0));
        when(service.retry(ADMIN, 41L)).thenReturn(job(AdminJobStatus.QUEUED, 1));

        mockMvc.perform(get("/api/admin/jobs/41").header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(41))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.leaseToken").doesNotExist());
        mockMvc.perform(post("/api/admin/jobs/41/retry").header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.attempt").value(1));
    }

    private void authenticate(AuthPrincipal principal) {
        when(jwtService.parseToken("job-token")).thenReturn(principal);
    }

    private AdminJobDto job(AdminJobStatus status, int attempt) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-25T00:00:00Z");
        return new AdminJobDto(
                41L, AdminJobType.DOCUMENT_REINDEX, status, "DOCUMENT", 7L, 1L,
                attempt, "worker-a", UUID.fromString("12345678-1234-1234-1234-123456789abc"),
                null, null, null, null, null, null,
                Map.of(), now, now, List.of());
    }
}
