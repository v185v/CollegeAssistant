package xyz.v185v.collegeassistant.admin.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.admin.job.AdminJobLease;

/** 使用固定参数和最小子进程环境启动 pg_dump 与 pg_restore。 */
@Component
public class PgBackupProcess {

    private final BackupProperties properties;
    private final BackupFileStore fileStore;
    private final BackupProcessSupervisor supervisor;

    public PgBackupProcess(
            BackupProperties properties,
            BackupFileStore fileStore,
            BackupProcessSupervisor supervisor
    ) {
        this.properties = properties;
        this.fileStore = fileStore;
        this.supervisor = supervisor;
    }

    public boolean dumpAvailable() {
        return executableAvailable(properties.pgDump());
    }

    public boolean restoreAvailable() {
        return executableAvailable(properties.pgRestore());
    }

/** 将转储写入唯一临时路径；发布仍由服务负责。 */
    public Path dump(AdminJobLease lease) {
        Path partial = fileStore.allocateAttempt(lease);
        try {
            ProcessBuilder builder = configuredBuilder(List.of(
                    properties.pgDump().toString(),
                    "--format=custom",
                    "--file=" + partial,
                    properties.databaseName()), true);
            SupervisedProcessResult result = supervisor.run(
                    builder, properties.dumpTimeout(), properties.outputLimitBytes());
            if (result.exitCode() != 0) {
                throw new BackupProcessException("Database backup process failed");
            }
            return partial;
        } catch (RuntimeException failure) {
            deletePartialAfterSupervisor(partial, failure);
            throw failure;
        }
    }

/** 仅使用固定 pg_restore 参数校验已归属本服务的自定义归档。 */
    public void validate(String objectKey) {
        Path archive = fileStore.resolveArtifact(objectKey);
        ProcessBuilder builder = configuredBuilder(
                List.of(properties.pgRestore().toString(), "--list", archive.toString()),
                false);
        SupervisedProcessResult result = supervisor.run(
                builder, properties.validationTimeout(), properties.outputLimitBytes());
        if (result.exitCode() != 0) {
            throw new BackupProcessException("Database backup validation failed");
        }
    }

    private ProcessBuilder configuredBuilder(
            List<String> command,
            boolean includeDatabaseCredentials
    ) {
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.putAll(minimalOsEnvironment());
        if (includeDatabaseCredentials) {
            environment.put("PGHOST", properties.host());
            environment.put("PGPORT", Integer.toString(properties.port()));
            environment.put("PGUSER", properties.username());
            environment.put("PGPASSWORD", properties.password());
        }
        return builder;
    }

    private Map<String, String> minimalOsEnvironment() {
        Map<String, String> allowed = new LinkedHashMap<>();
        copyIfPresent(allowed, "SystemRoot");
        copyIfPresent(allowed, "WINDIR");
        allowed.put("LANG", "C");
        allowed.put("LC_ALL", "C");
        return allowed;
    }

    private void copyIfPresent(Map<String, String> target, String name) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            target.put(name, value);
        }
    }

    private boolean executableAvailable(Path executable) {
        try {
            return executable != null
                    && executable.isAbsolute()
                    && Files.isRegularFile(executable)
                    && Files.isExecutable(executable);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void deletePartialAfterSupervisor(Path partial, RuntimeException failure) {
        try {
            Files.deleteIfExists(partial);
        } catch (IOException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
