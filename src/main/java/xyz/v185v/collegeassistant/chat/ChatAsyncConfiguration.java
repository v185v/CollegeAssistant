package xyz.v185v.collegeassistant.chat;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 聊天流后台编排线程池配置。 */
@Configuration(proxyBeanMethods = false)
public class ChatAsyncConfiguration {

    /** @return 使用有界队列和拒绝策略的聊天流执行器 */
    @Bean(name = "chatStreamExecutor", destroyMethod = "shutdown")
    ExecutorService chatStreamExecutor() {
        return new ThreadPoolExecutor(
                2,
                4,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100),
                chatThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private ThreadFactory chatThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> new Thread(
                task, "chat-stream-" + sequence.incrementAndGet());
    }
}
