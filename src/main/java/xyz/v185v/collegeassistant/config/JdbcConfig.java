package xyz.v185v.collegeassistant.config;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** JDBC 基础设施配置。 */
@Configuration
public class JdbcConfig {

    /**
     * 基于应用数据源创建轻量 JDBC 客户端。
     *
     * @param dataSource Spring Boot 装配的数据源
     * @return 供各仓储复用的 JDBC 客户端
     */
    @Bean
    @ConditionalOnBean(DataSource.class)
    public JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }
}
