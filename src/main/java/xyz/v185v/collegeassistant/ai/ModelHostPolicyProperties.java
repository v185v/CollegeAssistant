package xyz.v185v.collegeassistant.ai;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 由部署方管理的模型端点允许列表与严格探测超时配置。 */
@ConfigurationProperties(prefix = "app.ai")
public class ModelHostPolicyProperties {

    private String configEncryptionKey;
    private List<String> modelHostAllowlist = List.of();
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(10);

    public String getConfigEncryptionKey() {
        return configEncryptionKey;
    }

    public void setConfigEncryptionKey(String configEncryptionKey) {
        this.configEncryptionKey = configEncryptionKey;
    }

    public List<String> getModelHostAllowlist() {
        return modelHostAllowlist;
    }

    public void setModelHostAllowlist(List<String> modelHostAllowlist) {
        this.modelHostAllowlist = modelHostAllowlist == null
                ? List.of() : List.copyOf(modelHostAllowlist);
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = bounded(connectTimeout, Duration.ofSeconds(1),
                Duration.ofSeconds(30), "connectTimeout");
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = bounded(requestTimeout, Duration.ofSeconds(1),
                Duration.ofMinutes(2), "requestTimeout");
    }

    private Duration bounded(Duration value, Duration minimum, Duration maximum, String name) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " is outside the safe range");
        }
        return value;
    }
}
