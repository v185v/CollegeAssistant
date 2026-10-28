package xyz.v185v.collegeassistant.chat;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时分批清理因超时、进程中断等原因遗留的空助手消息占位。 */
@Component
public class ChatDraftReconciler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ChatDraftReconciler.class);

    private final ChatRepository repository;
    private final ChatProperties properties;
    private final Clock clock;

    @Autowired
    public ChatDraftReconciler(
            ChatRepository repository,
            ChatProperties properties
    ) {
        this(repository, properties, Clock.systemUTC());
    }

    ChatDraftReconciler(
            ChatRepository repository,
            ChatProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${app.chat.draft-cleanup-period:PT5M}",
            fixedDelayString = "${app.chat.draft-cleanup-period:PT5M}"
    )
    /** 按配置 TTL 和批量上限删除过期占位，失败时仅记录日志以保持调度器存活。 */
    public void cleanupStalePlaceholders() {
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(
                clock.instant().minus(properties.draftTtl()), ZoneOffset.UTC);
        try {
            repository.deleteStaleAssistantPlaceholders(
                    cutoff, properties.draftCleanupBatchSize());
        } catch (RuntimeException exception) {
            Throwable cause = exception.getCause() == null
                    ? exception : exception.getCause();
            LOGGER.warn(
                    "Chat draft cleanup failed: stage=delete-stale causeType={}",
                    cause.getClass().getName());
        }
    }
}
