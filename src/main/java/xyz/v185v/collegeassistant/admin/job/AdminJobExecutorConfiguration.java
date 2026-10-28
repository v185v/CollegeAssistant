package xyz.v185v.collegeassistant.admin.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 仅在队列认领提交后使用的有界执行器。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class AdminJobExecutorConfiguration {

    @Bean(name = "adminJobTaskExecutor")
    ThreadPoolTaskExecutor adminJobTaskExecutor(
            @Value("${app.admin.jobs.executor-core-size:1}") int coreSize,
            @Value("${app.admin.jobs.executor-max-size:2}") int maxSize,
            @Value("${app.admin.jobs.executor-queue-capacity:32}") int queueCapacity
    ) {
        if (coreSize < 1 || maxSize < coreSize || queueCapacity < 1) {
            throw new IllegalArgumentException("Invalid administration job executor configuration");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("admin-job-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
