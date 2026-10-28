package xyz.v185v.collegeassistant.ai;

import java.time.Duration;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

/** 各厂商 LangChain4j 向量模型的集中构建工具。 */
public final class EmbeddingModels {

    private EmbeddingModels() {
    }

    /** 判断指定厂商模型是否支持目标向量维度。 */
    public static boolean supportsDimension(String provider, String modelName, int dimension) {
        if (!"dashscope".equals(provider)) {
            return true;
        }
        return dimension == EmbeddingDimensions.DIMENSIONS
                && ("text-embedding-v1".equals(modelName)
                || "text-embedding-v2".equals(modelName));
    }

    /** 根据已校验的厂商配置创建向量模型。 */
    static EmbeddingModel create(
            String provider,
            String baseUrl,
            String modelName,
            String apiKey,
            int dimension
    ) {
        return create(
                provider, baseUrl, modelName, apiKey, dimension,
                Duration.ofSeconds(60), Duration.ofSeconds(15));
    }

    static EmbeddingModel create(
            String provider,
            String baseUrl,
            String modelName,
            String apiKey,
            int dimension,
            Duration timeout
    ) {
        return create(
                provider, baseUrl, modelName, apiKey, dimension, timeout,
                timeout.compareTo(Duration.ofSeconds(15)) < 0
                        ? timeout : Duration.ofSeconds(15));
    }

    static EmbeddingModel create(
            String provider,
            String baseUrl,
            String modelName,
            String apiKey,
            int dimension,
            Duration timeout,
            Duration connectTimeout
    ) {
        return switch (provider) {
            case "ollama" -> OllamaEmbeddingModel.builder()
                    .httpClientBuilder(httpClientBuilder(connectTimeout, timeout))
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .dimensions(dimension)
                    .timeout(timeout)
                    .maxRetries(0)
                    .build();
            case "openai", "zhipu" -> OpenAiEmbeddingModel.builder()
                    .httpClientBuilder(httpClientBuilder(connectTimeout, timeout))
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .dimensions(dimension)
                    .timeout(timeout)
                    .maxRetries(0)
                    .build();
            case "dashscope" -> QwenEmbeddingModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .build();
            default -> throw new IllegalArgumentException("Unsupported embedding provider");
        };
    }

    private static JdkHttpClientBuilder httpClientBuilder(
            Duration connectTimeout,
            Duration requestTimeout
    ) {
        return new JdkHttpClientBuilder()
                .httpClientBuilder(java.net.http.HttpClient.newBuilder()
                        .connectTimeout(connectTimeout)
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER))
                .connectTimeout(connectTimeout)
                .readTimeout(requestTimeout);
    }
}
