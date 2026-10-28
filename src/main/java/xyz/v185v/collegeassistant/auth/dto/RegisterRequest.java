package xyz.v185v.collegeassistant.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import xyz.v185v.collegeassistant.common.validation.Utf8ByteLength;

/**
 * 本地学生账号注册请求。
 *
 * @param username    登录用户名
 * @param password    明文密码，仅用于生成密码摘要
 * @param displayName 展示名称
 */
public record RegisterRequest(
        @NotBlank
        @Size(min = 3, max = 32)
        @Pattern(regexp = "[A-Za-z0-9_-]+")
        String username,

        @NotBlank
        @Size(min = 8)
        @Utf8ByteLength(max = 72)
        String password,

        @Size(min = 1, max = 32)
        String displayName
) {
}
