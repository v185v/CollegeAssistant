package xyz.v185v.collegeassistant.ingestion;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunItemRepository;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 摄取尝试发布文档/向量时使用的记录锁栅栏。 */
@Component
public class IngestRunLeaseGuard {

    private final IngestRunRepository runRepository;
    private final IngestRunItemRepository itemRepository;
    private final TransactionTemplate transactions;

    public IngestRunLeaseGuard(
            IngestRunRepository runRepository,
            IngestRunItemRepository itemRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.runRepository = runRepository;
        this.itemRepository = itemRepository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void assertActive(IngestRunLease lease) {
        if (!runRepository.isActive(lease)) {
            throw new IngestRunLeaseLostException();
        }
    }

    public <T> T inTransaction(
            IngestRunLease lease,
            Function<LeaseGuardedIngestOperations, T> work
    ) {
        Objects.requireNonNull(work, "work");
        return transactions.execute(status -> {
            if (!runRepository.lockAndAssertActive(lease)) {
                throw new IngestRunLeaseLostException();
            }
            LeaseGuardedIngestOperations operations = (itemId, itemStatus, documentId, checkpoint) -> {
                if (!itemRepository.checkpoint(
                        lease, itemId, itemStatus, documentId,
                        checkpoint == null ? Map.of() : checkpoint)) {
                    throw new IngestRunLeaseLostException();
                }
            };
            return work.apply(operations);
        });
    }
}
