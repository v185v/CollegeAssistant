package xyz.v185v.collegeassistant.ai;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.config.AiProperties;

/**
 * 按配置构建 LangChain4j 模型，避免应用服务依赖厂商 SDK 类型。
 *
 * <p>工厂集中校验厂商名称、模型名、端点、密钥、温度和向量维度，
 * 将装配失败统一转换为模型提供方业务异常。</p>
 */
public final class ModelFactory {

    private static final double MIN_TEMPERATURE = 0.0d;
    private static final double MAX_TEMPERATURE = 2.0d;
    private static final String OLLAMA = "ollama";
    private static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";
    private static final String DEFAULT_OLLAMA_CHAT_MODEL = "qwen2.5:7b";
    private static final String DEFAULT_OLLAMA_EMBEDDING_MODEL = "qwen3-embedding:4b";

    private final AiProperties properties;
    private final Duration timeout;
    private final Duration connectTimeout;

    public ModelFactory(AiProperties properties) {
        this(properties, Duration.ofSeconds(60), Duration.ofSeconds(15));
    }

    public ModelFactory(AiProperties properties, Duration timeout) {
        this(properties, timeout, timeout.compareTo(Duration.ofSeconds(15)) < 0
                ? timeout : Duration.ofSeconds(15));
    }

    public ModelFactory(
            AiProperties properties,
            Duration timeout,
            Duration connectTimeout
    ) {
        if (properties == null) {
            throw configurationFailure("AI properties are required");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw configurationFailure("AI model timeout must be positive");
        }
        if (connectTimeout == null || connectTimeout.isNegative()
                || connectTimeout.isZero() || connectTimeout.compareTo(timeout) > 0) {
            throw configurationFailure(
                    "AI model connect timeout must be positive and not exceed request timeout");
        }
        this.properties = properties;
        this.timeout = timeout;
        this.connectTimeout = connectTimeout;
    }

    /** @return 配置对应的同步对话模型 */
    public ChatModel chatModel() {
        ChatSettings settings = chatSettings();
        return buildSafely(() -> switch (settings.provider()) {
            case OLLAMA -> OllamaChatModel.builder()
                    .httpClientBuilder(httpClientBuilder())
                    .baseUrl(settings.baseUrl())
                    .modelName(settings.modelName())
                    .temperature(settings.temperature())
                    .timeout(timeout)
                    .maxRetries(0)
                    .build();
            case "openai", "deepseek", "zhipu" -> OpenAiChatModel.builder()
                    .httpClientBuilder(httpClientBuilder())
                    .baseUrl(settings.baseUrl())
                    .apiKey(settings.apiKey())
                    .modelName(settings.modelName())
                    .temperature(settings.temperature())
                    .timeout(timeout)
                    .maxRetries(0)
                    .build();
            case "dashscope" -> QwenChatModel.builder()
                    .baseUrl(settings.baseUrl())
                    .apiKey(settings.apiKey())
                    .modelName(settings.modelName())
                    .temperature(settings.temperature().floatValue())
                    .build();
            default -> throw configurationFailure("Unsupported chat provider");
        });
    }

    /** @return 配置对应的流式对话模型 */
    public StreamingChatModel streamingChatModel() {
        ChatSettings settings = chatSettings();
        return buildSafely(() -> switch (settings.provider()) {
            case OLLAMA -> OllamaStreamingChatModel.builder()
                    .httpClientBuilder(httpClientBuilder())
                    .baseUrl(settings.baseUrl())
                    .modelName(settings.modelName())
                    .temperature(settings.temperature())
                    .timeout(timeout)
                    .build();
            case "openai", "deepseek", "zhipu" -> OpenAiStreamingChatModel.builder()
                    .httpClientBuilder(httpClientBuilder())
                    .baseUrl(settings.baseUrl())
                    .apiKey(settings.apiKey())
                    .modelName(settings.modelName())
                    .temperature(settings.temperature())
                    .timeout(timeout)
                    .build();
            case "dashscope" -> QwenStreamingChatModel.builder()
                    .baseUrl(settings.baseUrl())
                    .apiKey(settings.apiKey())
                    .modelName(settings.modelName())
                    .temperature(settings.temperature().floatValue())
                    .build();
            default -> throw configurationFailure("Unsupported chat provider");
        });
    }

    /** @return 配置对应且维度受治理的文本向量模型 */
    public EmbeddingModel embeddingModel() {
        EmbeddingSettings settings = embeddingSettings();
        return buildSafely(() -> EmbeddingModels.create(
                settings.provider(),
                settings.baseUrl(),
                settings.modelName(),
                settings.apiKey(),
                settings.dimension(),
                timeout,
                connectTimeout));
    }

    private ChatSettings chatSettings() {
        AiProperties.Chat chat = properties.getChat();
        if (chat == null) {
            throw configurationFailure("Chat configuration is required");
        }
        String provider = provider(chat.getProvider(), "chat");
        String modelName = providerValue(
                provider,
                chat.getModel(),
                DEFAULT_OLLAMA_CHAT_MODEL,
                "Chat model is required");
        String baseUrl = baseUrl(provider, chat.getBaseUrl());
        String apiKey = apiKey(provider, chat.getApiKey(), "Chat API key is required");
        Double temperature = chat.getTemperature();
        if (temperature == null || !Double.isFinite(temperature)
                || temperature < MIN_TEMPERATURE || temperature > MAX_TEMPERATURE) {
            throw configurationFailure("Chat temperature must be between 0 and 2");
        }
        return new ChatSettings(provider, modelName, baseUrl, apiKey, temperature);
    }

    private EmbeddingSettings embeddingSettings() {
        AiProperties.Embedding embedding = properties.getEmbedding();
        if (embedding == null) {
            throw configurationFailure("Embedding configuration is required");
        }
        String provider = provider(embedding.getProvider(), "embedding");
        if ("deepseek".equals(provider)) {
            throw configurationFailure("Unsupported embedding provider");
        }
        String modelName = providerValue(
                provider,
                embedding.getModel(),
                DEFAULT_OLLAMA_EMBEDDING_MODEL,
                "Embedding model is required");
        String baseUrl = baseUrl(provider, embedding.getBaseUrl());
        String apiKey = apiKey(provider, embedding.getApiKey(), "Embedding API key is required");
        if (embedding.getDimension() != EmbeddingDimensions.DIMENSIONS) {
            throw configurationFailure("Embedding dimension must be exactly 1536");
        }
        if (!EmbeddingModels.supportsDimension(
                provider, modelName, embedding.getDimension())) {
            throw configurationFailure(
                    "Configured embedding model does not support 1536 dimensions");
        }
        return new EmbeddingSettings(
                provider, modelName, baseUrl, apiKey, embedding.getDimension());
    }

    private String provider(String value, String modelKind) {
        String provider = required(value, modelKind + " provider is required")
                .toLowerCase(Locale.ROOT);
        return switch (provider) {
            case OLLAMA, "openai", "deepseek", "zhipu", "dashscope" -> provider;
            default -> throw configurationFailure("Unsupported " + modelKind + " provider");
        };
    }

    private String apiKey(String provider, String value, String missingMessage) {
        if (OLLAMA.equals(provider)) {
            return value == null ? null : value.trim();
        }
        return required(value, missingMessage);
    }

    private String providerValue(
            String provider,
            String value,
            String ollamaDefault,
            String missingMessage
    ) {
        if (OLLAMA.equals(provider) && (value == null || value.isBlank())) {
            return ollamaDefault;
        }
        return required(value, missingMessage);
    }

    private String baseUrl(String provider, String value) {
        String configured = providerValue(
                provider,
                value,
                DEFAULT_OLLAMA_BASE_URL,
                "AI model base URL is required");
        try {
            URI uri = URI.create(configured);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("Base URL must be an absolute HTTP URL");
            }
            return configured;
        } catch (RuntimeException exception) {
            throw configurationFailure("AI model base URL is invalid", exception);
        }
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw configurationFailure(message);
        }
        return value.trim();
    }

    private <T> T buildSafely(Supplier<T> builder) {
        try {
            return builder.get();
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw configurationFailure("Failed to configure AI model provider", exception);
        }
    }

    private JdkHttpClientBuilder httpClientBuilder() {
        return new JdkHttpClientBuilder()
                .httpClientBuilder(java.net.http.HttpClient.newBuilder()
                        .connectTimeout(connectTimeout)
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER))
                .connectTimeout(connectTimeout)
                .readTimeout(timeout);
    }

    private static BusinessException configurationFailure(String message) {
        return new BusinessException(ErrorCode.MODEL_PROVIDER_FAILED, message);
    }

    private static BusinessException configurationFailure(String message, Throwable cause) {
        return new BusinessException(ErrorCode.MODEL_PROVIDER_FAILED, message, cause);
    }

    private record ChatSettings(
            String provider,
            String modelName,
            String baseUrl,
            String apiKey,
            Double temperature
    ) {
    }

    private record EmbeddingSettings(
            String provider,
            String modelName,
            String baseUrl,
            String apiKey,
            int dimension
    ) {
    }
}
