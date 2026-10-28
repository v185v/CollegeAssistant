package xyz.v185v.collegeassistant.ugc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

class UgcIndexingAsyncConfigurationTest {

    @Test
    void ugcExecutorUsesASmallPoolBoundedQueueAndGracefulShutdown() {
        ThreadPoolTaskExecutor executor = new UgcIndexingAsyncConfiguration()
                .ugcIndexTaskExecutor();

        assertThat(executor.getCorePoolSize()).isEqualTo(1);
        assertThat(executor.getMaxPoolSize()).isEqualTo(2);
        assertThat(executor.getQueueCapacity()).isBetween(1, 100);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("ugc-index-");
        assertThat(ReflectionTestUtils.getField(
                executor, "waitForTasksToCompleteOnShutdown")).isEqualTo(true);
        assertThat((Long) ReflectionTestUtils.getField(
                executor, "awaitTerminationMillis")).isGreaterThan(0L);
    }
}
