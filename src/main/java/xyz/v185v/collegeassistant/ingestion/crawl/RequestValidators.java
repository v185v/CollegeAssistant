package xyz.v185v.collegeassistant.ingestion.crawl;

import java.time.OffsetDateTime;

/** 允许列表中的条件请求元数据；调用方无法注入任意请求头。 */
public record RequestValidators(String etag, OffsetDateTime lastModified) {

    public RequestValidators {
        if (etag != null) {
            etag = etag.strip();
            if (etag.isEmpty()) {
                etag = null;
            } else if (etag.length() > 512 || etag.indexOf('\r') >= 0 || etag.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("ETag is invalid");
            }
        }
    }

    public static RequestValidators none() {
        return new RequestValidators(null, null);
    }
}
