package xyz.v185v.collegeassistant.ugc;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * UGC 审核请求。
 *
 * @param decision 审核决定
 * @param reason   拒绝原因；拒绝时由服务层要求必填
 */
public record ReviewUgcRequest(
        @NotNull ReviewDecision decision,
        @Size(max = 500) String reason
) {
}
