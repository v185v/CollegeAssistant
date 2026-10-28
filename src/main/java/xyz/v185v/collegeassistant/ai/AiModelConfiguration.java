package xyz.v185v.collegeassistant.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import xyz.v185v.collegeassistant.config.AiProperties;
import xyz.v185v.collegeassistant.rag.StreamingGenerationGateway;

/**
 * AI 模型及应用层网关的 Spring 装配配置。
 */
@Configuration(proxyBeanMethods = false)
public class AiModelConfiguration {

    @Bean
    ModelFactory modelFactory(AiProperties properties) {
        return new ModelFactory(properties);
    }

    @Bean
    EmbeddingModel embeddingModel(ModelFactory factory) {
        return factory.embeddingModel();
    }

    @Bean
    ModelRegistry modelRegistry(ModelFactory factory, EmbeddingModel embeddingModel) {
        return new ModelRegistry(
                new ModelRegistry.ChatContext(
                        1L, factory.chatModel(), factory.streamingChatModel()),
                new LangChain4jEmbeddingGateway(embeddingModel));
    }

    @Bean
    ChatModel chatModel(ModelRegistry registry) {
        return registry.chatModelProxy();
    }

    @Bean
    StreamingChatModel streamingChatModel(ModelRegistry registry) {
        return registry.streamingChatModelProxy();
    }

    @Bean
    EmbeddingGateway embeddingGateway(ModelRegistry registry) {
        return registry.embeddingGatewayProxy();
    }

    @Bean
    StreamingGenerationGateway streamingGenerationGateway(
            StreamingChatModel streamingChatModel
    ) {
        return new LangChain4jStreamingGenerationGateway(streamingChatModel);
    }
}
