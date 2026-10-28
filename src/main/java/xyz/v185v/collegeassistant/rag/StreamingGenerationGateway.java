package xyz.v185v.collegeassistant.rag;

/** 与模型厂商无关的流式文本生成端口。 */
public interface StreamingGenerationGateway {

    /**
     * 启动生成并通过观察者异步报告事件。
     *
     * @param prompt   生成提示
     * @param observer 事件观察者
     * @return 模型流控制柄
     */
    StreamControl generate(GenerationPrompt prompt, StreamObserver observer);
}
