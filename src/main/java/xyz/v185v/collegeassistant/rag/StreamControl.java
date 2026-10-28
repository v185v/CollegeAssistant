package xyz.v185v.collegeassistant.rag;

/** 控制底层模型流式生成的句柄。 */
public interface StreamControl {

    /** 请求停止模型生成。 */
    void cancel();

    /** @return 是否已经取消 */
    boolean isCancelled();
}
