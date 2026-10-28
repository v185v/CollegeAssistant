package xyz.v185v.collegeassistant.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import xyz.v185v.collegeassistant.admin.audit.AdminAuditFilter;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditLogDto;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditResult;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditService;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.JwtService;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.config.SecurityConfig;

@WebMvcTest(AdminAuditController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
class AdminAuditControllerTest {

    private static final String AUTHORIZATION = "Bearer audit-token";
    private static final AuthPrincipal ADMIN =
            new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());
    private static final AuthPrincipal STUDENT =
            new AuthPrincipal(2L, "student", UserRole.STUDENT.name());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminAuditService service;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void administratorCanFilterRedactedAuditInventory() throws Exception {
        authenticate(ADMIN);
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-25T00:00:00Z");
        AdminAuditLogDto row = new AdminAuditLogDto(
                31L, 1L, "SOURCE_UPDATED", "SOURCE", 10L,
                Map.of("enabled", false), "127.0.0.1/32",
                AdminAuditResult.SUCCEEDED, null, createdAt);
        when(service.list(eq(ADMIN), any(), eq(2), eq(25)))
                .thenReturn(new PageResult<>(List.of(row), 26, 2, 25, 2));

        mockMvc.perform(get("/api/admin/audit-logs")
                        .header("Authorization", AUTHORIZATION)
                        .param("page", "2")
                        .param("size", "25")
                        .param("actorId", "1")
                        .param("action", "SOURCE_UPDATED")
                        .param("targetType", "SOURCE")
                        .param("targetId", "10")
                        .param("result", "SUCCEEDED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].action").value("SOURCE_UPDATED"))
                .andExpect(jsonPath("$.data.items[0].summary.enabled").value(false))
                .andExpect(jsonPath("$.data.total").value(26));

        ArgumentCaptor<AdminAuditFilter> filter = ArgumentCaptor.forClass(AdminAuditFilter.class);
        verify(service).list(eq(ADMIN), filter.capture(), eq(2), eq(25));
        assertThat(filter.getValue().actorId()).isEqualTo(1L);
        assertThat(filter.getValue().targetId()).isEqualTo(10L);
        assertThat(filter.getValue().result()).isEqualTo(AdminAuditResult.SUCCEEDED);
    }

    @Test
    void studentCannotReadAuditInventory() throws Exception {
        authenticate(STUDENT);

        mockMvc.perform(get("/api/admin/audit-logs")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_REQUIRED));

        verify(service, never()).list(any(), any(), any(Integer.class), any(Integer.class));
    }

    private void authenticate(AuthPrincipal principal) {
        when(jwtService.parseToken("audit-token")).thenReturn(principal);
    }
}
