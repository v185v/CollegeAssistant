package xyz.v185v.collegeassistant.admin.job;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 通过记录锁和完整的尝试/令牌租约谓词保护领域变更。
 */
@Component
public class AdminJobLeaseGuard {

    private final AdminJobRepository repository;
    private final TransactionTemplate transactions;

    public AdminJobLeaseGuard(
            AdminJobRepository repository,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void assertActive(AdminJobLease lease) {
        if (!repository.isActive(lease)) {
            throw new AdminJobLeaseLostException();
        }
    }

    public <T> T inTransaction(
            AdminJobLease lease,
            Function<LeaseGuardedJobOperations, T> work
    ) {
        Objects.requireNonNull(work, "work");
        return transactions.execute(status -> {
            if (!repository.lockAndAssertActive(lease)) {
                throw new AdminJobLeaseLostException();
            }
            LeaseGuardedJobOperations operations = (itemId, itemStatus, checkpoint) -> {
                if (!repository.checkpoint(
                        lease, itemId, itemStatus,
                        checkpoint == null ? Map.of() : checkpoint)) {
                    throw new AdminJobLeaseLostException();
                }
            };
            return work.apply(operations);
        });
    }
}
