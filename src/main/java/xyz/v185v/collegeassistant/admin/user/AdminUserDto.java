package xyz.v185v.collegeassistant.admin.user;

import java.time.OffsetDateTime;

import xyz.v185v.collegeassistant.auth.UserRole;

/** 不含凭据的管理员用户账户视图。 */
public record AdminUserDto(
        Long id,
        String username,
        String displayName,
        UserRole role,
        boolean enabled,
        long tokenVersion,
        OffsetDateTime createdAt
) {
}
