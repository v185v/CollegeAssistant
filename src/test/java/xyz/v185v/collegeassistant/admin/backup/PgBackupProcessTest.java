package xyz.v185v.collegeassistant.admin.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import xyz.v185v.collegeassistant.admin.job.AdminJobLease;

class PgBackupProcessTest {

    @TempDir
    Path tempDir;

    private BackupFileStore fileStore;
    private BackupProcessSupervisor supervisor;
    private BackupProperties properties;
    private PgBackupProcess process;

    @BeforeEach
    void setUp() {
        fileStore = mock(BackupFileStore.class);
        supervisor = mock(BackupProcessSupervisor.class);
        properties = properties();
        process = new PgBackupProcess(properties, fileStore, supervisor);
    }

    @Test
    void dumpUsesOnlyFixedArgumentsAndAClearedAllowlistedEnvironment() {
        AdminJobLease lease = lease();
        Path partial = tempDir.resolve("server-generated.partial");
        when(fileStore.allocateAttempt(lease)).thenReturn(partial);
        when(supervisor.run(any(), eq(properties.dumpTimeout()),
                eq(properties.outputLimitBytes())))
                .thenReturn(success());

        Path result = process.dump(lease);

        assertThat(result).isEqualTo(partial);
        ArgumentCaptor<ProcessBuilder> builder = ArgumentCaptor.forClass(ProcessBuilder.class);
        verify(supervisor).run(builder.capture(), eq(properties.dumpTimeout()),
                eq(properties.outputLimitBytes()));
        assertThat(builder.getValue().command()).containsExactly(
                properties.pgDump().toString(),
                "--format=custom",
                "--file=" + partial,
                properties.databaseName());
        assertThat(builder.getValue().environment()).containsEntry("PGHOST", "db.internal")
                .containsEntry("PGPORT", "55432")
                .containsEntry("PGUSER", "backup_user")
                .containsEntry("PGPASSWORD", "child-only-password")
                .containsEntry("LANG", "C")
                .containsEntry("LC_ALL", "C");
        assertThat(builder.getValue().environment().keySet()).isSubsetOf(Set.of(
                "SystemRoot", "WINDIR", "LANG", "LC_ALL",
                "PGHOST", "PGPORT", "PGUSER", "PGPASSWORD"));
        assertThat(builder.getValue().environment()).doesNotContainKeys(
                "PATH", "HOME", "USERPROFILE", "HTTP_PROXY", "AWS_SECRET_ACCESS_KEY");
    }

    @Test
    void timeoutDeletesThePartialOnlyAfterTheSupervisorReturns() throws Exception {
        AdminJobLease lease = lease();
        Path partial = tempDir.resolve("timed-out.partial");
        Files.writeString(partial, "partial");
        when(fileStore.allocateAttempt(lease)).thenReturn(partial);
        when(supervisor.run(any(), any(), eq(properties.outputLimitBytes())))
                .thenThrow(new BackupProcessTimeoutException());

        assertThatThrownBy(() -> process.dump(lease))
                .isInstanceOf(BackupProcessTimeoutException.class);
        assertThat(partial).doesNotExist();
    }

    @Test
    void validationUsesOnlyPgRestoreListAndTheResolvedServerPath() {
        Path archive = tempDir.resolve("immutable.dump");
        when(fileStore.resolveArtifact("opaque.dump")).thenReturn(archive);
        when(supervisor.run(any(), eq(properties.validationTimeout()),
                eq(properties.outputLimitBytes())))
                .thenReturn(success());

        process.validate("opaque.dump");

        ArgumentCaptor<ProcessBuilder> builder = ArgumentCaptor.forClass(ProcessBuilder.class);
        verify(supervisor).run(builder.capture(), eq(properties.validationTimeout()),
                eq(properties.outputLimitBytes()));
        assertThat(builder.getValue().command()).containsExactly(
                properties.pgRestore().toString(), "--list", archive.toString());
        assertThat(builder.getValue().environment()).doesNotContainKeys(
                "PGHOST", "PGPORT", "PGUSER", "PGPASSWORD");
        assertThat(builder.getValue().environment().keySet()).isSubsetOf(Set.of(
                "SystemRoot", "WINDIR", "LANG", "LC_ALL"));
    }

    @Test
    void nonZeroDumpExitDeletesPartialAndReturnsOnlyASanitizedFailure() throws Exception {
        AdminJobLease lease = lease();
        Path partial = tempDir.resolve("failed.partial");
        Files.writeString(partial, "partial");
        when(fileStore.allocateAttempt(lease)).thenReturn(partial);
        when(supervisor.run(any(), any(), eq(properties.outputLimitBytes())))
                .thenReturn(new SupervisedProcessResult(
                        2,
                        new BoundedProcessOutput(new byte[0]),
                        new BoundedProcessOutput("credential=secret".getBytes())));

        assertThatThrownBy(() -> process.dump(lease))
                .isInstanceOf(BackupProcessException.class)
                .hasMessageNotContaining("credential")
                .hasMessageNotContaining("secret");
        assertThat(partial).doesNotExist();
    }

    @Test
    void configurationStringNeverExposesTheDatabasePassword() {
        assertThat(properties.toString())
                .doesNotContain("child-only-password")
                .contains("password=<redacted>");
    }

    private BackupProperties properties() {
        return new BackupProperties(
                tempDir.resolve("backups"),
                tempDir.resolve("trusted-pg-dump"),
                tempDir.resolve("trusted-pg-restore"),
                "db.internal",
                55432,
                "college",
                "backup_user",
                "child-only-password",
                Duration.ofSeconds(3),
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                1024,
                256,
                10_000,
                7,
                Duration.ofHours(1));
    }

    private AdminJobLease lease() {
        return new AdminJobLease(41L, 2, "worker", UUID.randomUUID());
    }

    private SupervisedProcessResult success() {
        return new SupervisedProcessResult(
                0,
                new BoundedProcessOutput(new byte[0]),
                new BoundedProcessOutput(new byte[0]));
    }
}
