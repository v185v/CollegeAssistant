package xyz.v185v.collegeassistant.rag;

import xyz.v185v.collegeassistant.chat.ChatDeltaDto;
import xyz.v185v.collegeassistant.chat.ChatDoneDto;
import xyz.v185v.collegeassistant.chat.ChatStreamMetaDto;
import xyz.v185v.collegeassistant.chat.SourceCitationDto;
import xyz.v185v.collegeassistant.common.ApiResponse;

/** RAG 编排器向传输层发送结构化聊天事件的端口。 */
public interface ChatEventSink {

    /** 发送会话和意图元数据。 */
    void meta(ChatStreamMetaDto event);

    /** 发送答案文本增量。 */
    void delta(ChatDeltaDto event);

    /** 发送一条来源引用。 */
    void source(SourceCitationDto event);

    /** 发送最终答案。 */
    void done(ChatDoneDto event);

    /** 发送结构化错误。 */
    void error(ApiResponse<Void> event);

    /** 正常结束传输。 */
    void complete();
}
