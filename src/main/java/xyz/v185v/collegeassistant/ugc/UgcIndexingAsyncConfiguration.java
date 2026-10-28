package xyz.v185v.collegeassistant.ugc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** UGC 索引执行器与缺失索引定时补偿配置。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class UgcIndexingAsyncConfiguration {

    /** @return 使用有界队列并支持优雅停机的 UGC 索引执行器 */
    @Bean(name = "ugcIndexTaskExecutor")
    ThreadPoolTaskExecutor ugcIndexTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ugc-index-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
