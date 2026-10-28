package xyz.v185v.collegeassistant.auth;

import java.time.OffsetDateTime;

/**
 * 用户账号持久化模型。
 *
 * @param id           用户编号
 * @param username     登录用户名
 * @param passwordHash BCrypt 密码摘要
 * @param displayName  展示名称
 * @param role         用户角色
 * @param createdAt    创建时间
 * @param enabled      是否允许认证
 * @param tokenVersion 当前令牌版本
 */
public record UserAccount(
        Long id,
        String username,
        String passwordHash,
        String displayName,
        UserRole role,
        OffsetDateTime createdAt,
        boolean enabled,
        long tokenVersion
) {

    public UserAccount(
            Long id,
            String username,
            String passwordHash,
            String displayName,
            UserRole role,
            OffsetDateTime createdAt
    ) {
        this(id, username, passwordHash, displayName, role, createdAt, true, 0);
    }
}
