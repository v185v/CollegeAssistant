package xyz.v185v.collegeassistant.ingestion.application;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.IngestRunDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunItemDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunLease;
import xyz.v185v.collegeassistant.ingestion.IngestRunLeaseLostException;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunItemRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import xyz.v185v.collegeassistant.admin.source.SourceConnectorType;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.document.SourceRepository;

/** 在同一记录锁上串行化数据源生命周期变更和持久化摄取运行创建。 */
@Component
public class IngestionRunCoordinator {

    private final SourceRepository sourceRepository;
    private final IngestRunRepository runRepository;
    private final IngestRunItemRepository itemRepository;
    private final IngestRunExpirationService expirationService;

    @Autowired
    public IngestionRunCoordinator(
            SourceRepository sourceRepository,
            IngestRunRepository runRepository,
            IngestRunItemRepository itemRepository,
            IngestRunExpirationService expirationService
    ) {
        this.sourceRepository = sourceRepository;
        this.runRepository = runRepository;
        this.itemRepository = itemRepository;
        this.expirationService = expirationService;
    }

/** 认领数据源并创建带租约的手动运行，然后释放数据源记录锁。 */
    @Transactional
    public ClaimedIngestionRun claimManual(Long sourceId, String reason, String workerId) {
        SourceRecord source = sourceRepository.lockForMutation(sourceId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SOURCE_NOT_FOUND, "Source not found"));
        if (!"NONE".equals(source.deletionStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Source is being deleted");
        }
        if (!source.enabled()) {
            throw new BusinessException(ErrorCode.SOURCE_NOT_FOUND, "Source not found");
        }
        expireStaleForSource(source.id());
        IngestRunLease lease = runRepository.startLease(
                source.id(), reason, "MANUAL", workerId);
        IngestRunDto running = runRepository.findById(lease.runId()).orElseThrow(() ->
                new BusinessException(ErrorCode.INGEST_FAILED, "Ingestion run could not be loaded"));
        return new ClaimedIngestionRun(source, running, lease);
    }

/** 仅在数据源仍启用且未进入删除时重新认领失败运行。 */
    @Transactional
    public ClaimedIngestionRun claimRetry(Long failedRunId, String workerId) {
        IngestRunDto previous = runRepository.findById(failedRunId).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Ingestion run not found"));
        SourceRecord source = sourceRepository.lockForMutation(previous.sourceId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SOURCE_NOT_FOUND, "Source not found"));
        if (!"NONE".equals(source.deletionStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Source is being deleted");
        }
        if (!source.enabled()) {
            throw new BusinessException(ErrorCode.SOURCE_NOT_FOUND, "Source not found");
        }
        IngestRunLease lease = runRepository.retryFailed(failedRunId, workerId);
        IngestRunDto running = runRepository.findById(lease.runId()).orElseThrow(() ->
                new BusinessException(ErrorCode.INGEST_FAILED, "Ingestion retry could not be loaded"));
        return new ClaimedIngestionRun(source, running, lease);
    }

/** 在锁定数据源变更记录期间创建 IMPORT 租约和所有稳定 FILE 条目。 */
    @Transactional
    public ClaimedImportRun claimImport(
            Long sourceId,
            String reason,
            List<String> clientIds,
            String workerId
    ) {
        SourceRecord source = sourceRepository.lockForMutation(sourceId).orElseThrow(() ->
                new BusinessException(ErrorCode.SOURCE_NOT_FOUND, "Source not found"));
        requireImportable(source);
        if (clientIds == null || clientIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Import files are required");
        }
        expireStaleForSource(source.id());
        IngestRunLease lease = runRepository.startLease(source.id(), reason, "IMPORT", workerId);
        List<IngestRunItemDto> items = new ArrayList<>(clientIds.size());
        for (String clientId : clientIds) {
            items.add(itemRepository.upsertItem(lease, clientId, "FILE")
                    .orElseThrow(IngestRunLeaseLostException::new));
        }
        IngestRunDto running = runRepository.findById(lease.runId()).orElseThrow(() ->
                new BusinessException(ErrorCode.INGEST_FAILED, "Import run could not be loaded"));
        return new ClaimedImportRun(source, running, lease, List.copyOf(items));
    }

/** 保留导入数据源锁，并携带复制的失败条目认领失败导入。 */
    @Transactional
    public ClaimedImportRun claimImportRetry(Long failedRunId, String workerId) {
        IngestRunDto previous = runRepository.findById(failedRunId).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Ingestion run not found"));
        SourceRecord source = sourceRepository.lockForMutation(previous.sourceId()).orElseThrow(() ->
                new BusinessException(ErrorCode.SOURCE_NOT_FOUND, "Source not found"));
        requireImportable(source);
        IngestRunLease lease = runRepository.retryFailedImport(failedRunId, workerId);
        IngestRunDto running = runRepository.findById(lease.runId()).orElseThrow(() ->
                new BusinessException(ErrorCode.INGEST_FAILED, "Import retry could not be loaded"));
        return new ClaimedImportRun(source, running, lease, itemRepository.findByRunId(lease.runId()));
    }

    private void requireImportable(SourceRecord source) {
        if (!"NONE".equals(source.deletionStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Source is being deleted");
        }
        if (!source.enabled()) {
            throw new BusinessException(ErrorCode.SOURCE_NOT_FOUND, "Source not found");
        }
        if (source.connectorType() != SourceConnectorType.LOCAL_UPLOAD) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Source does not accept local uploads");
        }
    }

    private void expireStaleForSource(Long sourceId) {
        expirationService.expireStaleForSource(sourceId, Duration.ofHours(1));
    }

    public record ClaimedIngestionRun(
            SourceRecord source,
            IngestRunDto running,
            IngestRunLease lease
    ) {
    }

    public record ClaimedImportRun(
            SourceRecord source,
            IngestRunDto running,
            IngestRunLease lease,
            List<IngestRunItemDto> items
    ) {
        public ClaimedImportRun {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
