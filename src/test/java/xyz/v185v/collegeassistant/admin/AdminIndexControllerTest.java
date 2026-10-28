package xyz.v185v.collegeassistant.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import xyz.v185v.collegeassistant.admin.model.EmbeddingRebuildService;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.JwtService;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.config.SecurityConfig;

@WebMvcTest(AdminIndexController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
class AdminIndexControllerTest {

    private static final String AUTHORIZATION = "Bearer index-token";
    private static final AuthPrincipal ADMIN =
            new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmbeddingRebuildService service;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void administratorCanListStartActivateAndDiscardEmbeddingRebuilds() throws Exception {
        authenticate();
        when(service.list(ADMIN)).thenReturn(List.of(job(41L, AdminJobType.EMBEDDING_REBUILD)));
        when(service.start(ADMIN, 19L)).thenReturn(job(42L, AdminJobType.EMBEDDING_REBUILD));
        when(service.discard(ADMIN, 19L)).thenReturn(job(43L, AdminJobType.INDEX_CLEANUP));

        mockMvc.perform(get("/api/admin/indexes/rebuilds").header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(41))
                .andExpect(jsonPath("$.data[0].jobType").value("EMBEDDING_REBUILD"));
        mockMvc.perform(post("/api/admin/indexes/rebuilds")
                        .header("Authorization", AUTHORIZATION)
                        .contentType("application/json")
                        .content("{\"configurationId\":19}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(42));
        mockMvc.perform(post("/api/admin/indexes/19/activate")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
        mockMvc.perform(delete("/api/admin/indexes/19").header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobType").value("INDEX_CLEANUP"));

        verify(service).activate(ADMIN, 19L);
        verify(service).start(any(), anyLong());
        verify(service).discard(ADMIN, 19L);
    }

    private void authenticate() {
        when(jwtService.parseToken("index-token")).thenReturn(ADMIN);
    }

    private AdminJobDto job(long id, AdminJobType type) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-26T00:00:00Z");
        return new AdminJobDto(
                id, type, AdminJobStatus.QUEUED,
                "EMBEDDING_CONFIGURATION", 19L, ADMIN.userId(),
                0, null, null, null, null, null, null, null, null,
                Map.of(), now, now, List.of());
    }
}
