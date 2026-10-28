package xyz.v185v.collegeassistant.admin.backup;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 由服务器持有的 PostgreSQL 备份配置；不接受请求传入的任何值。 */
@ConfigurationProperties(prefix = "app.backup")
public record BackupProperties(
        Path root,
        Path pgDump,
        Path pgRestore,
        String host,
        int port,
        String databaseName,
        String username,
        String password,
        Duration dumpTimeout,
        Duration validationTimeout,
        Duration killGrace,
        long outputLimitBytes,
        long minFreeBytes,
        long totalQuotaBytes,
        int defaultRetentionDays,
        Duration retentionInterval
) {

    public BackupProperties {
        if (root == null || pgDump == null || pgRestore == null) {
            throw new IllegalArgumentException("Backup paths are required");
        }
        host = required(host, "Backup database host");
        databaseName = required(databaseName, "Backup database name");
        username = required(username, "Backup database username");
        if (password == null) {
            throw new IllegalArgumentException("Backup database password is required");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Backup database port is invalid");
        }
        requirePositive(dumpTimeout, "Backup dump timeout");
        requirePositive(validationTimeout, "Backup validation timeout");
        requirePositive(killGrace, "Backup kill grace");
        if (outputLimitBytes < 1 || minFreeBytes < 0 || totalQuotaBytes < 1) {
            throw new IllegalArgumentException("Backup byte limits are invalid");
        }
        if (defaultRetentionDays < 1 || defaultRetentionDays > 3_650) {
            throw new IllegalArgumentException("Backup retention is invalid");
        }
        requirePositive(retentionInterval, "Backup retention interval");
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.strip();
    }

    private static void requirePositive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }

    @Override
    public String toString() {
        return "BackupProperties[host=" + host
                + ", port=" + port
                + ", databaseName=" + databaseName
                + ", username=" + username
                + ", password=<redacted>"
                + ", dumpTimeout=" + dumpTimeout
                + ", validationTimeout=" + validationTimeout
                + ", outputLimitBytes=" + outputLimitBytes
                + ", totalQuotaBytes=" + totalQuotaBytes
                + "]";
    }
}
