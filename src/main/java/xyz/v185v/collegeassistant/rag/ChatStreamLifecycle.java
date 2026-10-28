package xyz.v185v.collegeassistant.rag;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 线程安全的聊天流生命周期协调器。
 *
 * <p>保证完成、失败和取消只能进入一次终态，并以尽力而为方式传播取消到工作线程、
 * 模型提供方和助手消息占位记录。重入取消操作会延迟到生命周期锁释放后执行。</p>
 */
final class ChatStreamLifecycle implements ChatStreamControl {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ChatStreamLifecycle.class);
    private static final String ACTION_WORKER_CANCEL = "worker.cancel";
    private static final String ACTION_PROVIDER_CANCEL = "provider.cancel";
    private static final String ACTION_ASSISTANT_DISCARD = "assistant.discard";

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicBoolean cleanupStarted = new AtomicBoolean();
    private final AtomicBoolean completionFailureHandled =
            new AtomicBoolean();
    private final AtomicReference<Future<?>> worker = new AtomicReference<>();
    private final AtomicReference<StreamControl> provider =
            new AtomicReference<>();
    private final Runnable cancellationCleanup;
    private final Object lifecycleLock = new Object();
    private CancellationActions deferredCancellation;

    ChatStreamLifecycle(Runnable cancellationCleanup) {
        this.cancellationCleanup = cancellationCleanup;
    }

    /** 原子进入取消终态并触发关联资源清理。 */
    @Override
    public void cancel() {
        boolean reentrant = Thread.holdsLock(lifecycleLock);
        CancellationActions actions;
        synchronized (lifecycleLock) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            cancelled.set(true);
            actions = cancellationActionsLocked();
            if (reentrant) {
                deferredCancellation = actions;
            }
        }
        if (!reentrant) {
            runCancellationActions(actions);
        }
    }

/** 继承父类型的文档说明。 */
    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    boolean isActive() {
        synchronized (lifecycleLock) {
            return !terminal.get();
        }
    }

    boolean emitIfActive(Runnable emission) {
        try {
            synchronized (lifecycleLock) {
                if (terminal.get()) {
                    return false;
                }
                emission.run();
                return true;
            }
        } finally {
            drainDeferredCancellationIfUnlocked();
        }
    }

    boolean beginCompletion() {
        synchronized (lifecycleLock) {
            return terminal.compareAndSet(false, true);
        }
    }

    boolean fail() {
        CancellationActions actions;
        synchronized (lifecycleLock) {
            if (!terminal.compareAndSet(false, true)) {
                return false;
            }
            actions = cancellationActionsLocked();
        }
        runCancellationActions(actions);
        return true;
    }

    boolean failAfterCompletion() {
        CancellationActions actions;
        synchronized (lifecycleLock) {
            if (!completionFailureHandled.compareAndSet(false, true)) {
                return false;
            }
            actions = cancellationActionsLocked();
        }
        runCancellationActions(actions);
        return true;
    }

    void attachWorker(Future<?> future) {
        boolean shouldCancel;
        synchronized (lifecycleLock) {
            worker.set(future);
            shouldCancel = terminal.get();
        }
        if (shouldCancel) {
            runBestEffort(
                    ACTION_WORKER_CANCEL,
                    () -> future.cancel(true));
        }
    }

    void attachProvider(StreamControl control) {
        boolean shouldCancel;
        synchronized (lifecycleLock) {
            provider.set(control);
            shouldCancel = terminal.get();
        }
        if (shouldCancel) {
            runBestEffort(ACTION_PROVIDER_CANCEL, control::cancel);
        }
    }

    static void runBestEffort(String action, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException exception) {
            logBestEffortFailure(action, exception);
        }
    }

    static void logBestEffortFailure(
            String action,
            RuntimeException exception
    ) {
        LOGGER.warn(
                "RAG best-effort action failed action={} causeType={}",
                action,
                exception.getClass().getName());
    }

    private CancellationActions cancellationActionsLocked() {
        return new CancellationActions(
                worker.get(),
                provider.get(),
                cleanupStarted.compareAndSet(false, true));
    }

    private void runCancellationActions(CancellationActions actions) {
        if (actions.worker() != null) {
            runBestEffort(
                    ACTION_WORKER_CANCEL,
                    () -> actions.worker().cancel(true));
        }
        if (actions.provider() != null) {
            runBestEffort(
                    ACTION_PROVIDER_CANCEL,
                    actions.provider()::cancel);
        }
        if (actions.cleanup()) {
            runBestEffort(
                    ACTION_ASSISTANT_DISCARD,
                    cancellationCleanup);
        }
    }

    private void drainDeferredCancellationIfUnlocked() {
        if (Thread.holdsLock(lifecycleLock)) {
            return;
        }
        CancellationActions actions;
        synchronized (lifecycleLock) {
            actions = deferredCancellation;
            deferredCancellation = null;
        }
        if (actions != null) {
            runCancellationActions(actions);
        }
    }

    private record CancellationActions(
            Future<?> worker,
            StreamControl provider,
            boolean cleanup
    ) {
    }
}
