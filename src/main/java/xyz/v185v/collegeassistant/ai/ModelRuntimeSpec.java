package xyz.v185v.collegeassistant.ai;

import java.util.Arrays;
import java.util.Objects;

import xyz.v185v.collegeassistant.ai.config.ModelKind;

/** 携带防御性 API 密钥副本的短生命周期、可关闭运行时模型配置。 */
public record ModelRuntimeSpec(
        ModelKind kind,
        String provider,
        String model,
        String baseUrl,
        Double temperature,
        Integer dimension,
        char[] apiKey
) implements AutoCloseable {

    public ModelRuntimeSpec {
        kind = Objects.requireNonNull(kind, "kind");
        provider = required(provider, "provider");
        model = required(model, "model");
        baseUrl = required(baseUrl, "baseUrl");
        apiKey = apiKey == null ? new char[0] : apiKey.clone();
    }

    @Override
    public char[] apiKey() {
        return apiKey.clone();
    }

    public boolean hasApiKey() {
        return apiKey.length > 0;
    }

    @Override
    public void close() {
        Arrays.fill(apiKey, '\0');
    }

    @Override
    public String toString() {
        return "ModelRuntimeSpec[kind=" + kind + ", provider=" + provider
                + ", model=" + model + ", baseUrl=" + baseUrl
                + ", temperature=" + temperature + ", dimension=" + dimension
                + ", apiKey=<redacted>]";
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
