package xyz.v185v.collegeassistant.rag;

/** 控制完整聊天流生命周期的句柄。 */
public interface ChatStreamControl {

    /** 请求取消后台工作、模型生成和未完成消息。 */
    void cancel();

    /** @return 是否已接受取消请求 */
    boolean isCancelled();
}
