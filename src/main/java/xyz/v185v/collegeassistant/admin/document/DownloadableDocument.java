package xyz.v185v.collegeassistant.admin.document;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;

/** 已打开的文档对象及响应元数据；响应写入器负责关闭该流。 */
public record DownloadableDocument(
        InputStream input,
        String contentType,
        String displayFilename
) {

    public DownloadableDocument {
        if (input == null) {
            throw new IllegalArgumentException("Download input is required");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("Download content type is required");
        }
        if (displayFilename == null || displayFilename.isBlank()) {
            throw new IllegalArgumentException("Download display filename is required");
        }
    }

/** 使用 Spring 的 RFC 5987 编码器，绝不拼接用户控制的请求头文本。 */
    public String contentDisposition() {
        return ContentDisposition.attachment()
                .filename(displayFilename, StandardCharsets.UTF_8)
                .build()
                .toString();
    }
}
