package xyz.v185v.collegeassistant.auth.dto;

import jakarta.validation.constraints.NotBlank;
import xyz.v185v.collegeassistant.common.validation.Utf8ByteLength;

/**
 * 本地账号登录请求。
 *
 * @param username 用户名
 * @param password 明文密码，仅用于本次认证
 */
public record LoginRequest(
        @NotBlank
        String username,

        @NotBlank
        @Utf8ByteLength(max = 72)
        String password
) {
}
