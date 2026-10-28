package xyz.v185v.collegeassistant.ugc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

import xyz.v185v.collegeassistant.admin.AdminUgcController;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.JwtService;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.config.SecurityConfig;

@WebMvcTest({UgcController.class, AdminUgcController.class})
@Import(SecurityConfig.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
class UgcReviewControllerTest {

    private static final String AUTHORIZATION = "Bearer moderation-token";
    private static final AuthPrincipal STUDENT = new AuthPrincipal(7L, "alice", UserRole.STUDENT.name());
    private static final AuthPrincipal ADMIN = new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UgcService ugcService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void studentReviewIsRejectedByRealSecurityConfigWith40301() throws Exception {
        authenticate(STUDENT);

        mockMvc.perform(post("/api/admin/ugc/11/review")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"APPROVE"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_REQUIRED));

        verify(ugcService, never()).review(any(), any(), any());
    }

    @Test
    void adminCanApprovePendingPost() throws Exception {
        authenticate(ADMIN);
        UgcPostDto approved = postDto(11L, UgcStatus.APPROVED, 0, 0, null);
        when(ugcService.review(eq(ADMIN), eq(11L), any(ReviewUgcRequest.class))).thenReturn(approved);

        mockMvc.perform(post("/api/admin/ugc/11/review")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"APPROVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        ArgumentCaptor<ReviewUgcRequest> requestCaptor = ArgumentCaptor.forClass(ReviewUgcRequest.class);
        verify(ugcService).review(eq(ADMIN), eq(11L), requestCaptor.capture());
        assertThat(requestCaptor.getValue().decision()).isEqualTo(ReviewDecision.APPROVE);
    }

    @Test
    void adminCanRejectPendingPostWithReason() throws Exception {
        authenticate(ADMIN);
        UgcPostDto rejected = postDto(12L, UgcStatus.REJECTED, 0, 0, "Contains private data");
        when(ugcService.review(eq(ADMIN), eq(12L), any(ReviewUgcRequest.class))).thenReturn(rejected);

        mockMvc.perform(post("/api/admin/ugc/12/review")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"REJECT","reason":"Contains private data"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectReason").value("Contains private data"));

        ArgumentCaptor<ReviewUgcRequest> requestCaptor = ArgumentCaptor.forClass(ReviewUgcRequest.class);
        verify(ugcService).review(eq(ADMIN), eq(12L), requestCaptor.capture());
        assertThat(requestCaptor.getValue().decision()).isEqualTo(ReviewDecision.REJECT);
        assertThat(requestCaptor.getValue().reason()).isEqualTo("Contains private data");
    }

    @Test
    void reviewRequiresDecision() throws Exception {
        authenticate(ADMIN);

        mockMvc.perform(post("/api/admin/ugc/13/review")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Missing decision"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));

        verify(ugcService, never()).review(any(), any(), any());
    }

    @Test
    void reviewReasonCannotExceed500Characters() throws Exception {
        authenticate(ADMIN);

        mockMvc.perform(post("/api/admin/ugc/13/review")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"REJECT","reason":"%s"}
                                """.formatted("x".repeat(501))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));

        verify(ugcService, never()).review(any(), any(), any());
    }

    @Test
    void authenticatedStudentCanLikeApprovedPost() throws Exception {
        authenticate(STUDENT);
        UgcPostDto liked = postDto(21L, UgcStatus.APPROVED, 4, 0, null);
        when(ugcService.like(STUDENT, 21L)).thenReturn(liked);

        mockMvc.perform(post("/api/ugc/21/like").header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.likes").value(4));

        verify(ugcService).like(STUDENT, 21L);
    }

    @Test
    void authenticatedStudentCanReportApprovedPost() throws Exception {
        authenticate(STUDENT);

        mockMvc.perform(post("/api/ugc/22/report")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"Contains personal information"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(nullValue()));

        ArgumentCaptor<ReportUgcRequest> requestCaptor = ArgumentCaptor.forClass(ReportUgcRequest.class);
        verify(ugcService).report(eq(STUDENT), eq(22L), requestCaptor.capture());
        assertThat(requestCaptor.getValue().reason()).isEqualTo("Contains personal information");
    }

    @Test
    void reportReasonMustBeNonBlankAndAtMost500Characters() throws Exception {
        authenticate(STUDENT);

        assertInvalidReport("   ");
        assertInvalidReport("x".repeat(501));

        verify(ugcService, never()).report(any(), any(), any());
    }

    @Test
    void adminCanListReportsWithDefaultPagination() throws Exception {
        authenticate(ADMIN);
        UgcReportDto report = new UgcReportDto(
                91L,
                22L,
                7L,
                "Alice",
                "Contains personal information",
                UgcReportStatus.OPEN,
                OffsetDateTime.parse("2026-07-13T12:15:30+08:00"));
        when(ugcService.listReports(22L, 1, 20))
                .thenReturn(new PageResult<>(List.of(report), 1, 1, 20, 1));

        mockMvc.perform(get("/api/admin/ugc/22/reports")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items[0].id").value(91))
                .andExpect(jsonPath("$.data.items[0].reporterName").value("Alice"))
                .andExpect(jsonPath("$.data.items[0].reason")
                        .value("Contains personal information"))
                .andExpect(jsonPath("$.data.items[0].status").value("OPEN"));

        verify(ugcService).listReports(22L, 1, 20);
    }

    @Test
    void reportListRequiresAdminRole() throws Exception {
        authenticate(STUDENT);

        mockMvc.perform(get("/api/admin/ugc/22/reports")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_REQUIRED));

        verify(ugcService, never()).listReports(any(), anyInt(), anyInt());
    }

    @Test
    void reportListValidatesPaginationBounds() throws Exception {
        authenticate(ADMIN);

        mockMvc.perform(get("/api/admin/ugc/22/reports")
                        .header("Authorization", AUTHORIZATION)
                        .queryParam("page", "0")
                        .queryParam("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));

        verify(ugcService, never()).listReports(any(), anyInt(), anyInt());
    }

    @Test
    void administratorCanRemovePost() throws Exception {
        authenticate(ADMIN);
        UgcPostDto removed = postDto(31L, UgcStatus.REMOVED, 0, 2, null);
        when(ugcService.remove(ADMIN, 31L)).thenReturn(removed);

        mockMvc.perform(post("/api/admin/ugc/31/remove")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(31))
                .andExpect(jsonPath("$.data.status").value("REMOVED"));

        verify(ugcService).remove(ADMIN, 31L);
    }

    @Test
    void administratorCanResolveReport() throws Exception {
        authenticate(ADMIN);
        UgcReportDto resolved = new UgcReportDto(
                91L,
                22L,
                7L,
                "Alice",
                "Contains personal information",
                UgcReportStatus.RESOLVED,
                OffsetDateTime.parse("2026-07-13T12:15:30+08:00"));
        when(ugcService.resolveReport(ADMIN, 22L, 91L, UgcReportStatus.RESOLVED))
                .thenReturn(resolved);

        mockMvc.perform(patch("/api/admin/ugc/22/reports/91")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"RESOLVED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(91))
                .andExpect(jsonPath("$.data.status").value("RESOLVED"));

        verify(ugcService).resolveReport(ADMIN, 22L, 91L, UgcReportStatus.RESOLVED);
    }

    @Test
    void reportResolutionRequiresStatus() throws Exception {
        authenticate(ADMIN);

        mockMvc.perform(patch("/api/admin/ugc/22/reports/91")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));

        verify(ugcService, never()).resolveReport(any(), any(), any(), any());
    }

    private void authenticate(AuthPrincipal principal) {
        when(jwtService.parseToken("moderation-token")).thenReturn(principal);
    }

    private void assertInvalidReport(String reason) throws Exception {
        mockMvc.perform(post("/api/ugc/22/report")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"%s"}
                                """.formatted(reason)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED));
    }

    private UgcPostDto postDto(
            Long id,
            UgcStatus status,
            long likes,
            long reports,
            String rejectReason
    ) {
        return new UgcPostDto(
                id,
                8L,
                "Author",
                "How I selected CS courses",
                "This is a detailed course selection experience for new students.",
                List.of("course"),
                "CS101",
                "West Campus",
                status,
                likes,
                reports,
                rejectReason,
                OffsetDateTime.parse("2026-07-13T10:15:30+08:00"),
                status == UgcStatus.PENDING ? null : OffsetDateTime.parse("2026-07-13T11:15:30+08:00"));
    }
}
