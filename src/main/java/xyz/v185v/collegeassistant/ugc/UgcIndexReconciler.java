package xyz.v185v.collegeassistant.ugc;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.common.ErrorCode;

/** 定时扫描并补投已审核通过但缺少向量分块的 UGC。 */
@Component
public class UgcIndexReconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(UgcIndexReconciler.class);

    private final UgcRepository ugcRepository;
    private final UgcIndexDispatcher dispatcher;
    private final int batchSize;
    private final AtomicLong cursor = new AtomicLong(0L);

    public UgcIndexReconciler(
            UgcRepository ugcRepository,
            UgcIndexDispatcher dispatcher,
            @Value("${app.ugc-index.reconcile-batch-size:50}") int batchSize
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("UGC reconciliation batch size must be positive");
        }
        this.ugcRepository = ugcRepository;
        this.dispatcher = dispatcher;
        this.batchSize = batchSize;
    }

    /** 按单调编号游标分批扫描缺失索引，扫到末尾后重置游标。 */
    @Scheduled(
            initialDelayString = "${app.ugc-index.reconcile-initial-delay-ms:60000}",
            fixedDelayString = "${app.ugc-index.reconcile-fixed-delay-ms:300000}"
    )
    public void reconcileMissingIndexes() {
        long afterId = cursor.get();
        List<Long> postIds;
        try {
            postIds = ugcRepository.findApprovedWithoutChunksAfter(afterId, batchSize);
        } catch (RuntimeException exception) {
            logFailure(null, "scan", exception);
            return;
        }

        if (postIds.isEmpty()) {
            cursor.compareAndSet(afterId, 0L);
            return;
        }
        Long lastId = postIds.getLast();
        if (lastId == null || lastId <= afterId) {
            logFailure(
                    lastId,
                    "cursor",
                    new IllegalStateException("Reconciliation scan returned an invalid cursor"));
            return;
        }

        for (Long postId : postIds) {
            try {
                dispatcher.dispatch(postId);
            } catch (RuntimeException exception) {
                logFailure(postId, "dispatch", exception);
            }
        }
        cursor.compareAndSet(afterId, lastId);
    }

    private void logFailure(Long postId, String stage, RuntimeException exception) {
        Throwable cause = exception.getCause() == null
                ? exception
                : exception.getCause();
        LOGGER.warn(
                "UGC reconciliation operation failed: postId={} stage={} failureCode={} causeType={}",
                postId,
                stage,
                ErrorCode.INGEST_FAILED,
                cause.getClass().getName());
    }
}
