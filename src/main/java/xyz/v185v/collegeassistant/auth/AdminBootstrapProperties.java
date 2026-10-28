package xyz.v185v.collegeassistant.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 部署阶段一次性建立首个管理员所需的配置。
 *
 * @param username 要创建或提升的用户名
 * @param password 要替换为的明文密码，仅在启动期间交给密码编码器
 */
@ConfigurationProperties(prefix = "app.admin-bootstrap")
public record AdminBootstrapProperties(
        String username,
        String password
) {

    /** 只有用户名和密码同时非空时才启用引导。 */
    public boolean configured() {
        return username != null
                && !username.isBlank()
                && password != null
                && !password.isBlank();
    }
}
