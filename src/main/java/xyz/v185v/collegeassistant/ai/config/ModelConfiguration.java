package xyz.v185v.collegeassistant.ai.config;

import java.time.OffsetDateTime;
import java.util.Objects;

/** 不可变的持久化模型配置；密钥字节数组始终进行防御性复制。 */
public record ModelConfiguration(
        long id,
        String configurationKey,
        ModelKind kind,
        String provider,
        String model,
        String baseUrl,
        Double temperature,
        Integer dimension,
        byte[] apiKeyCiphertext,
        byte[] apiKeyIv,
        String encryptionKeyId,
        ModelConfigurationStatus status,
        Long createdBy,
        OffsetDateTime activatedAt,
        String failureReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public ModelConfiguration {
        if (id <= 0) {
            throw new IllegalArgumentException("Model configuration id must be positive");
        }
        configurationKey = required(configurationKey, "configurationKey");
        kind = Objects.requireNonNull(kind, "kind");
        status = Objects.requireNonNull(status, "status");
        apiKeyCiphertext = copy(apiKeyCiphertext);
        apiKeyIv = copy(apiKeyIv);
        boolean noSecret = apiKeyCiphertext == null && apiKeyIv == null && encryptionKeyId == null;
        boolean completeSecret = apiKeyCiphertext != null && apiKeyCiphertext.length >= 16
                && apiKeyIv != null && apiKeyIv.length == 12
                && encryptionKeyId != null && !encryptionKeyId.isBlank();
        if (!noSecret && !completeSecret) {
            throw new IllegalArgumentException("Encrypted model secret must be complete");
        }
        if (createdBy != null && createdBy <= 0) {
            throw new IllegalArgumentException("createdBy must be positive when present");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    @Override
    public byte[] apiKeyCiphertext() {
        return copy(apiKeyCiphertext);
    }

    @Override
    public byte[] apiKeyIv() {
        return copy(apiKeyIv);
    }

    public boolean hasPersistedSecret() {
        return apiKeyCiphertext != null;
    }

    public boolean isBootstrap() {
        return "bootstrap-chat".equals(configurationKey)
                || "bootstrap-embedding".equals(configurationKey);
    }

    public ModelSecretCipher.EncryptedSecret encryptedSecret() {
        return hasPersistedSecret()
                ? new ModelSecretCipher.EncryptedSecret(
                        apiKeyCiphertext, apiKeyIv, encryptionKeyId)
                : null;
    }

    public String aad() {
        return aad(id, kind);
    }

    public static String aad(long id, ModelKind kind) {
        if (id <= 0 || kind == null) {
            throw new IllegalArgumentException("Model secret AAD requires id and kind");
        }
        return "config:" + id + ':' + kind.name();
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
