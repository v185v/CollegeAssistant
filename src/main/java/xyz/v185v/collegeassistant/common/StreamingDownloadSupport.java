package xyz.v185v.collegeassistant.common;

import java.io.InputStream;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 文档和备份下载共用的安全关闭响应流工具。 */
@Component
public class StreamingDownloadSupport {

    public StreamingResponseBody stream(InputStream source) {
        Objects.requireNonNull(source, "source");
        return output -> {
            try (InputStream owned = source) {
                owned.transferTo(output);
            }
        };
    }
}
