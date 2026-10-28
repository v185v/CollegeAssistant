package xyz.v185v.collegeassistant.auth.dto;

import xyz.v185v.collegeassistant.auth.UserRole;

/**
 * 返回给客户端的非敏感用户摘要。
 *
 * @param id          用户编号
 * @param username    用户名
 * @param displayName 展示名称
 * @param role        用户角色
 */
public record UserSummaryDto(
        Long id,
        String username,
        String displayName,
        UserRole role
) {
}
