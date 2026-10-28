package xyz.v185v.collegeassistant.ingestion.application;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 数据摄取后台任务执行器配置。 */
@Configuration(proxyBeanMethods = false)
public class IngestionAsyncConfiguration {

    /** @return 具有有界队列和优雅停机策略的摄取任务执行器 */
    @Bean(name = "ingestionTaskExecutor")
    ThreadPoolTaskExecutor ingestionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ingestion-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
