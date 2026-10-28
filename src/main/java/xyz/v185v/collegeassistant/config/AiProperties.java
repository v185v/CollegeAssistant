package xyz.v185v.collegeassistant.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 模型与向量库相关配置（对应 application.yaml 的 ai.* 节点）。
 *
 * <p>多模型可切换的核心：chat/embedding 各自通过 provider 选择厂商，
 * 业务层只依赖 LangChain4j 接口，不绑定具体 SDK。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    private Chat chat = new Chat();
    private Embedding embedding = new Embedding();

    /** 对话生成模型配置。 */
    @Getter
    @Setter
    public static class Chat {
        /** chat 模型厂商：dashscope | openai | deepseek | zhipu | ollama */
        private String provider = "ollama";
        private String model;
        private String apiKey;
        private String baseUrl;
        private Double temperature = 0.7;
    }

    /** 文本向量模型配置。 */
    @Getter
    @Setter
    public static class Embedding {
        /** embedding 模型厂商：dashscope | openai | ollama */
        private String provider = "ollama";
        private String model;
        private String apiKey;
        private String baseUrl;
        /** 向量维度，必须与所选 embedding 模型一致，且与 pgvector 列维度一致。 */
        private int dimension = 1536;
    }
}
