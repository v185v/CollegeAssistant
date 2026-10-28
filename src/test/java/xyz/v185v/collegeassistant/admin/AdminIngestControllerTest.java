package xyz.v185v.collegeassistant.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.JwtService;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.config.SecurityConfig;
import xyz.v185v.collegeassistant.ingestion.IngestRunDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunDetailDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunStatus;
import xyz.v185v.collegeassistant.ingestion.application.IngestionService;
import xyz.v185v.collegeassistant.ingestion.RunIngestRequest;

@WebMvcTest(AdminIngestController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
class AdminIngestControllerTest {

    private static final String AUTHORIZATION = "Bearer ingest-token";
    private static final AuthPrincipal ADMIN = new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());
    private static final AuthPrincipal STUDENT = new AuthPrincipal(7L, "student", UserRole.STUDENT.name());
    private static final OffsetDateTime STARTED = OffsetDateTime.parse("2026-07-14T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IngestionService ingestionService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void studentCannotStartOrListIngestionRuns() throws Exception {
        authenticate(STUDENT);

        mockMvc.perform(post("/api/admin/ingest/10/run")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_REQUIRED));
        mockMvc.perform(get("/api/admin/ingest/runs")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_REQUIRED));
        mockMvc.perform(get("/api/admin/ingest/runs/90")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_REQUIRED));
        mockMvc.perform(post("/api/admin/ingest/runs/90/retry")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_REQUIRED));

        verify(ingestionService, never()).start(any(), anyLong(), any());
        verify(ingestionService, never()).listRuns(any(), any(), any(), anyInt(), anyInt());
        verify(ingestionService, never()).getRun(any(), anyLong());
        verify(ingestionService, never()).retryFailedRun(any(), anyLong());
    }

    @Test
    void adminCanStartRunWithMappedRequest() throws Exception {
        authenticate(ADMIN);
        when(ingestionService.start(any(), anyLong(), any())).thenReturn(running(90L));

        mockMvc.perform(post("/api/admin/ingest/10/run")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"force":true,"rebuildVectors":false,"reason":"manual refresh"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(90))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        ArgumentCaptor<RunIngestRequest> request = ArgumentCaptor.forClass(RunIngestRequest.class);
        verify(ingestionService).start(org.mockito.ArgumentMatchers.eq(ADMIN),
                org.mockito.ArgumentMatchers.eq(10L), request.capture());
        assertThat(request.getValue().force()).isTrue();
        assertThat(request.getValue().rebuildVectors()).isFalse();
        assertThat(request.getValue().reason()).isEqualTo("manual refresh");
    }

    @Test
    void nullFlagsDefaultToFalseDuringJsonBinding() throws Exception {
        authenticate(ADMIN);
        when(ingestionService.start(any(), anyLong(), any())).thenReturn(running(91L));

        mockMvc.perform(post("/api/admin/ingest/10/run")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        ArgumentCaptor<RunIngestRequest> request = ArgumentCaptor.forClass(RunIngestRequest.class);
        verify(ingestionService).start(any(), anyLong(), request.capture());
        assertThat(request.getValue().force()).isFalse();
        assertThat(request.getValue().rebuildVectors()).isFalse();
    }

    @Test
    void adminCanListFilteredRunsWithOneBasedPagination() throws Exception {
        authenticate(ADMIN);
        when(ingestionService.listRuns(ADMIN, 10L, IngestRunStatus.SUCCESS, 2, 5))
                .thenReturn(new PageResult<>(List.of(run(90L)), 6, 2, 5, 2));

        mockMvc.perform(get("/api/admin/ingest/runs")
                        .header("Authorization", AUTHORIZATION)
                        .queryParam("sourceId", "10")
                        .queryParam("status", "SUCCESS")
                        .queryParam("page", "2")
                        .queryParam("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].id").value(90))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.pages").value(2));
    }

    @Test
    void adminCanInspectDetailAndManuallyRetryFailedRun() throws Exception {
        authenticate(ADMIN);
        when(ingestionService.getRun(ADMIN, 90L)).thenReturn(new IngestRunDetailDto(
                90L, 10L, IngestRunStatus.FAILED, "MANUAL", null, 1,
                0, 0, 0, 1, 0, null, null, STARTED,
                "CRAWL_FAILED", "Source crawl failed", STARTED, STARTED.plusSeconds(2), List.of()));
        when(ingestionService.retryFailedRun(ADMIN, 90L)).thenReturn(running(91L));

        mockMvc.perform(get("/api/admin/ingest/runs/90")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(90))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.errorCode").value("CRAWL_FAILED"));
        mockMvc.perform(post("/api/admin/ingest/runs/90/retry")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(91))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
    }

    @Test
    void invalidBodyAndQueryValuesReturnValidationErrors() throws Exception {
        authenticate(ADMIN);

        mockMvc.perform(post("/api/admin/ingest/10/run")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"%s"}
                                """.formatted("x".repeat(201))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));

        assertInvalidQuery("sourceId", "0");
        assertInvalidQuery("page", "0");
        assertInvalidQuery("size", "0");
        assertInvalidQuery("size", "101");
        assertInvalidQuery("status", "NOT_A_STATUS");
        verify(ingestionService, never()).listRuns(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void duplicateRunningErrorIsReturnedWithoutTranslation() throws Exception {
        authenticate(ADMIN);
        when(ingestionService.start(any(), anyLong(), any())).thenThrow(new BusinessException(
                ErrorCode.INGEST_ALREADY_RUNNING, "Ingestion already running"));

        mockMvc.perform(post("/api/admin/ingest/10/run")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.INGEST_ALREADY_RUNNING));
    }

    private void authenticate(AuthPrincipal principal) {
        when(jwtService.parseToken("ingest-token")).thenReturn(principal);
    }

    private void assertInvalidQuery(String name, String value) throws Exception {
        mockMvc.perform(get("/api/admin/ingest/runs")
                        .header("Authorization", AUTHORIZATION)
                        .queryParam(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));
    }

    private IngestRunDto run(Long id) {
        return new IngestRunDto(
                id,
                10L,
                IngestRunStatus.SUCCESS,
                1,
                0,
                0,
                0,
                null,
                STARTED,
                STARTED.plusSeconds(2));
    }

    private IngestRunDto running(Long id) {
        return new IngestRunDto(
                id, 10L, IngestRunStatus.RUNNING, 0, 0, 0, 0, null, STARTED, null);
    }
}
