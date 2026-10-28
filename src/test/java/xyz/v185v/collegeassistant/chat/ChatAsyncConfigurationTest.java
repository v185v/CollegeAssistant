package xyz.v185v.collegeassistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ChatAsyncConfigurationTest {

    @Test
    void chatExecutorUsesSmallPoolBoundedQueueAndAbortPolicy()
            throws InterruptedException {
        ExecutorService service =
                new ChatAsyncConfiguration().chatStreamExecutor();

        assertThat(service).isNotNull();
        assertThat(service).isInstanceOf(ThreadPoolExecutor.class);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) service;
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaximumPoolSize()).isEqualTo(4);
            assertThat(executor.getKeepAliveTime(TimeUnit.SECONDS))
                    .isEqualTo(60L);
            assertThat(executor.getQueue())
                    .isInstanceOf(ArrayBlockingQueue.class);
            assertThat(executor.getQueue().remainingCapacity()).isEqualTo(100);
            assertThat(executor.getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
            AtomicReference<String> threadName = new AtomicReference<>();
            CountDownLatch executed = new CountDownLatch(1);
            executor.execute(() -> {
                threadName.set(Thread.currentThread().getName());
                executed.countDown();
            });
            assertThat(executed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(threadName.get()).startsWith("chat-stream-");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void springContextPublishesNamedExecutorAndShutsItDownOnClose() {
        ExecutorService executor;
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(
                             ChatAsyncConfiguration.class)) {
            var executors = context.getBeansOfType(ExecutorService.class);
            assertThat(executors).containsOnlyKeys("chatStreamExecutor");

            ExecutorService byName = context.getBean(
                    "chatStreamExecutor", ExecutorService.class);
            ExecutorService byType = context.getBean(ExecutorService.class);
            executor = executors.get("chatStreamExecutor");
            assertThat(byName).isSameAs(executor);
            assertThat(byType).isSameAs(executor);
            assertThat(executor.isShutdown()).isFalse();
        }

        assertThat(executor.isShutdown()).isTrue();
    }
}
