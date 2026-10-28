package xyz.v185v.collegeassistant.ugc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import xyz.v185v.collegeassistant.common.ErrorCode;

/** 在 UGC 审核事务提交后异步投递向量索引任务。 */
@Component
public class UgcApprovedEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(UgcApprovedEventListener.class);

    private final UgcIndexDispatcher dispatcher;

    public UgcApprovedEventListener(UgcIndexDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** 处理审核通过事件，避免回滚事务产生不可见内容的索引。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApproved(UgcApprovedEvent event) {
        try {
            dispatcher.dispatch(event.postId());
        } catch (RuntimeException exception) {
            Throwable cause = exception.getCause() == null
                    ? exception
                    : exception.getCause();
            LOGGER.error(
                    "UGC approval dispatch failed: postId={} stage={} failureCode={} causeType={}",
                    event.postId(),
                    "listener-dispatch",
                    ErrorCode.INTERNAL_ERROR,
                    cause.getClass().getName());
        }
    }
}
