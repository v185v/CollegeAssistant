package xyz.v185v.collegeassistant.ugc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import xyz.v185v.collegeassistant.admin.audit.AdminAuditEvent;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditService;
import xyz.v185v.collegeassistant.admin.model.EmbeddingMaintenanceGuard;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;

@ExtendWith(MockitoExtension.class)
class UgcServiceTest {

    private static final AuthPrincipal STUDENT = new AuthPrincipal(7L, "alice", UserRole.STUDENT.name());
    private static final AuthPrincipal ADMIN = new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());

    @Mock
    private UgcRepository ugcRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private EmbeddingChunkRepository embeddingChunkRepository;

    @Mock
    private AdminAuditService auditService;

    @InjectMocks
    private UgcService ugcService;

    @BeforeEach
    void passThroughAuditBoundary() {
        lenient().when(auditService.audited(any(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
    }

    @Test
    void createUsesPrincipalAuthorId() {
        CreateUgcPostRequest request = request();
        UgcPostDto saved = post(11L, STUDENT.userId(), UgcStatus.PENDING);
        when(ugcRepository.create(STUDENT.userId(), request)).thenReturn(saved);

        UgcPostDto result = ugcService.create(STUDENT, request);

        assertThat(result).isSameAs(saved);
        verify(ugcRepository).create(STUDENT.userId(), request);
    }

    @Test
    void studentPublicListForcesApprovedStatus() {
        PageResult<UgcPostDto> expected = emptyPage();
        when(ugcRepository.findPage(null, UgcStatus.APPROVED, "course", 1, 20)).thenReturn(expected);

        PageResult<UgcPostDto> result = ugcService.list(
                STUDENT, 1, 20, false, UgcStatus.REJECTED, "course");

        assertThat(result).isSameAs(expected);
        verify(ugcRepository).findPage(null, UgcStatus.APPROVED, "course", 1, 20);
    }

    @Test
    void mineListRestrictsToCurrentAuthorAndUsesRequestedStatus() {
        PageResult<UgcPostDto> expected = emptyPage();
        when(ugcRepository.findPage(STUDENT.userId(), UgcStatus.REJECTED, null, 2, 10)).thenReturn(expected);

        PageResult<UgcPostDto> result = ugcService.list(
                STUDENT, 2, 10, true, UgcStatus.REJECTED, null);

        assertThat(result).isSameAs(expected);
        verify(ugcRepository).findPage(STUDENT.userId(), UgcStatus.REJECTED, null, 2, 10);
    }

    @Test
    void mineListWithoutStatusIncludesAllCurrentAuthorsStatuses() {
        PageResult<UgcPostDto> expected = emptyPage();
        when(ugcRepository.findPage(STUDENT.userId(), null, "course", 1, 20)).thenReturn(expected);

        PageResult<UgcPostDto> result = ugcService.list(
                STUDENT, 1, 20, true, null, "course");

        assertThat(result).isSameAs(expected);
        verify(ugcRepository).findPage(STUDENT.userId(), null, "course", 1, 20);
    }

    @Test
    void adminPublicListUsesRequestedStatusFilter() {
        PageResult<UgcPostDto> expected = emptyPage();
        when(ugcRepository.findPage(null, UgcStatus.REJECTED, null, 1, 20)).thenReturn(expected);

        PageResult<UgcPostDto> result = ugcService.list(
                ADMIN, 1, 20, false, UgcStatus.REJECTED, null);

        assertThat(result).isSameAs(expected);
        verify(ugcRepository).findPage(null, UgcStatus.REJECTED, null, 1, 20);
    }

    @Test
    void hiddenOtherUsersPendingDetailReturns40402() {
        when(ugcRepository.findById(99L)).thenReturn(Optional.of(post(99L, 8L, UgcStatus.PENDING)));

        assertThatThrownBy(() -> ugcService.get(STUDENT, 99L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.UGC_NOT_FOUND));
    }

    @Test
    void ownerCanSeeOwnNonApprovedDetail() {
        UgcPostDto rejected = post(12L, STUDENT.userId(), UgcStatus.REJECTED);
        when(ugcRepository.findById(12L)).thenReturn(Optional.of(rejected));

        assertThat(ugcService.get(STUDENT, 12L)).isSameAs(rejected);
    }

    @Test
    void adminCanSeeAnyDetail() {
        UgcPostDto pending = post(13L, 8L, UgcStatus.PENDING);
        when(ugcRepository.findById(13L)).thenReturn(Optional.of(pending));

        assertThat(ugcService.get(ADMIN, 13L)).isSameAs(pending);
    }

    @Test
    void approvedDetailIsPublicToAuthenticatedStudents() {
        UgcPostDto approved = post(14L, 8L, UgcStatus.APPROVED);
        when(ugcRepository.findById(14L)).thenReturn(Optional.of(approved));

        assertThat(ugcService.get(STUDENT, 14L)).isSameAs(approved);
    }

    @Test
    void missingDetailReturns40402() {
        when(ugcRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ugcService.get(STUDENT, 404L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.UGC_NOT_FOUND));
    }

    @Test
    void reviewSuccessUsesAnAllowlistedAuditSummary() {
        ReviewUgcRequest request = new ReviewUgcRequest(ReviewDecision.APPROVE, null);
        UgcPostDto pending = post(20L, STUDENT.userId(), UgcStatus.PENDING);
        UgcPostDto approved = post(20L, STUDENT.userId(), UgcStatus.APPROVED);
        when(ugcRepository.findById(20L)).thenReturn(Optional.of(pending));
        when(ugcRepository.review(20L, ADMIN.userId(), ReviewDecision.APPROVE, null))
                .thenReturn(Optional.of(approved));

        assertThat(ugcService.review(ADMIN, 20L, request)).isSameAs(approved);

        ArgumentCaptor<AdminAuditEvent> event = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(auditService).audited(event.capture(), any());
        assertThat(event.getValue().action()).isEqualTo("UGC_REVIEWED");
        assertThat(event.getValue().targetType()).isEqualTo("UGC_POST");
        assertThat(event.getValue().targetId()).isEqualTo(20L);
        assertThat(event.getValue().summary())
                .containsExactlyEntriesOf(java.util.Map.of("decision", "APPROVE"));
    }

    @Test
    void moderationMutationChecksTheEmbeddingMaintenanceFenceBeforeChangingStatus() {
        EmbeddingMaintenanceGuard guard = mock(EmbeddingMaintenanceGuard.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        when(audit.audited(any(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        UgcService service = new UgcService(
                ugcRepository, eventPublisher, embeddingChunkRepository, audit);
        service.setEmbeddingMaintenanceGuard(guard);
        UgcPostDto pending = post(62L, STUDENT.userId(), UgcStatus.PENDING);
        UgcPostDto approved = post(62L, STUDENT.userId(), UgcStatus.APPROVED);
        when(ugcRepository.findById(62L)).thenReturn(Optional.of(pending));
        when(ugcRepository.review(62L, ADMIN.userId(), ReviewDecision.APPROVE, null))
                .thenReturn(Optional.of(approved));

        service.review(ADMIN, 62L, new ReviewUgcRequest(ReviewDecision.APPROVE, null));

        verify(guard).requireWritesAllowed();
    }

    @Test
    void removalSetsRemovedAndDeletesEveryEmbeddingVersionInAuditBoundary() {
        UgcPostDto approved = post(30L, STUDENT.userId(), UgcStatus.APPROVED);
        UgcPostDto removed = post(30L, STUDENT.userId(), UgcStatus.REMOVED);
        when(ugcRepository.findById(30L)).thenReturn(Optional.of(approved));
        when(ugcRepository.remove(30L)).thenReturn(Optional.of(removed));

        UgcPostDto result = ugcService.remove(ADMIN, 30L);

        assertThat(result).isSameAs(removed);
        verify(embeddingChunkRepository).deleteAllForReference("UGC", 30L);
        ArgumentCaptor<AdminAuditEvent> event = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(auditService).audited(event.capture(), any());
        assertThat(event.getValue().action()).isEqualTo("UGC_REMOVED");
        assertThat(event.getValue().summary()).isEmpty();
    }

    @Test
    void missingRemovalStillTraversesFailureAuditBoundary() {
        when(ugcRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ugcService.remove(ADMIN, 404L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.UGC_NOT_FOUND));

        verify(auditService).audited(any(), any());
        verify(embeddingChunkRepository, never()).deleteAllForReference(any(), any());
    }

    @Test
    void resolvesOpenReportWithReviewerAndFinalStatus() {
        UgcReportDto resolved = report(91L, 30L, UgcReportStatus.RESOLVED);
        when(ugcRepository.resolveReport(
                30L, 91L, ADMIN.userId(), UgcReportStatus.RESOLVED))
                .thenReturn(Optional.of(resolved));

        UgcReportDto result = ugcService.resolveReport(
                ADMIN, 30L, 91L, UgcReportStatus.RESOLVED);

        assertThat(result).isSameAs(resolved);
        ArgumentCaptor<AdminAuditEvent> event = ArgumentCaptor.forClass(AdminAuditEvent.class);
        verify(auditService).audited(event.capture(), any());
        assertThat(event.getValue().action()).isEqualTo("UGC_REPORT_RESOLVED");
        assertThat(event.getValue().targetType()).isEqualTo("UGC_REPORT");
        assertThat(event.getValue().targetId()).isEqualTo(91L);
        assertThat(event.getValue().summary())
                .containsExactlyEntriesOf(java.util.Map.of("status", "RESOLVED", "postId", 30L));
    }

    @Test
    void reportResolutionRejectsOpenAsATargetInsideAuditBoundary() {
        assertThatThrownBy(() -> ugcService.resolveReport(
                ADMIN, 30L, 91L, UgcReportStatus.OPEN))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verify(auditService).audited(any(), any());
        verify(ugcRepository, never()).resolveReport(any(), any(), any(), any());
    }

    private PageResult<UgcPostDto> emptyPage() {
        return new PageResult<>(List.of(), 0, 1, 20, 0);
    }

    private CreateUgcPostRequest request() {
        return new CreateUgcPostRequest(
                "How I selected CS courses",
                "This is a detailed course selection experience for new students.",
                List.of("course"),
                "CS101",
                "West Campus");
    }

    private UgcPostDto post(Long id, Long authorId, UgcStatus status) {
        return new UgcPostDto(
                id,
                authorId,
                "Author",
                "How I selected CS courses",
                "This is a detailed course selection experience for new students.",
                List.of("course"),
                "CS101",
                "West Campus",
                status,
                0,
                0,
                status == UgcStatus.REJECTED ? "Needs more detail" : null,
                OffsetDateTime.parse("2026-07-13T10:15:30+08:00"),
                status == UgcStatus.PENDING ? null : OffsetDateTime.parse("2026-07-13T11:15:30+08:00"));
    }

    private UgcReportDto report(Long id, Long postId, UgcReportStatus status) {
        return new UgcReportDto(
                id,
                postId,
                STUDENT.userId(),
                "Alice",
                "Contains personal information",
                status,
                OffsetDateTime.parse("2026-07-13T12:15:30+08:00"));
    }
}
