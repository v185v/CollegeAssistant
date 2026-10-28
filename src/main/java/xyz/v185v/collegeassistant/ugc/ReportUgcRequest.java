package xyz.v185v.collegeassistant.ugc;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 举报已公开 UGC 的请求。
 *
 * @param reason 举报原因
 */
public record ReportUgcRequest(
        @NotBlank @Size(max = 500) String reason
) {
}
