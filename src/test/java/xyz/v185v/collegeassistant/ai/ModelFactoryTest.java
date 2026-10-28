package xyz.v185v.collegeassistant.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.Consumer;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.config.AiProperties;

class ModelFactoryTest {

    private static final int EMBEDDING_DIMENSION = 1536;

    @Test
    void createsTheExactModelClassesForEverySupportedProvider() {
        List<ProviderScenario> scenarios = List.of(
                new ProviderScenario(
                        "ollama",
                        OllamaChatModel.class,
                        OllamaStreamingChatModel.class,
                        OllamaEmbeddingModel.class),
                new ProviderScenario(
                        "openai",
                        OpenAiChatModel.class,
                        OpenAiStreamingChatModel.class,
                        OpenAiEmbeddingModel.class),
                new ProviderScenario(
                        "deepseek",
                        OpenAiChatModel.class,
                        OpenAiStreamingChatModel.class,
                        null),
                new ProviderScenario(
                        "zhipu",
                        OpenAiChatModel.class,
                        OpenAiStreamingChatModel.class,
                        OpenAiEmbeddingModel.class),
                new ProviderScenario(
                        "dashscope",
                        QwenChatModel.class,
                        QwenStreamingChatModel.class,
                        QwenEmbeddingModel.class));

        for (ProviderScenario scenario : scenarios) {
            ModelFactory factory = new ModelFactory(configuredProperties(scenario.provider()));

            assertThat(factory.chatModel())
                    .as("%s chat model", scenario.provider())
                    .isExactlyInstanceOf(scenario.chatModelClass());
            assertThat(factory.streamingChatModel())
                    .as("%s streaming chat model", scenario.provider())
                    .isExactlyInstanceOf(scenario.streamingChatModelClass());
            if (scenario.embeddingModelClass() != null) {
                assertThat(factory.embeddingModel())
                        .as("%s embedding model", scenario.provider())
                        .isExactlyInstanceOf(scenario.embeddingModelClass());
            }
        }
    }

    @Test
    void normalizesProviderWhitespaceAndCaseBeforeSelection() {
        ModelFactory factory = new ModelFactory(configuredProperties("  OlLaMa  "));

        assertThat(factory.chatModel()).isExactlyInstanceOf(OllamaChatModel.class);
        assertThat(factory.streamingChatModel())
                .isExactlyInstanceOf(OllamaStreamingChatModel.class);
        assertThat(factory.embeddingModel()).isExactlyInstanceOf(OllamaEmbeddingModel.class);
    }

    @Test
    void rejectsUnsupportedOrMissingRequiredProviderConfiguration() {
        assertInvalidChat(chat -> chat.setProvider("unsupported"));
        assertInvalidEmbedding(embedding -> embedding.setProvider("unsupported"));

        assertInvalidChat(chat -> chat.setProvider(null));
        assertInvalidChat(chat -> chat.setModel(" "));
        assertInvalidChat(chat -> chat.setBaseUrl(null));
        assertInvalidChat(chat -> chat.setApiKey(""));

        assertInvalidEmbedding(embedding -> embedding.setProvider(" "));
        assertInvalidEmbedding(embedding -> embedding.setModel(null));
        assertInvalidEmbedding(embedding -> embedding.setBaseUrl(""));
        assertInvalidEmbedding(embedding -> embedding.setApiKey(null));
    }

    @Test
    void requiresEmbeddingDimensionToBeExactly1536() {
        for (int invalidDimension : List.of(0, 1535, 1537)) {
            AiProperties properties = configuredProperties("openai");
            properties.getEmbedding().setDimension(invalidDimension);

            assertModelProviderFailure(() -> new ModelFactory(properties).embeddingModel());
        }

        ModelFactory factory = new ModelFactory(configuredProperties("openai"));
        assertThat(factory.embeddingModel()).isExactlyInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    void rejectsMissingNonFiniteOrOutOfRangeChatTemperatures() {
        assertInvalidChat(chat -> chat.setTemperature(null));
        for (double invalidTemperature : List.of(
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                -0.01d,
                2.01d)) {
            assertInvalidChat(chat -> chat.setTemperature(invalidTemperature));
        }
    }

    @Test
    void configurationFailuresDoNotLeakApiKeys() {
        String secretApiKey = "sk-private-test-secret";
        AiProperties properties = configuredProperties("unsupported");
        properties.getChat().setApiKey(secretApiKey);

        assertThatThrownBy(() -> new ModelFactory(properties).chatModel())
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.MODEL_PROVIDER_FAILED);
                    assertThat(exception.getMessage()).doesNotContain(secretApiKey);
                });
    }

    @Test
    void rejectsDashscopeEmbeddingModelsThatCannotProduce1536Dimensions() {
        AiProperties properties = configuredProperties("dashscope");
        properties.getEmbedding().setModel("text-embedding-v3");

        assertModelProviderFailure(() -> new ModelFactory(properties).embeddingModel());
    }

    @Test
    void rejectsDeepseekAsAnEmbeddingProvider() {
        AiProperties properties = configuredProperties("deepseek");

        assertModelProviderFailure(() -> new ModelFactory(properties).embeddingModel());
    }

    @Test
    void requiresCanonicalCaseSensitiveDashscopeEmbeddingModelIds() {
        AiProperties properties = configuredProperties("dashscope");
        properties.getEmbedding().setModel("TEXT-EMBEDDING-V2");

        assertModelProviderFailure(() -> new ModelFactory(properties).embeddingModel());
    }

    @Test
    void providerBuilderFailuresRetainCauseWithoutLeakingApiKeys() {
        String secretApiKey = "sk-builder-secret";
        AiProperties properties = configuredProperties("openai");
        properties.getChat().setApiKey(secretApiKey);
        properties.getChat().setBaseUrl("http://[");

        assertThatThrownBy(() -> new ModelFactory(properties).chatModel())
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.MODEL_PROVIDER_FAILED);
                    assertThat(exception.getCause()).isNotNull();
                    assertThat(exception.getMessage()).doesNotContain(secretApiKey);
                });
    }

    @Test
    void managedDashscopeConfigurationsUseTheOpenAiCompatibleClient() {
        assertThat(ModelRuntimeFactory.clientProvider("dashscope")).isEqualTo("openai");
        assertThat(ModelRuntimeFactory.clientProvider("openai")).isEqualTo("openai");
        assertThat(ModelRuntimeFactory.clientProvider("ollama")).isEqualTo("ollama");
    }

    private void assertInvalidChat(Consumer<AiProperties.Chat> mutation) {
        AiProperties properties = configuredProperties("openai");
        mutation.accept(properties.getChat());
        assertModelProviderFailure(() -> new ModelFactory(properties).chatModel());
    }

    private void assertInvalidEmbedding(Consumer<AiProperties.Embedding> mutation) {
        AiProperties properties = configuredProperties("openai");
        mutation.accept(properties.getEmbedding());
        assertModelProviderFailure(() -> new ModelFactory(properties).embeddingModel());
    }

    private void assertModelProviderFailure(ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.MODEL_PROVIDER_FAILED));
    }

    private AiProperties configuredProperties(String provider) {
        AiProperties properties = new AiProperties();
        properties.getChat().setProvider(provider);
        properties.getEmbedding().setProvider(provider);
        properties.getEmbedding().setDimension(EMBEDDING_DIMENSION);

        switch (provider) {
            case "ollama" -> configure(
                    properties,
                    "http://localhost:11434",
                    "llama3.2",
                    "nomic-embed-text",
                    null);
            case "openai" -> configure(
                    properties,
                    "https://api.openai.com/v1",
                    "gpt-4.1-mini",
                    "text-embedding-3-small",
                    "test-openai-key");
            case "deepseek" -> configure(
                    properties,
                    "https://api.deepseek.com/v1",
                    "deepseek-chat",
                    "deepseek-embedding",
                    "test-deepseek-key");
            case "zhipu" -> configure(
                    properties,
                    "https://open.bigmodel.cn/api/paas/v4",
                    "glm-4-flash",
                    "embedding-3",
                    "test-zhipu-key");
            case "dashscope" -> configure(
                    properties,
                    "https://dashscope.aliyuncs.com/api/v1",
                    "qwen-plus",
                    "text-embedding-v2",
                    "test-dashscope-key");
            default -> configure(
                    properties,
                    "https://models.invalid/v1",
                    "test-chat-model",
                    "test-embedding-model",
                    "test-key");
        }
        return properties;
    }

    private void configure(
            AiProperties properties,
            String baseUrl,
            String chatModel,
            String embeddingModel,
            String apiKey
    ) {
        properties.getChat().setBaseUrl(baseUrl);
        properties.getChat().setModel(chatModel);
        properties.getChat().setApiKey(apiKey);
        properties.getEmbedding().setBaseUrl(baseUrl);
        properties.getEmbedding().setModel(embeddingModel);
        properties.getEmbedding().setApiKey(apiKey);
    }

    private record ProviderScenario(
            String provider,
            Class<?> chatModelClass,
            Class<?> streamingChatModelClass,
            Class<?> embeddingModelClass
    ) {
    }
}
