package xyz.v185v.collegeassistant.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

class ModelRegistryTest {

    @Test
    void swapsEmbeddingIdAndGatewayAsOneImmutableContext() {
        EmbeddingGateway bootstrapEmbedding = gateway(2.0);
        ModelRegistry registry = new ModelRegistry(chat(1), bootstrapEmbedding);

        assertThatThrownBy(registry::requireEmbedding)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.MODEL_CONFIG_UNAVAILABLE));

        EffectiveEmbeddingContext candidate =
                new EffectiveEmbeddingContext(9, gateway(9.0));
        registry.loadEmbeddingCandidate(candidate);
        registry.activateEmbedding(candidate);

        EffectiveEmbeddingContext effective = registry.requireEmbedding();
        assertThat(effective.configurationId()).isEqualTo(9);
        assertThat(effective.gateway().embed("text")).containsExactly(9.0);
        assertThat(registry.pendingEmbedding()).contains(candidate);
    }

    @Test
    void chatCanFallBackButEmbeddingCanBeClearedFailClosed() {
        ModelRegistry.ChatContext bootstrapChat = chat(1);
        ModelRegistry registry = new ModelRegistry(bootstrapChat, gateway(2.0));
        registry.activateChat(chat(7));
        registry.activateEmbedding(new EffectiveEmbeddingContext(7, gateway(7.0)));

        registry.fallbackChat();
        registry.clearEmbedding();

        assertThat(registry.requireChat()).isSameAs(bootstrapChat);
        assertThatThrownBy(registry::requireEmbedding)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.MODEL_CONFIG_UNAVAILABLE));
    }

    private ModelRegistry.ChatContext chat(long id) {
        return new ModelRegistry.ChatContext(id, new ChatModel() { }, new StreamingChatModel() { });
    }

    private EmbeddingGateway gateway(double value) {
        return new EmbeddingGateway() {
            @Override
            public List<Double> embed(String text) {
                return List.of(value);
            }

            @Override
            public List<List<Double>> embedAll(List<String> texts) {
                return texts.stream().map(ignored -> List.of(value)).toList();
            }

            @Override
            public int dimension() {
                return 1536;
            }
        };
    }
}
