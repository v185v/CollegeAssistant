package xyz.v185v.collegeassistant.storage;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储配置。
 *
 * @param localRoot 本地对象存储根目录
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(Path localRoot) {
}
