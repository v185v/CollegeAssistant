package xyz.v185v.collegeassistant.chat;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 聊天流和草稿清理配置。
 *
 * @param streamTimeout         SSE 流超时时间
 * @param draftTtl              未完成助手消息保留时间
 * @param draftCleanupBatchSize 单次清理上限
 * @param draftCleanupPeriod    清理调度周期
 * @param memoryMessageLimit    RAG 记忆消息上限
 * @param detailMessageLimit    会话详情消息上限
 */
@ConfigurationProperties(prefix = "app.chat")
public record ChatProperties(
        Duration streamTimeout,
        Duration draftTtl,
        Integer draftCleanupBatchSize,
        Duration draftCleanupPeriod,
        Integer memoryMessageLimit,
        Integer detailMessageLimit
) {

    static final Duration DEFAULT_STREAM_TIMEOUT = Duration.ofMinutes(2);
    static final Duration DEFAULT_DRAFT_TTL = Duration.ofHours(1);
    static final int DEFAULT_DRAFT_CLEANUP_BATCH_SIZE = 100;
    static final Duration DEFAULT_DRAFT_CLEANUP_PERIOD = Duration.ofMinutes(5);
    static final int DEFAULT_MEMORY_MESSAGE_LIMIT = 8;
    static final int DEFAULT_DETAIL_MESSAGE_LIMIT = 200;

    public ChatProperties {
        streamTimeout = streamTimeout == null
                ? DEFAULT_STREAM_TIMEOUT : streamTimeout;
        draftTtl = draftTtl == null ? DEFAULT_DRAFT_TTL : draftTtl;
        draftCleanupBatchSize = draftCleanupBatchSize == null
                ? DEFAULT_DRAFT_CLEANUP_BATCH_SIZE : draftCleanupBatchSize;
        draftCleanupPeriod = draftCleanupPeriod == null
                ? DEFAULT_DRAFT_CLEANUP_PERIOD : draftCleanupPeriod;
        memoryMessageLimit = memoryMessageLimit == null
                ? DEFAULT_MEMORY_MESSAGE_LIMIT : memoryMessageLimit;
        detailMessageLimit = detailMessageLimit == null
                ? DEFAULT_DETAIL_MESSAGE_LIMIT : detailMessageLimit;
        requirePositive(streamTimeout, "Chat stream timeout");
        requirePositive(draftTtl, "Chat draft TTL");
        requirePositive(draftCleanupPeriod, "Chat draft cleanup period");
        if (draftTtl.compareTo(streamTimeout) <= 0) {
            throw new IllegalArgumentException(
                    "Chat draft TTL must be longer than the stream timeout");
        }
        if (draftCleanupBatchSize < 1 || draftCleanupBatchSize > 1_000) {
            throw new IllegalArgumentException(
                    "Chat draft cleanup batch size must be between 1 and 1000");
        }
        if (memoryMessageLimit < 1 || memoryMessageLimit > 50) {
            throw new IllegalArgumentException(
                    "Chat memory message limit must be between 1 and 50");
        }
        if (detailMessageLimit < 1 || detailMessageLimit > 500) {
            throw new IllegalArgumentException(
                    "Chat detail message limit must be between 1 and 500");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
