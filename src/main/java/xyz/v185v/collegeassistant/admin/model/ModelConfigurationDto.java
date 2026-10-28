package xyz.v185v.collegeassistant.admin.model;

import java.time.OffsetDateTime;

import xyz.v185v.collegeassistant.ai.config.ModelConfiguration;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationStatus;
import xyz.v185v.collegeassistant.ai.config.ModelKind;

/** 面向管理员且不暴露密钥的模型配置版本投影。 */
public record ModelConfigurationDto(
        long id,
        String configurationKey,
        ModelKind kind,
        String provider,
        String model,
        String baseUrl,
        Double temperature,
        Integer dimension,
        ModelConfigurationStatus status,
        boolean bootstrap,
        boolean secretConfigured,
        String maskedApiKey,
        boolean available,
        boolean restartRequired,
        String failureReason,
        OffsetDateTime activatedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static ModelConfigurationDto from(
            ModelConfiguration configuration,
            String provider,
            String model,
            String baseUrl,
            Double temperature,
            Integer dimension,
            boolean secretConfigured,
            String maskedApiKey,
            boolean available
    ) {
        return new ModelConfigurationDto(
                configuration.id(), configuration.configurationKey(), configuration.kind(),
                provider, model, baseUrl, temperature, dimension, configuration.status(),
                configuration.isBootstrap(), secretConfigured, maskedApiKey, available,
                configuration.status() == ModelConfigurationStatus.PENDING,
                configuration.failureReason(), configuration.activatedAt(),
                configuration.createdAt(), configuration.updatedAt());
    }
}
