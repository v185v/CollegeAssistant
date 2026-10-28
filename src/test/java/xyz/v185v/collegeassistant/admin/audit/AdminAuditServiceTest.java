package xyz.v185v.collegeassistant.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

class AdminAuditServiceTest {

    private final AdminAuditRedactor redactor = new AdminAuditRedactor();

    @Test
    void redactorDropsSensitiveKeysAndAbsolutePathsAtEveryNestingLevel() {
        AdminAuditEvent event = event(Map.of(
                "enabled", true,
                "Password", "password-value",
                "nested", Map.of(
                        "apiKey", "api-key-value",
                        "directory", "/var/private/report.csv",
                        "safe", "visible"),
                "items", java.util.Arrays.asList(
                        "safe-item",
                        null,
                        "C:\\private\\backup.dump",
                        Map.of("objectKey", "documents/opaque-key", "count", 2)),
                "networkToken", "token-value",
                "databaseSecret", "secret-value"));

        AdminAuditEvent redacted = redactor.redact(event);

        assertThat(redacted.summary()).containsEntry("enabled", true)
                .doesNotContainKeys("Password", "networkToken", "databaseSecret");
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) redacted.summary().get("nested");
        assertThat(nested).containsEntry("safe", "visible")
                .doesNotContainKeys("apiKey", "directory");
        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) redacted.summary().get("items");
        assertThat(items).containsExactly("safe-item", null, Map.of("count", 2));
        assertThat(redacted.summary().toString())
                .doesNotContain("password-value", "api-key-value", "token-value", "secret-value")
                .doesNotContain("/var/private/report.csv", "C:\\private\\backup.dump")
                .doesNotContain("documents/opaque-key");
    }

    @Test
    void redactorRejectsOversizedAllowlistedSummaryValues() {
        assertThatThrownBy(() -> redactor.redact(event(Map.of("note", "x".repeat(513)))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void successfulDomainMutationWritesSucceededAuditInRequiredTransaction() {
        AdminAuditWriter writer = mock(AdminAuditWriter.class);
        AdminAuditFailureWriter failureWriter = mock(AdminAuditFailureWriter.class);
        AdminAuditService service = service(writer, failureWriter);
        ArgumentCaptor<AdminAuditEvent> event = ArgumentCaptor.forClass(AdminAuditEvent.class);

        String result = service.audited(event(Map.of("enabled", false)), () -> "updated");

        assertThat(result).isEqualTo("updated");
        verify(writer).writeInCurrentTransaction(event.capture());
        verifyNoInteractions(failureWriter);
        assertThat(event.getValue().result()).isEqualTo(AdminAuditResult.SUCCEEDED);
        assertThat(event.getValue().errorCode()).isNull();
    }

    @Test
    void failedDomainMutationWritesFailureAuditWithBusinessErrorCode() {
        AdminAuditWriter writer = mock(AdminAuditWriter.class);
        AdminAuditFailureWriter failureWriter = mock(AdminAuditFailureWriter.class);
        AdminAuditService service = service(writer, failureWriter);
        BusinessException failure = new BusinessException(ErrorCode.CONFLICT, "domain failed");
        ArgumentCaptor<AdminAuditEvent> event = ArgumentCaptor.forClass(AdminAuditEvent.class);

        assertThatThrownBy(() -> service.audited(event(Map.of("enabled", false)), () -> {
            throw failure;
        })).isSameAs(failure);

        verifyNoInteractions(writer);
        verify(failureWriter).writeFailure(event.capture());
        assertThat(event.getValue().result()).isEqualTo(AdminAuditResult.FAILED);
        assertThat(event.getValue().errorCode()).isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void successfulAuditCanDeriveItsTargetFromTheCreatedResult() {
        AdminAuditWriter writer = mock(AdminAuditWriter.class);
        AdminAuditFailureWriter failureWriter = mock(AdminAuditFailureWriter.class);
        AdminAuditService service = service(writer, failureWriter);
        AdminAuditEvent requested = event(Map.of()).withTargetId(null);
        ArgumentCaptor<AdminAuditEvent> written = ArgumentCaptor.forClass(AdminAuditEvent.class);

        String result = service.auditedResult(
                requested,
                () -> "created",
                ignored -> requested.withTargetId(73L));

        assertThat(result).isEqualTo("created");
        verify(writer).writeInCurrentTransaction(written.capture());
        assertThat(written.getValue().targetId()).isEqualTo(73L);
    }

    @Test
    void oneAtomicMutationCanEmitOneTerminalAuditPerChangedRow() {
        AdminAuditWriter writer = mock(AdminAuditWriter.class);
        AdminAuditFailureWriter failureWriter = mock(AdminAuditFailureWriter.class);
        AdminAuditService service = service(writer, failureWriter);
        AdminAuditEvent requested = event(Map.of()).withTargetId(null);
        ArgumentCaptor<AdminAuditEvent> written = ArgumentCaptor.forClass(AdminAuditEvent.class);

        List<Long> expired = service.auditedMany(
                requested,
                () -> List.of(81L, 82L),
                ids -> ids.stream().map(id -> requested.withTargetId(id)).toList());

        assertThat(expired).containsExactly(81L, 82L);
        verify(writer, org.mockito.Mockito.times(2))
                .writeInCurrentTransaction(written.capture());
        assertThat(written.getAllValues()).extracting(AdminAuditEvent::targetId)
                .containsExactly(81L, 82L);
    }

    @Test
    void falseMutationOutcomeDoesNotWriteASuccessAudit() {
        AdminAuditWriter writer = mock(AdminAuditWriter.class);
        AdminAuditFailureWriter failureWriter = mock(AdminAuditFailureWriter.class);
        AdminAuditService service = service(writer, failureWriter);

        boolean changed = service.auditedOutcome(
                event(Map.of()).succeeded(), () -> false, Boolean.TRUE::equals);

        assertThat(changed).isFalse();
        verifyNoInteractions(writer, failureWriter);
    }

    private AdminAuditService service(
            AdminAuditWriter writer,
            AdminAuditFailureWriter failureWriter
    ) {
        return new AdminAuditService(
                new TransactionTemplate(new TestTransactionManager()), writer, failureWriter, redactor);
    }

    private AdminAuditEvent event(Map<String, Object> summary) {
        return AdminAuditEvent.requested(
                7L, "SOURCE_UPDATED", "SOURCE", 42L, summary, "127.0.0.1");
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
