package xyz.v185v.collegeassistant.ai;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import xyz.v185v.collegeassistant.ai.config.ModelKind;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.config.AiProperties;

/** 根据受治理配置构建并探测有界 LangChain4j 客户端。 */
@Component
public class ModelRuntimeFactory {

    private final ModelHostPolicy hostPolicy;
    private final ModelHostPolicyProperties properties;

    public ModelRuntimeFactory(
            ModelHostPolicy hostPolicy,
            ModelHostPolicyProperties properties
    ) {
        this.hostPolicy = hostPolicy;
        this.properties = properties;
    }

    public void probe(ModelRuntimeSpec spec) {
        requireSpec(spec);
        hostPolicy.requireAllowed(spec.provider(), spec.baseUrl());
        runBounded(() -> {
            if (spec.kind() == ModelKind.CHAT) {
                String response = factory(spec).chatModel().chat("Reply only with OK.");
                if (response == null || response.isBlank()) {
                    throw providerFailure("Chat model probe returned no content", null);
                }
            } else {
                new LangChain4jEmbeddingGateway(factory(spec).embeddingModel())
                        .embed("model health check");
            }
            return null;
        });
    }

    public ModelRegistry.ChatContext buildChat(long configurationId, ModelRuntimeSpec spec) {
        requireSpec(spec);
        if (spec.kind() != ModelKind.CHAT) {
            throw new IllegalArgumentException("Chat runtime requires CHAT settings");
        }
        hostPolicy.requireAllowed(spec.provider(), spec.baseUrl());
        ModelFactory factory = factory(spec);
        ChatModel chatModel = factory.chatModel();
        StreamingChatModel streamingChatModel = factory.streamingChatModel();
        return new ModelRegistry.ChatContext(configurationId, chatModel, streamingChatModel);
    }

    public EffectiveEmbeddingContext buildEmbedding(
            long configurationId,
            ModelRuntimeSpec spec
    ) {
        requireSpec(spec);
        if (spec.kind() != ModelKind.EMBEDDING) {
            throw new IllegalArgumentException("Embedding runtime requires EMBEDDING settings");
        }
        hostPolicy.requireAllowed(spec.provider(), spec.baseUrl());
        EmbeddingModel model = factory(spec).embeddingModel();
        return new EffectiveEmbeddingContext(
                configurationId, new LangChain4jEmbeddingGateway(model));
    }

    private ModelFactory factory(ModelRuntimeSpec spec) {
        AiProperties ai = new AiProperties();
        char[] apiKey = spec.apiKey();
        String secret = apiKey.length == 0 ? null : new String(apiKey);
        Arrays.fill(apiKey, '\0');
        String provider = clientProvider(spec.provider());
        if (spec.kind() == ModelKind.CHAT) {
            ai.getChat().setProvider(provider);
            ai.getChat().setModel(spec.model());
            ai.getChat().setBaseUrl(spec.baseUrl());
            ai.getChat().setTemperature(spec.temperature());
            ai.getChat().setApiKey(secret);
        } else {
            ai.getEmbedding().setProvider(provider);
            ai.getEmbedding().setModel(spec.model());
            ai.getEmbedding().setBaseUrl(spec.baseUrl());
            ai.getEmbedding().setDimension(spec.dimension() == null ? 0 : spec.dimension());
            ai.getEmbedding().setApiKey(secret);
        }
        return new ModelFactory(
                ai, properties.getRequestTimeout(), properties.getConnectTimeout());
    }

    /**
* DashScope SDK 不提供重定向控制。因此，受管配置使用 DashScope 的 OpenAI 兼容 HTTP API，
* 该 API 基于禁用重定向的 JDK 客户端构建。
     */
    static String clientProvider(String provider) {
        return "dashscope".equals(provider) ? "openai" : provider;
    }

    private <T> T runBounded(Supplier<T> operation) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<T> future = executor.submit(operation::get);
        try {
            return future.get(properties.getRequestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw providerFailure("Model probe timed out", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw providerFailure("Model probe was interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof BusinessException businessException) {
                throw businessException;
            }
            throw providerFailure("Model probe failed", cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private void requireSpec(ModelRuntimeSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("Model runtime settings are required");
        }
    }

    private BusinessException providerFailure(String message, Throwable cause) {
        return cause == null
                ? new BusinessException(ErrorCode.MODEL_PROVIDER_FAILED, message)
                : new BusinessException(ErrorCode.MODEL_PROVIDER_FAILED, message, cause);
    }
}
