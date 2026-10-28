package xyz.v185v.collegeassistant.admin.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobService;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.admin.source.SourceConnectorType;
import xyz.v185v.collegeassistant.admin.source.SourceScheduleType;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.document.DocumentRecord;
import xyz.v185v.collegeassistant.document.DocumentReferenceType;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.DocumentStatus;
import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.storage.ObjectStorage;

class AdminDocumentServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-25T00:00:00Z");
    private static final AuthPrincipal ADMIN =
            new AuthPrincipal(1L, "admin", UserRole.ADMIN.name());
    private static final AuthPrincipal STUDENT =
            new AuthPrincipal(2L, "student", UserRole.STUDENT.name());

    @Test
    void listMapsRecordsWithoutExposingObjectKeys() {
        Fixture fixture = fixture();
        when(fixture.documents.find(
                1, 20, 10L, DocumentStatus.ACTIVE, "Guide",
                DocumentReferenceType.INTERNAL, null, null))
                .thenReturn(new PageResult<>(List.of(document()), 1, 1, 20, 1));

        PageResult<DocumentDto> result = fixture.service.list(
                ADMIN, 1, 20, 10L, DocumentStatus.ACTIVE, " Guide ",
                DocumentReferenceType.INTERNAL, null, null);

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(20L);
            assertThat(item.originalFilename()).isEqualTo("guide.pdf");
            assertThat(item.downloadable()).isTrue();
        });
    }

    @Test
    void authenticatedStudentCanOpenAnAvailableObjectWithEncodedDisplayMetadata() {
        Fixture fixture = fixture();
        ByteArrayInputStream input = new ByteArrayInputStream("pdf".getBytes());
        when(fixture.documents.findAvailableForContent(20L))
                .thenReturn(Optional.of(document()));
        when(fixture.storage.open("documents/object-20")).thenReturn(input);

        DownloadableDocument download = fixture.service.openDownload(STUDENT, 20L);

        assertThat(download.input()).isSameAs(input);
        assertThat(download.contentType()).isEqualTo("application/pdf");
        assertThat(download.contentDisposition()).contains("filename*=UTF-8''guide.pdf");
    }

    @Test
    void deletionClaimsTheRowBeforeCreatingItsDurableJob() {
        Fixture fixture = fixture();
        AdminJobDto job = job(AdminJobType.DOCUMENT_DELETE);
        when(fixture.documents.findById(20L)).thenReturn(Optional.of(document()));
        when(fixture.sources.lockForMutation(10L)).thenReturn(Optional.of(source("NONE")));
        when(fixture.documents.lockForMutation(20L)).thenReturn(Optional.of(document()));
        when(fixture.documents.markDeleting(20L)).thenReturn(true);
        when(fixture.jobs.create(
                eq(ADMIN), eq(AdminJobType.DOCUMENT_DELETE), eq("DOCUMENT"), eq(20L), any()))
                .thenReturn(job);

        assertThat(fixture.service.requestDeletion(ADMIN, 20L)).isEqualTo(job);

        verify(fixture.documents).markDeleting(20L);
        verify(fixture.jobs).create(
                ADMIN, AdminJobType.DOCUMENT_DELETE, "DOCUMENT", 20L,
                Map.of("documentId", 20L));
    }

    @Test
    void failedDocumentCanStillBeCleanedUpByTheDeletionJob() {
        Fixture fixture = fixture();
        DocumentRecord failed = document(DocumentStatus.FAILED);
        AdminJobDto job = job(AdminJobType.DOCUMENT_DELETE);
        when(fixture.documents.findById(20L)).thenReturn(Optional.of(failed));
        when(fixture.sources.lockForMutation(10L)).thenReturn(Optional.of(source("NONE")));
        when(fixture.documents.lockForMutation(20L)).thenReturn(Optional.of(failed));
        when(fixture.documents.markDeleting(20L)).thenReturn(true);
        when(fixture.jobs.create(
                eq(ADMIN), eq(AdminJobType.DOCUMENT_DELETE), eq("DOCUMENT"), eq(20L), any()))
                .thenReturn(job);

        assertThat(fixture.service.requestDeletion(ADMIN, 20L)).isEqualTo(job);
    }

    @Test
    void sourceDeletionStateRejectsReindexBeforeCreatingAJob() {
        Fixture fixture = fixture();
        when(fixture.documents.findById(20L)).thenReturn(Optional.of(document()));
        when(fixture.sources.lockForMutation(10L))
                .thenReturn(Optional.of(source("DELETING")));

        assertThatThrownBy(() -> fixture.service.reindex(ADMIN, 20L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("source");

        verify(fixture.documents, never()).lockForMutation(any());
        verifyNoInteractions(fixture.jobs);
    }

    @Test
    void studentCannotBrowseAdministrativeInventoryAtTheServiceBoundary() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service.list(
                STUDENT, 1, 20, null, null, null, null, null, null))
                .isInstanceOf(BusinessException.class);

        verify(fixture.documents, never()).find(
                any(Integer.class), any(Integer.class), any(), any(), any(), any(), any(), any());
    }

    private Fixture fixture() {
        DocumentRepository documents = mock(DocumentRepository.class);
        SourceRepository sources = mock(SourceRepository.class);
        AdminJobService jobs = mock(AdminJobService.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        return new Fixture(
                documents, sources, jobs, storage,
                new AdminDocumentService(documents, sources, jobs, storage));
    }

    private DocumentRecord document() {
        return document(DocumentStatus.ACTIVE);
    }

    private DocumentRecord document(DocumentStatus status) {
        UUID locator = UUID.fromString("9f735650-c80a-4ce9-a788-03ebfed144ac");
        return new DocumentRecord(
                20L, 10L, "Guide", "internal://document/" + locator, "Registrar",
                NOW, "hash", "documents/object-20", NOW, DocumentReferenceType.INTERNAL,
                "https://example.edu/guide", locator, "guide.pdf", "guide.pdf",
                status, null, null, NOW, NOW,
                "GENERAL", "GENERAL", "GENERAL", "GENERAL");
    }

    private SourceRecord source(String deletionStatus) {
        return new SourceRecord(
                10L, "Registrar", null, "OFFICIAL", true,
                SourceConnectorType.LOCAL_UPLOAD, deletionStatus, SourceScheduleType.MANUAL,
                "Asia/Shanghai", null, true, 0, 100, 1, List.of(), List.of(),
                null, null, 1L, NOW, NOW);
    }

    private AdminJobDto job(AdminJobType type) {
        return new AdminJobDto(
                70L, type, AdminJobStatus.QUEUED, "DOCUMENT", 20L, 1L,
                0, null, null, null, null, null, null, null, null,
                Map.of("documentId", 20L), NOW, NOW, List.of());
    }

    private record Fixture(
            DocumentRepository documents,
            SourceRepository sources,
            AdminJobService jobs,
            ObjectStorage storage,
            AdminDocumentService service
    ) {
    }
}
