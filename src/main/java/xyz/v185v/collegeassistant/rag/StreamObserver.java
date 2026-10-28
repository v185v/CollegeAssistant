package xyz.v185v.collegeassistant.rag;

/** 模型流式生成事件观察者。 */
public interface StreamObserver {

    /** 接收一段增量文本。 */
    void onDelta(String text);

    /** 接收模型提供的完整最终文本。 */
    void onComplete(String finalText);

    /** 接收生成失败。 */
    void onError(Throwable error);
}
