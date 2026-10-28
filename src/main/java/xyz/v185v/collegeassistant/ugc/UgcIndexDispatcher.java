package xyz.v185v.collegeassistant.ugc;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/** 将 UGC 索引任务提交到有界后台执行器，并集中记录阶段化失败。 */
@Component
public class UgcIndexDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(UgcIndexDispatcher.class);

    private final UgcIndexingService indexingService;
    private final Executor executor;

    public UgcIndexDispatcher(
            UgcIndexingService indexingService,
            @Qualifier("ugcIndexTaskExecutor") Executor executor
    ) {
        this.indexingService = indexingService;
        this.executor = executor;
    }

    /**
     * 尝试提交索引任务。
     *
     * @return 成功进入执行器队列时为 {@code true}
     */
    public boolean dispatch(Long postId) {
        try {
            executor.execute(() -> runIndexing(postId));
            return true;
        } catch (RuntimeException exception) {
            logFailure(postId, "dispatch", ErrorCode.INGEST_FAILED, exception);
            return false;
        }
    }

    private void runIndexing(Long postId) {
        try {
            indexingService.indexApproved(postId);
        } catch (RuntimeException exception) {
            String stage = exception instanceof UgcIndexingException indexingException
                    ? indexingException.getStage().label()
                    : "unknown";
            int failureCode = exception instanceof BusinessException businessException
                    ? businessException.getCode()
                    : ErrorCode.INTERNAL_ERROR;
            logFailure(postId, stage, failureCode, exception);
        }
    }

    private void logFailure(
            Long postId,
            String stage,
            int failureCode,
            RuntimeException exception
    ) {
        Throwable cause = exception.getCause() == null
                ? exception
                : exception.getCause();
        LOGGER.warn(
                "UGC indexing operation failed: postId={} stage={} failureCode={} causeType={}",
                postId,
                stage,
                failureCode,
                cause.getClass().getName());
    }
}
