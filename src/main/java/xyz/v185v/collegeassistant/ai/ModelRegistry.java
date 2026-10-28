package xyz.v185v.collegeassistant.ai;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/** 无锁选择完整模型上下文的运行时注册表。 */
public final class ModelRegistry {

    private final ChatContext bootstrapChat;
    private final EmbeddingGateway bootstrapEmbedding;
    private final AtomicReference<ChatContext> chat;
    private final AtomicReference<EffectiveEmbeddingContext> embedding =
            new AtomicReference<>();
    private final AtomicReference<EffectiveEmbeddingContext> pendingEmbedding =
            new AtomicReference<>();

    public ModelRegistry(ChatContext bootstrapChat, EmbeddingGateway bootstrapEmbedding) {
        this.bootstrapChat = Objects.requireNonNull(bootstrapChat, "bootstrapChat");
        this.bootstrapEmbedding = Objects.requireNonNull(
                bootstrapEmbedding, "bootstrapEmbedding");
        this.chat = new AtomicReference<>(bootstrapChat);
    }

    public ChatContext requireChat() {
        ChatContext context = chat.get();
        if (context == null) {
            throw unavailable("Active chat configuration is unavailable");
        }
        return context;
    }

    public void activateChat(ChatContext next) {
        chat.set(Objects.requireNonNull(next, "next"));
    }

    public void fallbackChat() {
        chat.set(bootstrapChat);
    }

    public EffectiveEmbeddingContext requireEmbedding() {
        EffectiveEmbeddingContext context = embedding.get();
        if (context == null) {
            throw unavailable("Active embedding configuration is unavailable");
        }
        return context;
    }

    public void activateEmbedding(EffectiveEmbeddingContext next) {
        embedding.set(Objects.requireNonNull(next, "next"));
    }

    public void clearEmbedding() {
        embedding.set(null);
    }

    public void loadEmbeddingCandidate(EffectiveEmbeddingContext candidate) {
        pendingEmbedding.set(Objects.requireNonNull(candidate, "candidate"));
    }

    public Optional<EffectiveEmbeddingContext> pendingEmbedding() {
        return Optional.ofNullable(pendingEmbedding.get());
    }

    public void clearEmbeddingCandidate() {
        pendingEmbedding.set(null);
    }

    public EmbeddingGateway bootstrapEmbedding() {
        return bootstrapEmbedding;
    }

    public ChatModel chatModelProxy() {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return requireChat().chatModel().doChat(request);
            }
        };
    }

    public StreamingChatModel streamingChatModelProxy() {
        return new StreamingChatModel() {
            @Override
            public void doChat(
                    ChatRequest request,
                    StreamingChatResponseHandler handler
            ) {
                requireChat().streamingChatModel().doChat(request, handler);
            }
        };
    }

    public EmbeddingGateway embeddingGatewayProxy() {
        return new EmbeddingGateway() {
            @Override
            public List<Double> embed(String text) {
                return requireEmbedding().gateway().embed(text);
            }

            @Override
            public List<List<Double>> embedAll(List<String> texts) {
                return requireEmbedding().gateway().embedAll(texts);
            }

            @Override
            public int dimension() {
                return requireEmbedding().gateway().dimension();
            }
        };
    }

    private BusinessException unavailable(String message) {
        return new BusinessException(ErrorCode.MODEL_CONFIG_UNAVAILABLE, message);
    }

    public record ChatContext(
            long configurationId,
            ChatModel chatModel,
            StreamingChatModel streamingChatModel
    ) {
        public ChatContext {
            if (configurationId <= 0) {
                throw new IllegalArgumentException("Chat configuration id must be positive");
            }
            chatModel = Objects.requireNonNull(chatModel, "chatModel");
            streamingChatModel = Objects.requireNonNull(
                    streamingChatModel, "streamingChatModel");
        }
    }
}
