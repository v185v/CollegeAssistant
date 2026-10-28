package xyz.v185v.collegeassistant.admin.backup;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;

/** 已打开的备份流及安全响应元数据；响应写入器负责该流。 */
public record BackupDownload(
        InputStream input,
        String displayFilename,
        long sizeBytes
) {

    public BackupDownload {
        if (input == null || displayFilename == null || displayFilename.isBlank()
                || sizeBytes < 0) {
            throw new IllegalArgumentException("Backup download metadata is invalid");
        }
    }

    public String contentDisposition() {
        return ContentDisposition.attachment()
                .filename(displayFilename, StandardCharsets.UTF_8)
                .build()
                .toString();
    }
}
