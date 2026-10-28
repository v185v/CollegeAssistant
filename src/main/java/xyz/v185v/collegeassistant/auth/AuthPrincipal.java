package xyz.v185v.collegeassistant.auth;

/**
 * 通过 JWT 认证后写入 Spring Security 上下文的当前用户身份。
 *
 * @param userId   用户编号
 * @param username 用户名
 * @param role     角色名称，不包含 {@code ROLE_} 前缀
 */
public record AuthPrincipal(
        Long userId,
        String username,
        String role
) {
}
