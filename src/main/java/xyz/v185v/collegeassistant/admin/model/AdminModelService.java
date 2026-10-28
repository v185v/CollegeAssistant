package xyz.v185v.collegeassistant.admin.model;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import xyz.v185v.collegeassistant.admin.audit.AdminAuditEvent;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditService;
import xyz.v185v.collegeassistant.ai.EmbeddingDimensions;
import xyz.v185v.collegeassistant.ai.EmbeddingModels;
import xyz.v185v.collegeassistant.ai.ModelRuntimeFactory;
import xyz.v185v.collegeassistant.ai.ModelRuntimeSpec;
import xyz.v185v.collegeassistant.ai.config.ModelConfiguration;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationRepository;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationStatus;
import xyz.v185v.collegeassistant.ai.config.ModelKind;
import xyz.v185v.collegeassistant.ai.config.ModelSecretCipher;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.config.AiProperties;

/** 模型校验、探测、重新加密与事务发布边界。 */
@Service
public class AdminModelService {

    private static final Set<String> CHAT_PROVIDERS = Set.of(
            "ollama", "openai", "deepseek", "zhipu", "dashscope");
    private static final Set<String> EMBEDDING_PROVIDERS = Set.of(
            "ollama", "openai", "zhipu", "dashscope");
    private static final Map<String, String> DEFAULT_BASE_URLS = Map.of(
            "ollama", "http://localhost:11434",
            "openai", "https://api.openai.com/v1",
            "deepseek", "https://api.deepseek.com/v1",
            "zhipu", "https://open.bigmodel.cn/api/paas/v4",
            "dashscope", "https://dashscope.aliyuncs.com/compatible-mode/v1");

    private final ModelConfigurationRepository repository;
    private final ModelSecretCipher cipher;
    private final ModelRuntimeFactory runtimeFactory;
    private final AiProperties bootstrap;
    private final AdminAuditService auditService;

    @Autowired
    public AdminModelService(
            ModelConfigurationRepository repository,
            ModelSecretCipher cipher,
            ModelRuntimeFactory runtimeFactory,
            AiProperties bootstrap,
            AdminAuditService auditService
    ) {
        this.repository = repository;
        this.cipher = cipher;
        this.runtimeFactory = runtimeFactory;
        this.bootstrap = bootstrap;
        this.auditService = auditService;
    }

    public List<ModelConfigurationDto> list(AuthPrincipal actor) {
        requireAdmin(actor);
        return repository.findAll().stream().map(this::toDto).toList();
    }

    public void test(AuthPrincipal actor, ModelTestRequest request) {
        requireAdmin(actor);
        if (request == null) {
            throw validation("Model test request is required");
        }
        PreparedRequest prepared = null;
        try {
            prepared = prepare(
                    request.kind(), request.provider(), request.model(), request.baseUrl(),
                    request.temperature(), request.dimension(), request.apiKey(),
                    Boolean.TRUE.equals(request.keepExistingSecret()), request.existingConfigurationId());
            AdminAuditEvent event = event(
                    actor, "MODEL_CONFIGURATION_TESTED", null, prepared);
            try {
                probe(prepared);
                auditSuccess(event);
            } catch (RuntimeException failure) {
                auditFailure(event, failure);
                throw modelSecretFailure(failure);
            }
        } finally {
            if (prepared != null) {
                prepared.clearApiKey();
            }
            request.clearApiKey();
        }
    }

    public ModelConfigurationDto save(
            AuthPrincipal actor,
            ModelConfigurationRequest request
    ) {
        requireAdmin(actor);
        if (request == null) {
            throw validation("Model configuration request is required");
        }
        PreparedRequest prepared = null;
        try {
            prepared = prepare(
                    request.kind(), request.provider(), request.model(), request.baseUrl(),
                    request.temperature(), request.dimension(), request.apiKey(),
                    Boolean.TRUE.equals(request.keepExistingSecret()), request.existingConfigurationId());
            AdminAuditEvent requested = event(
                    actor, "MODEL_CONFIGURATION_SAVED", null, prepared);
            try {
                cipher.requireAvailable();
                probe(prepared);
            } catch (RuntimeException failure) {
                auditFailure(requested, failure);
                throw modelSecretFailure(failure);
            }
            PreparedRequest ready = prepared;
            ModelConfiguration saved = auditedResult(
                    requested,
                    () -> persist(actor.userId(), ready),
                    configuration -> requested.withTargetId(configuration.id()).succeeded());
            return toDto(saved);
        } finally {
            if (prepared != null) {
                prepared.clearApiKey();
            }
            request.clearApiKey();
        }
    }

    private ModelConfiguration persist(long actorId, PreparedRequest request) {
        long id = repository.reserveId();
        char[] secret = resolveSecret(request, true);
        try {
            ModelSecretCipher.EncryptedSecret encrypted = secret.length == 0
                    ? null
                    : cipher.encrypt(secret, ModelConfiguration.aad(id, request.kind()));
            OffsetDateTime now = OffsetDateTime.now();
            return repository.publishPending(new ModelConfiguration(
                    id,
                    request.kind().name().toLowerCase(Locale.ROOT) + '-' + id,
                    request.kind(), request.provider(), request.model(), request.baseUrl(),
                    request.temperature(), request.dimension(),
                    encrypted == null ? null : encrypted.ciphertext(),
                    encrypted == null ? null : encrypted.iv(),
                    encrypted == null ? null : encrypted.encryptionKeyId(),
                    ModelConfigurationStatus.PENDING, actorId, null, null, now, now));
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private void probe(PreparedRequest request) {
        char[] secret = resolveSecret(request, false);
        try (ModelRuntimeSpec spec = request.runtimeSpec(secret)) {
            runtimeFactory.probe(spec);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private char[] resolveSecret(PreparedRequest request, boolean lock) {
        if (!request.keepExistingSecret()) {
            return request.copyApiKey();
        }
        cipher.requireAvailable();
        long existingId = Optional.ofNullable(request.existingConfigurationId())
                .filter(id -> id > 0)
                .orElseThrow(() -> validation(
                        "Existing configuration id is required to retain a secret"));
        ModelConfiguration existing = (lock
                ? repository.lockById(existingId)
                : repository.findById(existingId))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Existing model configuration was not found"));
        if (existing.isBootstrap() || existing.kind() != request.kind()
                || !existing.hasPersistedSecret()) {
            throw validation("Only a persisted secret of the same model kind can be retained");
        }
        return cipher.decrypt(existing.encryptedSecret(), existing.aad());
    }

    private PreparedRequest prepare(
            ModelKind kind,
            String provider,
            String model,
            String baseUrl,
            Double temperature,
            Integer dimension,
            char[] apiKey,
            boolean keepExistingSecret,
            Long existingConfigurationId
    ) {
        if (kind == null) {
            throw validation("Model kind is required");
        }
        String normalizedProvider = required(provider, 64, "Model provider is required")
                .toLowerCase(Locale.ROOT);
        Set<String> supported = kind == ModelKind.CHAT
                ? CHAT_PROVIDERS : EMBEDDING_PROVIDERS;
        if (!supported.contains(normalizedProvider)) {
            throw validation("Unsupported provider for the selected model kind");
        }
        String normalizedModel = required(model, 256, "Model name is required");
        String normalizedBaseUrl = baseUrl == null || baseUrl.isBlank()
                ? DEFAULT_BASE_URLS.get(normalizedProvider)
                : required(baseUrl, 2_048, "Model base URL is invalid");
        Double normalizedTemperature = null;
        Integer normalizedDimension = null;
        if (kind == ModelKind.CHAT) {
            normalizedTemperature = temperature == null ? 0.7 : temperature;
            if (!Double.isFinite(normalizedTemperature)
                    || normalizedTemperature < 0 || normalizedTemperature > 2) {
                throw validation("Chat temperature must be between 0 and 2");
            }
            if (dimension != null) {
                throw validation("Chat configurations do not accept a dimension");
            }
        } else {
            normalizedDimension = dimension == null
                    ? EmbeddingDimensions.DIMENSIONS : dimension;
            if (normalizedDimension != EmbeddingDimensions.DIMENSIONS
                    || !EmbeddingModels.supportsDimension(
                            normalizedProvider, normalizedModel, normalizedDimension)) {
                throw validation("Embedding model must support exactly 1536 dimensions");
            }
            if (temperature != null) {
                throw validation("Embedding configurations do not accept a temperature");
            }
        }
        char[] supplied = apiKey == null ? new char[0] : apiKey.clone();
        if (apiKey != null) {
            Arrays.fill(apiKey, '\0');
        }
        if (supplied.length > 8_192) {
            Arrays.fill(supplied, '\0');
            throw validation("Model API key is too long");
        }
        if (keepExistingSecret && supplied.length > 0) {
            Arrays.fill(supplied, '\0');
            throw validation("Supply a new secret or retain the old one, not both");
        }
        if (!keepExistingSecret && existingConfigurationId != null) {
            Arrays.fill(supplied, '\0');
            throw validation("Existing configuration id requires secret retention");
        }
        if (!"ollama".equals(normalizedProvider)
                && !keepExistingSecret && supplied.length == 0) {
            Arrays.fill(supplied, '\0');
            throw validation("The selected provider requires an API key");
        }
        try {
            return new PreparedRequest(
                    kind, normalizedProvider, normalizedModel, normalizedBaseUrl,
                    normalizedTemperature, normalizedDimension, supplied,
                    keepExistingSecret, existingConfigurationId);
        } finally {
            Arrays.fill(supplied, '\0');
        }
    }

    private ModelConfigurationDto toDto(ModelConfiguration configuration) {
        if (configuration.isBootstrap()) {
            return bootstrapDto(configuration);
        }
        String masked = null;
        boolean available = cipher.isAvailable()
                && configuration.status() != ModelConfigurationStatus.FAILED;
        if (configuration.hasPersistedSecret() && available) {
            char[] secret = null;
            try {
                secret = cipher.decrypt(configuration.encryptedSecret(), configuration.aad());
                masked = mask(secret);
            } catch (RuntimeException failure) {
                available = false;
            } finally {
                if (secret != null) {
                    Arrays.fill(secret, '\0');
                }
            }
        }
        return ModelConfigurationDto.from(
                configuration, configuration.provider(), configuration.model(),
                configuration.baseUrl(), configuration.temperature(), configuration.dimension(),
                configuration.hasPersistedSecret(), masked, available);
    }

    private ModelConfigurationDto bootstrapDto(ModelConfiguration configuration) {
        String provider;
        String model;
        String baseUrl;
        Double temperature;
        Integer dimension;
        String secretValue;
        if (configuration.kind() == ModelKind.CHAT) {
            provider = bootstrap.getChat().getProvider();
            model = bootstrap.getChat().getModel();
            baseUrl = bootstrap.getChat().getBaseUrl();
            temperature = bootstrap.getChat().getTemperature();
            dimension = null;
            secretValue = bootstrap.getChat().getApiKey();
        } else {
            provider = bootstrap.getEmbedding().getProvider();
            model = bootstrap.getEmbedding().getModel();
            baseUrl = bootstrap.getEmbedding().getBaseUrl();
            temperature = null;
            dimension = bootstrap.getEmbedding().getDimension();
            secretValue = bootstrap.getEmbedding().getApiKey();
        }
        String normalizedProvider = provider == null
                ? null : provider.strip().toLowerCase(Locale.ROOT);
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URLS.get(normalizedProvider);
        }
        if ((model == null || model.isBlank()) && "ollama".equals(normalizedProvider)) {
            model = configuration.kind() == ModelKind.CHAT
                    ? "qwen2.5:7b" : "qwen3-embedding:4b";
        }
        provider = normalizedProvider;
        char[] secret = secretValue == null ? new char[0] : secretValue.toCharArray();
        try {
            return ModelConfigurationDto.from(
                    configuration, provider, model, baseUrl, temperature, dimension,
                    secret.length > 0, secret.length == 0 ? null : mask(secret), true);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private String mask(char[] secret) {
        int tailLength = Math.min(4, secret.length);
        return "********" + new String(secret, secret.length - tailLength, tailLength);
    }

    private AdminAuditEvent event(
            AuthPrincipal actor,
            String action,
            Long targetId,
            PreparedRequest request
    ) {
        return AdminAuditEvent.requested(
                actor.userId(), action, "MODEL_CONFIGURATION", targetId,
                Map.of(
                        "kind", request.kind().name(),
                        "provider", request.provider(),
                        "model", request.model()));
    }

    private void auditSuccess(AdminAuditEvent event) {
        if (auditService != null) {
            auditService.audited(event, () -> null);
        }
    }

    private void auditFailure(AdminAuditEvent event, RuntimeException failure) {
        if (auditService == null) {
            return;
        }
        try {
            auditService.audited(event, () -> {
                throw failure;
            });
        } catch (RuntimeException recorded) {
            if (recorded != failure) {
                failure.addSuppressed(recorded);
            }
        }
    }

    private ModelSecretFailure modelSecretFailure(RuntimeException failure) {
        if (failure instanceof BusinessException businessException
                && businessException.getCode() != ErrorCode.MODEL_CONFIG_UNAVAILABLE) {
            throw businessException;
        }
        return new ModelSecretFailure(failure);
    }

    private void requireAdmin(AuthPrincipal actor) {
        if (actor == null || !UserRole.ADMIN.name().equals(actor.role())) {
            throw new BusinessException(ErrorCode.ADMIN_REQUIRED, "Administrator role required");
        }
    }

    private String required(String value, int maximum, String message) {
        if (value == null || value.isBlank()) {
            throw validation(message);
        }
        String normalized = value.strip();
        if (normalized.length() > maximum) {
            throw validation(message);
        }
        return normalized;
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    private <T> T auditedResult(
            AdminAuditEvent event,
            Supplier<T> operation,
            java.util.function.Function<T, AdminAuditEvent> success
    ) {
        return auditService == null
                ? operation.get()
                : auditService.auditedResult(event, operation, success);
    }

    private static final class ModelSecretFailure extends BusinessException {
        private ModelSecretFailure(Throwable cause) {
            super(ErrorCode.MODEL_CONFIG_UNAVAILABLE,
                    "Persisted model configurations are unavailable", cause);
        }
    }

    private record PreparedRequest(
            ModelKind kind,
            String provider,
            String model,
            String baseUrl,
            Double temperature,
            Integer dimension,
            char[] apiKey,
            boolean keepExistingSecret,
            Long existingConfigurationId
    ) {
        private PreparedRequest {
            apiKey = apiKey.clone();
        }

        private char[] copyApiKey() {
            return apiKey.clone();
        }

        private void clearApiKey() {
            Arrays.fill(apiKey, '\0');
        }

        private ModelRuntimeSpec runtimeSpec(char[] secret) {
            return new ModelRuntimeSpec(
                    kind, provider, model, baseUrl, temperature, dimension, secret);
        }
    }
}
