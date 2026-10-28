package xyz.v185v.collegeassistant.ugc;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/** 携带帖子编号和失败阶段的 UGC 索引业务异常。 */
public final class UgcIndexingException extends BusinessException {

    private final Long postId;
    private final UgcIndexingStage stage;

    public UgcIndexingException(
            Long postId,
            UgcIndexingStage stage,
            RuntimeException cause
    ) {
        super(
                ErrorCode.INGEST_FAILED,
                "UGC indexing failed at " + stage.label() + " stage for postId=" + postId,
                cause);
        this.postId = postId;
        this.stage = stage;
    }

    /** @return 索引失败的帖子编号 */
    public Long getPostId() {
        return postId;
    }

    /** @return 索引失败阶段 */
    public UgcIndexingStage getStage() {
        return stage;
    }
}
