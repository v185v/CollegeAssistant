package xyz.v185v.collegeassistant.auth.dto;

/**
 * 注册或登录成功后的令牌响应。
 *
 * @param tokenType   令牌类型，当前固定为 Bearer
 * @param accessToken JWT 访问令牌
 * @param expiresIn   有效秒数
 * @param user        当前用户摘要
 */
public record AuthTokenResponse(
        String tokenType,
        String accessToken,
        long expiresIn,
        UserSummaryDto user
) {
}
