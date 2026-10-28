package xyz.v185v.collegeassistant.ugc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class UgcApprovedEventTransactionTest {

    private AnnotationConfigApplicationContext context;
    private UgcIndexDispatcher dispatcher;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        dispatcher = mock(UgcIndexDispatcher.class);
        context.register(TransactionInfrastructure.class);
        context.registerBean(PlatformTransactionManager.class, TestTransactionManager::new);
        context.registerBean(UgcIndexDispatcher.class, () -> dispatcher);
        context.registerBean(UgcApprovedEventListener.class);
        context.refresh();
        transactionTemplate = new TransactionTemplate(
                context.getBean(PlatformTransactionManager.class));
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void committedEventDispatchesExactlyOnceAndOnlyAfterCommit() {
        transactionTemplate.executeWithoutResult(status -> {
            context.publishEvent(new UgcApprovedEvent(41L));
            verifyNoInteractions(dispatcher);
        });

        verify(dispatcher, times(1)).dispatch(41L);
    }

    @Test
    void rolledBackEventIsNeverDispatched() {
        transactionTemplate.executeWithoutResult(status -> {
            context.publishEvent(new UgcApprovedEvent(42L));
            status.setRollbackOnly();
        });

        verify(dispatcher, never()).dispatch(42L);
    }

    private static final class TestTransactionManager
            extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionInfrastructure {
    }
}
