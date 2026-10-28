package xyz.v185v.collegeassistant.admin.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;
import xyz.v185v.collegeassistant.ai.config.ModelKind;

/** 管理员发起的持久化最新探测不可变配置版本的请求。 */
public record ModelConfigurationRequest(
        @NotNull ModelKind kind,
        @NotBlank @Size(max = 64) String provider,
        @NotBlank @Size(max = 256) String model,
        @Size(max = 2048) String baseUrl,
        @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
        Integer dimension,
        @JsonDeserialize(using = SecretCharsDeserializer.class)
        @Size(max = 8192) char[] apiKey,
        Boolean keepExistingSecret,
        @Positive Long existingConfigurationId
) {

    public ModelConfigurationRequest {
        if (apiKey != null) {
            char[] supplied = apiKey;
            apiKey = supplied.clone();
            java.util.Arrays.fill(supplied, '\0');
        }
    }

    @Override
    public char[] apiKey() {
        return apiKey == null ? null : apiKey.clone();
    }

    public void clearApiKey() {
        if (apiKey != null) {
            java.util.Arrays.fill(apiKey, '\0');
        }
    }

    @Override
    public String toString() {
        return "ModelConfigurationRequest[kind=" + kind + ", provider=" + provider
                + ", model=" + model + ", baseUrl=" + baseUrl
                + ", temperature=" + temperature + ", dimension=" + dimension
                + ", apiKey=<redacted>, keepExistingSecret=" + keepExistingSecret
                + ", existingConfigurationId=" + existingConfigurationId + ']';
    }
}
