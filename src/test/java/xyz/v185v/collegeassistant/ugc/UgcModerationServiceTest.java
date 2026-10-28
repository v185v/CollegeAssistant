package xyz.v185v.collegeassistant.ugc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;

@ExtendWith(MockitoExtension.class)
class UgcModerationServiceTest {

    private static final AuthPrincipal ADMIN = new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());
    private static final AuthPrincipal STUDENT = new AuthPrincipal(7L, "alice", UserRole.STUDENT.name());

    @Mock
    private UgcRepository ugcRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private UgcService ugcService;

    @Test
    void reviewIsTransactionalSoStateAndSynchronousEventShareOneTransaction() throws Exception {
        assertThat(UgcService.class
                .getDeclaredMethod("review", AuthPrincipal.class, Long.class, ReviewUgcRequest.class)
                .isAnnotationPresent(Transactional.class))
                .isTrue();
    }

    @Test
    void feedbackWritesAreTransactional() throws Exception {
        assertThat(UgcService.class
                .getDeclaredMethod("like", AuthPrincipal.class, Long.class)
                .isAnnotationPresent(Transactional.class))
                .isTrue();
        assertThat(UgcService.class
                .getDeclaredMethod("report", AuthPrincipal.class, Long.class, ReportUgcRequest.class)
                .isAnnotationPresent(Transactional.class))
                .isTrue();
    }

    @Test
    void studentReviewReturns40301BeforeRepositoryAccess() {
        ReviewUgcRequest request = new ReviewUgcRequest(ReviewDecision.APPROVE, null);

        assertThatThrownBy(() -> ugcService.review(STUDENT, 11L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.ADMIN_REQUIRED));

        verifyNoInteractions(ugcRepository, eventPublisher);
    }

    @Test
    void approvePendingPostUsesReviewerIdentityAndPublishesApprovedEvent() {
        UgcPostDto pending = post(11L, UgcStatus.PENDING, 0, 0, null);
        UgcPostDto approved = post(11L, UgcStatus.APPROVED, 0, 0, null);
        ReviewUgcRequest request = new ReviewUgcRequest(ReviewDecision.APPROVE, null);
        when(ugcRepository.findById(11L)).thenReturn(Optional.of(pending));
        when(ugcRepository.review(11L, ADMIN.userId(), ReviewDecision.APPROVE, null))
                .thenReturn(Optional.of(approved));

        UgcPostDto result = ugcService.review(ADMIN, 11L, request);

        assertThat(result).isSameAs(approved);
        verify(ugcRepository).review(11L, ADMIN.userId(), ReviewDecision.APPROVE, null);
        verify(eventPublisher).publishEvent(new UgcApprovedEvent(11L));
    }

    @Test
    void rejectPendingPostUsesReviewerIdentityWithoutPublishingEvent() {
        UgcPostDto pending = post(12L, UgcStatus.PENDING, 0, 0, null);
        UgcPostDto rejected = post(12L, UgcStatus.REJECTED, 0, 0, "Contains private data");
        ReviewUgcRequest request = new ReviewUgcRequest(ReviewDecision.REJECT, "Contains private data");
        when(ugcRepository.findById(12L)).thenReturn(Optional.of(pending));
        when(ugcRepository.review(12L, ADMIN.userId(), ReviewDecision.REJECT, "Contains private data"))
                .thenReturn(Optional.of(rejected));

        UgcPostDto result = ugcService.review(ADMIN, 12L, request);

        assertThat(result).isSameAs(rejected);
        verify(ugcRepository).review(12L, ADMIN.userId(), ReviewDecision.REJECT, "Contains private data");
        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectWithoutNonBlankReasonReturns40001BeforeRepositoryAccess() {
        ReviewUgcRequest request = new ReviewUgcRequest(ReviewDecision.REJECT, "   ");

        assertThatThrownBy(() -> ugcService.review(ADMIN, 13L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verifyNoInteractions(ugcRepository, eventPublisher);
    }

    @Test
    void reviewMissingPostReturns40402() {
        ReviewUgcRequest request = new ReviewUgcRequest(ReviewDecision.APPROVE, null);
        when(ugcRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ugcService.review(ADMIN, 404L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.UGC_NOT_FOUND));

        verify(ugcRepository, never()).review(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(eventPublisher);
    }

    @ParameterizedTest
    @EnumSource(value = UgcStatus.class, names = {"APPROVED", "REJECTED", "REMOVED"})
    void reviewNonPendingPostReturns40902(UgcStatus status) {
        ReviewUgcRequest request = new ReviewUgcRequest(ReviewDecision.APPROVE, null);
        when(ugcRepository.findById(14L)).thenReturn(Optional.of(post(14L, status, 0, 0, null)));

        assertThatThrownBy(() -> ugcService.review(ADMIN, 14L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.UGC_ALREADY_REVIEWED));

        verify(ugcRepository, never()).review(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void concurrentReviewLossReturns40902WithoutPublishingEvent() {
        ReviewUgcRequest request = new ReviewUgcRequest(ReviewDecision.APPROVE, null);
        when(ugcRepository.findById(15L)).thenReturn(Optional.of(post(15L, UgcStatus.PENDING, 0, 0, null)));
        when(ugcRepository.review(15L, ADMIN.userId(), ReviewDecision.APPROVE, null))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> ugcService.review(ADMIN, 15L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.UGC_ALREADY_REVIEWED));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void likeUsesAuthenticatedUserAndReturnsCurrentApprovedPost() {
        UgcPostDto liked = post(21L, UgcStatus.APPROVED, 4, 0, null);
        when(ugcRepository.findApprovedById(21L)).thenReturn(Optional.of(liked));

        UgcPostDto result = ugcService.like(STUDENT, 21L);

        assertThat(result).isSameAs(liked);
        verify(ugcRepository).ensureLike(21L, STUDENT.userId());
        verify(ugcRepository).findApprovedById(21L);
    }

    @Test
    void likeMissingOrNonApprovedPostReturns40402() {
        when(ugcRepository.findApprovedById(22L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ugcService.like(STUDENT, 22L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.UGC_NOT_FOUND));

        verify(ugcRepository).ensureLike(22L, STUDENT.userId());
    }

    @Test
    void reportUsesAuthenticatedUserAndStripsReason() {
        ReportUgcRequest request = new ReportUgcRequest("  Contains personal information  ");
        when(ugcRepository.findApprovedById(23L))
                .thenReturn(Optional.of(post(23L, UgcStatus.APPROVED, 0, 1, null)));

        ugcService.report(STUDENT, 23L, request);

        verify(ugcRepository).ensureReport(
                23L, STUDENT.userId(), "Contains personal information");
        verify(ugcRepository).findApprovedById(23L);
    }

    @Test
    void reportMissingOrNonApprovedPostReturns40402() {
        ReportUgcRequest request = new ReportUgcRequest("Spam");
        when(ugcRepository.findApprovedById(24L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ugcService.report(STUDENT, 24L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.UGC_NOT_FOUND));

        verify(ugcRepository).ensureReport(24L, STUDENT.userId(), "Spam");
    }

    @Test
    void listReportsVerifiesPostThenReturnsRepositoryPage() {
        UgcPostDto post = post(25L, UgcStatus.APPROVED, 0, 1, null);
        PageResult<UgcReportDto> page = new PageResult<>(List.of(), 0, 2, 10, 0);
        when(ugcRepository.findById(25L)).thenReturn(Optional.of(post));
        when(ugcRepository.findReports(25L, 2, 10)).thenReturn(page);

        assertThat(ugcService.listReports(25L, 2, 10)).isSameAs(page);
    }

    @Test
    void listReportsRejectsMissingPostBeforeQueryingDetails() {
        when(ugcRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ugcService.listReports(404L, 1, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.UGC_NOT_FOUND));

        verify(ugcRepository, never()).findReports(
                org.mockito.ArgumentMatchers.anyLong(), anyInt(), anyInt());
    }

    private UgcPostDto post(Long id, UgcStatus status, long likes, long reports, String rejectReason) {
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
