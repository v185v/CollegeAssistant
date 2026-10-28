package xyz.v185v.collegeassistant.ingestion.application;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.crawl.CrawlContext;
import xyz.v185v.collegeassistant.ingestion.crawl.CrawlDeferredException;
import xyz.v185v.collegeassistant.ingestion.crawl.CrawledDocument;
import xyz.v185v.collegeassistant.ingestion.IngestRunDetailDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunExecutionContext;
import xyz.v185v.collegeassistant.ingestion.IngestRunItemDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunItemStatus;
import xyz.v185v.collegeassistant.ingestion.IngestRunLease;
import xyz.v185v.collegeassistant.ingestion.IngestRunLeaseGuard;
import xyz.v185v.collegeassistant.ingestion.IngestRunLeaseHeartbeatScheduler;
import xyz.v185v.collegeassistant.ingestion.IngestRunLeaseLostException;
import xyz.v185v.collegeassistant.ingestion.IngestRunStatus;
import xyz.v185v.collegeassistant.ingestion.LeaseGuardedIngestOperations;
import xyz.v185v.collegeassistant.ingestion.RunIngestRequest;
import xyz.v185v.collegeassistant.ingestion.ScheduledIngestClaim;
import xyz.v185v.collegeassistant.ingestion.crawl.SourceCrawler;
import xyz.v185v.collegeassistant.ingestion.parsing.DocumentParser;
import xyz.v185v.collegeassistant.ingestion.parsing.ParsedDocument;
import xyz.v185v.collegeassistant.ingestion.parsing.PreparedEmbeddingChunk;
import xyz.v185v.collegeassistant.ingestion.parsing.TextChunk;
import xyz.v185v.collegeassistant.ingestion.parsing.TextChunker;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunItemRepository;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import xyz.v185v.collegeassistant.admin.audit.AdminAuditEvent;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditService;
import xyz.v185v.collegeassistant.admin.model.EmbeddingMaintenanceGuard;
import xyz.v185v.collegeassistant.ai.EmbeddingGateway;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.document.DocumentRecord;
import xyz.v185v.collegeassistant.document.DocumentReferenceType;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.DocumentWriteResult;
import xyz.v185v.collegeassistant.document.DocumentWriteStatus;
import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.storage.ObjectStorage;
import xyz.v185v.collegeassistant.storage.StoredObject;

/**
 * 数据摄取应用服务。
 *
 * <p>编排抓取、解析、分块、向量化、对象存储、增量写入和运行状态记录，
 * 并在部分步骤失败时执行对象补偿清理。</p>
 */
@Service
public class IngestionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionService.class);
    private static final String DOCUMENT_NAMESPACE = "documents";
    private static final String OFFICIAL = "OFFICIAL";
    private static final String GENERAL = "GENERAL";
    private static final int MAX_FILENAME_UTF8_BYTES = 180;
    private static final int MAX_CLEANUP_ATTEMPTS = 3;

    private final SourceRepository sourceRepository;
    private final DocumentRepository documentRepository;
    private final IngestRunRepository runRepository;
    private final SourceCrawler crawler;
    private final DocumentParser parser;
    private final TextChunker chunker;
    private final EmbeddingGateway embeddingGateway;
    private final ObjectStorage objectStorage;
    private final DocumentIndexWriter indexWriter;
    private final IngestRunItemRepository itemRepository;
    private final IngestRunLeaseGuard leaseGuard;
    private final IngestRunLeaseHeartbeatScheduler leaseHeartbeatScheduler;
    private final IngestionRunCoordinator runCoordinator;
    private final DocumentIngestionPipeline pipeline;
    private final Executor executor;
    private final AdminAuditService auditService;
    private final String workerId;
    private EmbeddingMaintenanceGuard maintenanceGuard;

    @Autowired
    public IngestionService(
            SourceRepository sourceRepository,
            DocumentRepository documentRepository,
            IngestRunRepository runRepository,
            SourceCrawler crawler,
            DocumentParser parser,
            TextChunker chunker,
            EmbeddingGateway embeddingGateway,
            ObjectStorage objectStorage,
            DocumentIndexWriter indexWriter,
            IngestRunItemRepository itemRepository,
            IngestRunLeaseGuard leaseGuard,
            IngestRunLeaseHeartbeatScheduler leaseHeartbeatScheduler,
            IngestionRunCoordinator runCoordinator,
            DocumentIngestionPipeline pipeline,
            @Qualifier("ingestionTaskExecutor") Executor executor,
            AdminAuditService auditService
    ) {
        this.sourceRepository = sourceRepository;
        this.documentRepository = documentRepository;
        this.runRepository = runRepository;
        this.crawler = crawler;
        this.parser = parser;
        this.chunker = chunker;
        this.embeddingGateway = embeddingGateway;
        this.objectStorage = objectStorage;
        this.indexWriter = indexWriter;
        this.itemRepository = itemRepository;
        this.leaseGuard = leaseGuard;
        this.leaseHeartbeatScheduler = leaseHeartbeatScheduler;
        this.runCoordinator = runCoordinator;
        this.pipeline = pipeline;
        this.executor = executor;
        this.auditService = auditService;
        this.workerId = "ingestion-" + UUID.randomUUID();
    }

    @Autowired
    public void setEmbeddingMaintenanceGuard(EmbeddingMaintenanceGuard maintenanceGuard) {
        this.maintenanceGuard = maintenanceGuard;
    }

/** 保留原完整依赖接口的兼容构造函数。 */
    public IngestionService(
            SourceRepository sourceRepository,
            DocumentRepository documentRepository,
            IngestRunRepository runRepository,
            SourceCrawler crawler,
            DocumentParser parser,
            TextChunker chunker,
            EmbeddingGateway embeddingGateway,
            ObjectStorage objectStorage,
            DocumentIndexWriter indexWriter,
            IngestRunItemRepository itemRepository,
            IngestRunLeaseGuard leaseGuard,
            IngestRunLeaseHeartbeatScheduler leaseHeartbeatScheduler,
            IngestionRunCoordinator runCoordinator,
            DocumentIngestionPipeline pipeline,
            Executor executor
    ) {
        this(
                sourceRepository, documentRepository, runRepository, crawler, parser, chunker,
                embeddingGateway, objectStorage, indexWriter, itemRepository, leaseGuard,
                leaseHeartbeatScheduler, runCoordinator, pipeline, executor, null);
    }

/** 为不使用数据源记录协调器的针对性持久化摄取测试保留的构造函数。 */
    public IngestionService(
            SourceRepository sourceRepository,
            DocumentRepository documentRepository,
            IngestRunRepository runRepository,
            SourceCrawler crawler,
            DocumentParser parser,
            TextChunker chunker,
            EmbeddingGateway embeddingGateway,
            ObjectStorage objectStorage,
            DocumentIndexWriter indexWriter,
            IngestRunItemRepository itemRepository,
            IngestRunLeaseGuard leaseGuard,
            IngestRunLeaseHeartbeatScheduler leaseHeartbeatScheduler,
            Executor executor
    ) {
        this(
                sourceRepository, documentRepository, runRepository, crawler, parser, chunker,
                embeddingGateway, objectStorage, indexWriter, itemRepository, leaseGuard,
                leaseHeartbeatScheduler, null,
                new DocumentIngestionPipeline(
                        documentRepository, parser, chunker, embeddingGateway, objectStorage, indexWriter),
                executor);
    }

    /**
* 针对性单元测试使用的兼容构造函数，其协作者执行租约前代码路径。
* 上面的 Spring 管理构造函数始终启用持久化栅栏。
     */
    public IngestionService(
            SourceRepository sourceRepository,
            DocumentRepository documentRepository,
            IngestRunRepository runRepository,
            SourceCrawler crawler,
            DocumentParser parser,
            TextChunker chunker,
            EmbeddingGateway embeddingGateway,
            ObjectStorage objectStorage,
            DocumentIndexWriter indexWriter,
            Executor executor
    ) {
        this(
                sourceRepository, documentRepository, runRepository, crawler, parser, chunker,
                embeddingGateway, objectStorage, indexWriter, executor, null);
    }

/** 带显式审计边界、供针对性测试使用的租约前构造函数。 */
    public IngestionService(
            SourceRepository sourceRepository,
            DocumentRepository documentRepository,
            IngestRunRepository runRepository,
            SourceCrawler crawler,
            DocumentParser parser,
            TextChunker chunker,
            EmbeddingGateway embeddingGateway,
            ObjectStorage objectStorage,
            DocumentIndexWriter indexWriter,
            Executor executor,
            AdminAuditService auditService
    ) {
        this.sourceRepository = sourceRepository;
        this.documentRepository = documentRepository;
        this.runRepository = runRepository;
        this.crawler = crawler;
        this.parser = parser;
        this.chunker = chunker;
        this.embeddingGateway = embeddingGateway;
        this.objectStorage = objectStorage;
        this.indexWriter = indexWriter;
        this.itemRepository = null;
        this.leaseGuard = null;
        this.leaseHeartbeatScheduler = null;
        this.runCoordinator = null;
        this.pipeline = new DocumentIngestionPipeline(
                documentRepository, parser, chunker, embeddingGateway, objectStorage, indexWriter);
        this.executor = executor;
        this.auditService = auditService;
        this.workerId = "ingestion-test";
    }

    /**
     * 校验管理员权限并异步启动一次摄取运行。
     *
     * @param principal 当前登录管理员
     * @param sourceId  数据源编号
     * @param request   运行参数
     * @return 已进入运行态的任务记录
     */
    public IngestRunDto start(
            AuthPrincipal principal,
            Long sourceId,
            RunIngestRequest request
    ) {
        PreparedRun prepared = prepareAuditedRun(principal, sourceId, request);
        registerLease(prepared);
        try {
            executor.execute(() -> executeInBackground(prepared));
        } catch (RuntimeException exception) {
            finishAfterSubmissionFailure(prepared, exception);
            unregisterLease(prepared);
            throw new BusinessException(
                    ErrorCode.INGEST_FAILED,
                    "Ingestion could not be scheduled",
                    exception);
        }
        return prepared.running();
    }

/** 将事务已提交的计划数据源认领结果提交到有界摄取线程池。 */
    public IngestRunDto submitClaimed(ScheduledIngestClaim claim) {
        return submitClaim(claim, "scheduled ingestion");
    }

/** 恢复事务认领的延迟运行，不创建另一条持久化运行记录。 */
    public IngestRunDto resume(ScheduledIngestClaim claim) {
        return submitClaim(claim, "deferred retry");
    }

    private IngestRunDto submitClaim(ScheduledIngestClaim claim, String reason) {
        if (claim == null) {
            throw new IllegalArgumentException("Ingestion claim is required");
        }
        requireWritesAllowed();
        if (!durableLeasesEnabled()) {
            throw new BusinessException(
                    ErrorCode.UNSUPPORTED_OPERATION,
                    "Durable scheduled ingestion is unavailable");
        }
        PreparedRun prepared = new PreparedRun(
                claim.source(),
                new RunIngestRequest(false, false, reason),
                claim.running(),
                claim.lease(),
                claim.retryItems(),
                null);
        leaseHeartbeatScheduler.register(claim.lease());
        try {
            executor.execute(() -> executeInBackground(prepared));
        } catch (RuntimeException exception) {
            finishAfterSubmissionFailure(prepared, exception);
            unregisterLease(prepared);
            throw new BusinessException(
                    ErrorCode.INGEST_FAILED,
                    "Claimed ingestion could not be scheduled",
                    exception);
        }
        return claim.running();
    }

    /**
     * 在当前线程同步执行一次摄取，主要用于内部调用和测试。
     *
     * @param principal 当前登录管理员
     * @param sourceId  数据源编号
     * @param request   运行参数
     * @return 任务的最终运行记录
     */
    public IngestRunDto run(
            AuthPrincipal principal,
            Long sourceId,
            RunIngestRequest request
    ) {
        PreparedRun prepared = prepareAuditedRun(principal, sourceId, request);
        registerLease(prepared);
        try {
            return executeRun(prepared);
        } finally {
            unregisterLease(prepared);
        }
    }

    private PreparedRun prepareRun(
            AuthPrincipal principal,
            Long sourceId,
            RunIngestRequest request
    ) {
        requireAdmin(principal);
        RunIngestRequest effectiveRequest = request == null
                ? new RunIngestRequest(false, false, null)
                : request;
        if (durableLeasesEnabled() && runCoordinator != null) {
            IngestionRunCoordinator.ClaimedIngestionRun claimed = runCoordinator.claimManual(
                    sourceId, effectiveRequest.reason(), workerId);
            return new PreparedRun(
                    claimed.source(), effectiveRequest, claimed.running(), claimed.lease(),
                    List.of(), principal.userId());
        }
        SourceRecord source = sourceRepository.findById(sourceId)
                .filter(SourceRecord::enabled)
                .filter(candidate -> "NONE".equals(candidate.deletionStatus()))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SOURCE_NOT_FOUND, "Source not found"));
        if (!durableLeasesEnabled()) {
            IngestRunDto running = runRepository.start(source.id(), effectiveRequest.reason());
            return new PreparedRun(
                    source, effectiveRequest, running, null, List.of(), principal.userId());
        }
        IngestRunLease lease = runRepository.startLease(
                source.id(), effectiveRequest.reason(), "MANUAL", workerId);
        IngestRunDto running = runRepository.findById(lease.runId()).orElseThrow(() ->
                new BusinessException(ErrorCode.INGEST_FAILED, "Ingestion run could not be loaded"));
        return new PreparedRun(
                source, effectiveRequest, running, lease, List.of(), principal.userId());
    }

    private PreparedRun prepareAuditedRun(
            AuthPrincipal principal,
            Long sourceId,
            RunIngestRequest request
    ) {
        requireAdmin(principal);
        requireWritesAllowed();
        RunIngestRequest effectiveRequest = request == null
                ? new RunIngestRequest(false, false, null)
                : request;
        AdminAuditEvent event = AdminAuditEvent.requested(
                principal.userId(), "INGESTION_REQUESTED", "SOURCE", positiveId(sourceId),
                Map.of(
                        "force", effectiveRequest.force(),
                        "rebuildVectors", effectiveRequest.rebuildVectors()));
        return audited(event, () -> prepareRun(principal, sourceId, effectiveRequest));
    }

    private IngestRunDto executeRun(PreparedRun prepared) {
        SourceRecord source = prepared.source();
        RunIngestRequest effectiveRequest = prepared.request();
        IngestRunDto running = prepared.running();
        requireLease(prepared);
        List<CrawledDocument> documents;
        try {
            documents = crawler.crawl(
                    source, CrawlContext.run(running.id()), effectiveRequest.force());
        } catch (CrawlDeferredException exception) {
            return deferRun(prepared, exception.retryAt());
        } catch (RuntimeException exception) {
            logFailure(running.id(), "crawl", exception);
            return finishFailedCrawl(prepared, "Source crawl failed");
        }
        requireLease(prepared);
        if (documents == null || documents.isEmpty()) {
            if (!prepared.retryItems().isEmpty()) {
                return finishMissingRetryItems(prepared, "Source crawl returned no documents");
            }
            return finishFailedCrawl(prepared, "Source crawl returned no documents");
        }

        List<CrawledDocument> retryDocuments = documents;
        if (!prepared.retryItems().isEmpty()) {
            Set<String> retryKeys = prepared.retryItems().stream()
                    .map(IngestRunItemDto::itemKey)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            retryDocuments = documents.stream()
                    .filter(document -> retryKeys.contains(document.sourceUrl()))
                    .toList();
        }
        if (retryDocuments.isEmpty()) {
            return finishMissingRetryItems(prepared, "Retry did not rediscover failed documents");
        }

        Counters counters = new Counters();
        Set<String> foundRetryItems = new HashSet<>();
        for (CrawledDocument document : retryDocuments) {
            foundRetryItems.add(document.sourceUrl());
            try {
                processDocument(prepared, source, document, effectiveRequest, counters);
            } catch (LeaseLostException exception) {
                throw exception;
            } catch (StageFailure exception) {
                counters.recordFailure(exception.stage());
                logFailure(running.id(), exception.stage().label, exception.getCause());
            } catch (RuntimeException exception) {
                counters.recordFailure(FailureStage.UNKNOWN);
                logFailure(running.id(), FailureStage.UNKNOWN.label, exception);
            } finally {
                requireLease(prepared);
            }
        }
        for (IngestRunItemDto retryItem : prepared.retryItems()) {
            if (!foundRetryItems.contains(retryItem.itemKey())) {
                checkpointFailure(prepared, retryItem, FailureStage.CRAWL);
                counters.recordFailure(FailureStage.CRAWL);
            }
        }
        IngestRunStatus status = terminalStatus(counters);
        String errorDetail = counters.errorDetail();
        return finishRun(prepared, status, counters.added, counters.updated,
                counters.skipped, counters.failed, null, errorDetail);
    }

    private void executeInBackground(PreparedRun prepared) {
        try {
            executeRun(prepared);
        } catch (LeaseLostException exception) {
            logFailure(prepared.running().id(), "lease", exception);
        } catch (RuntimeException exception) {
            logFailure(prepared.running().id(), "background", exception);
            finishQuietly(prepared, "Background ingestion failed [background=1]");
        } finally {
            unregisterLease(prepared);
        }
    }

    /**
     * 按数据源和状态分页查询摄取记录。
     *
     * @param principal 当前登录管理员
     * @param sourceId  可选数据源过滤条件
     * @param status    可选状态过滤条件
     * @param page      从 1 开始的页码
     * @param size      每页记录数
     * @return 摄取记录分页结果
     */
    public PageResult<IngestRunDto> listRuns(
            AuthPrincipal principal,
            Long sourceId,
            IngestRunStatus status,
            int page,
            int size
    ) {
        requireAdmin(principal);
        return runRepository.find(sourceId, status, page, size);
    }

/** 管理员持有数据源变更锁期间使用的共享生命周期保护器。 */
    public void requireNoActiveRun(Long sourceId) {
        if (runRepository.hasActiveForSource(sourceId)) {
            throw new BusinessException(
                    ErrorCode.INGEST_ALREADY_RUNNING, "Ingestion is already running for this source");
        }
    }

    private void requireWritesAllowed() {
        if (maintenanceGuard != null) {
            maintenanceGuard.requireWritesAllowed();
        }
    }

/** 为数据源清单提供最新持久化运行状态，不暴露租约细节。 */
    public java.util.Optional<IngestRunStatus> findLatestStatus(Long sourceId) {
        return runRepository.findLatestStatus(sourceId);
    }

/** 返回包含条目级检查点的一次持久化运行。 */
    public IngestRunDetailDto getRun(AuthPrincipal principal, Long runId) {
        requireAdmin(principal);
        return runRepository.findDetailById(runId).orElseThrow(() -> new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND, "Ingestion run not found"));
    }

/** 使用新的记录和令牌显式重试失败或部分完成的运行。 */
    public IngestRunDto retryFailedRun(AuthPrincipal principal, Long runId) {
        requireAdmin(principal);
        requireWritesAllowed();
        if (!durableLeasesEnabled()) {
            throw new BusinessException(
                    ErrorCode.UNSUPPORTED_OPERATION, "Durable ingestion retries are unavailable");
        }
        PreparedRun prepared = audited(
                AdminAuditEvent.requested(
                        principal.userId(), "INGESTION_RETRY_REQUESTED", "INGEST_RUN",
                        positiveId(runId), Map.of("retry", true)),
                () -> prepareRetry(runId, principal.userId()));
        registerLease(prepared);
        try {
            executor.execute(() -> executeInBackground(prepared));
        } catch (RuntimeException exception) {
            finishAfterSubmissionFailure(prepared, exception);
            unregisterLease(prepared);
            throw new BusinessException(
                    ErrorCode.INGEST_FAILED, "Ingestion retry could not be scheduled", exception);
        }
        return prepared.running();
    }

    private PreparedRun prepareRetry(Long runId, Long actorId) {
        SourceRecord source;
        IngestRunLease retry;
        IngestRunDto running;
        if (runCoordinator != null) {
            IngestionRunCoordinator.ClaimedIngestionRun claimed = runCoordinator.claimRetry(
                    runId, workerId);
            source = claimed.source();
            retry = claimed.lease();
            running = claimed.running();
        } else {
            IngestRunDto failed = runRepository.findById(runId).orElseThrow(() -> new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND, "Ingestion run not found"));
            source = sourceRepository.findById(failed.sourceId())
                    .filter(SourceRecord::enabled)
                    .filter(candidate -> "NONE".equals(candidate.deletionStatus()))
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.SOURCE_NOT_FOUND, "Source not found"));
            retry = runRepository.retryFailed(runId, workerId);
            running = runRepository.findById(retry.runId()).orElseThrow(() ->
                    new BusinessException(ErrorCode.INGEST_FAILED, "Ingestion retry could not be loaded"));
        }
        PreparedRun prepared = new PreparedRun(
                source, new RunIngestRequest(false, false, "manual retry"), running, retry,
                itemRepository.findByRunId(retry.runId()), actorId);
        return prepared;
    }

    private void processDocument(
            PreparedRun prepared,
            SourceRecord source,
            CrawledDocument crawled,
            RunIngestRequest request,
            Counters counters
    ) {
        IngestRunItemDto item = registerItem(prepared, crawled.sourceUrl());
        if (item != null && item.status() == IngestRunItemStatus.SUCCEEDED) {
            counters.skipped++;
            return;
        }
        try {
            processRegisteredDocument(prepared, source, crawled, request, counters, item);
        } catch (LeaseLostException exception) {
            throw exception;
        } catch (StageFailure exception) {
            checkpointFailure(prepared, item, exception.stage());
            throw exception;
        } catch (RuntimeException exception) {
            checkpointFailure(prepared, item, FailureStage.UNKNOWN);
            throw exception;
        }
    }

    private void processRegisteredDocument(
            PreparedRun prepared,
            SourceRecord source,
            CrawledDocument crawled,
            RunIngestRequest request,
            Counters counters,
            IngestRunItemDto item
    ) {
        if (crawled.notModified()) {
            refreshNotModified(prepared, source, crawled, counters, item);
            return;
        }
        try {
            DocumentIngestionPipeline.PipelineResult result = pipeline.ingest(
                    new DocumentIngestionPipeline.PipelineRequest(
                            source, crawled.rawBytes(), crawled.filename(), null,
                            crawled.sourceUrl(), crawled.lastModified(),
                            DocumentReferenceType.EXTERNAL, null,
                            null, null, null, null,
                            request.force(), request.rebuildVectors(),
                            crawled.etag(), crawled.lastModified(),
                            crawled.conditionalMetadata()
                                    ? OffsetDateTime.now(ZoneOffset.UTC) : null),
                    executionContext(prepared), item == null ? null : item.id());
            if (result.status() == DocumentWriteStatus.UNCHANGED) {
                counters.skipped++;
                return;
            }
            if (result.status() == DocumentWriteStatus.ADDED) {
                counters.added++;
            } else if (result.status() == DocumentWriteStatus.UPDATED) {
                counters.updated++;
            } else {
                throw new IllegalStateException("Unsupported document write status");
            }
        } catch (IngestRunLeaseLostException exception) {
            throw new LeaseLostException();
        } catch (DocumentIngestionPipeline.PipelineException exception) {
            throw new StageFailure(toFailureStage(exception.stage()), exception.getCause());
        } catch (RuntimeException exception) {
            throw exception;
        }
    }

    private void refreshNotModified(
            PreparedRun prepared,
            SourceRecord source,
            CrawledDocument crawled,
            Counters counters,
            IngestRunItemDto item
    ) {
        DocumentRecord existing = documentRepository.findBySourceIdAndSourceUrl(
                        source.id(), crawled.sourceUrl())
                .orElseThrow(() -> new StageFailure(
                        FailureStage.LOOKUP,
                        new IllegalStateException("Conditional response has no stored document")));
        OffsetDateTime checkedAt = OffsetDateTime.now(ZoneOffset.UTC);
        DocumentRecord refreshed = new DocumentRecord(
                existing.id(), existing.sourceId(), existing.title(), existing.sourceUrl(),
                existing.sourceOrg(), existing.publishedAt(), existing.contentHash(),
                existing.fileObjectKey(), existing.ingestedAt(), existing.referenceType(),
                existing.externalSourceUrl(), existing.internalLocator(), existing.originalFilename(),
                existing.normalizedFilename(), existing.documentStatus(), crawled.etag(),
                crawled.lastModified(), checkedAt, checkedAt,
                existing.degree(), existing.college(), existing.campus(), existing.gradeRange());
        try {
            if (durableLeasesEnabled()) {
                leaseGuard.inTransaction(prepared.lease(), operations -> {
                    DocumentRecord stored = documentRepository.write(refreshed, true).document();
                    operations.checkpoint(item.id(), IngestRunItemStatus.SKIPPED, stored.id(), Map.of(
                            "documentId", stored.id(), "writeStatus", "NOT_MODIFIED"));
                    return null;
                });
            } else {
                documentRepository.write(refreshed, true);
            }
            counters.skipped++;
        } catch (IngestRunLeaseLostException exception) {
            throw new LeaseLostException();
        } catch (RuntimeException exception) {
            throw new StageFailure(FailureStage.INDEX, exception);
        }
    }

    private IngestRunExecutionContext executionContext(PreparedRun prepared) {
        return new IngestRunExecutionContext() {
            @Override
            public IngestRunLease lease() {
                return prepared.lease();
            }

            @Override
            public <T> T inLeaseTransaction(
                    java.util.function.Function<LeaseGuardedIngestOperations, T> work
            ) {
                if (durableLeasesEnabled()) {
                    return leaseGuard.inTransaction(prepared.lease(), work);
                }
                return work.apply((itemId, status, documentId, checkpoint) -> {
// 旧调用方没有可持久化的条目检查点。
                });
            }

            @Override
            public void heartbeat() {
                if (durableLeasesEnabled()) {
                    leaseGuard.assertActive(prepared.lease());
                } else if (!runRepository.heartbeat(prepared.running().id())) {
                    throw new IngestRunLeaseLostException();
                }
            }
        };
    }

    private FailureStage toFailureStage(DocumentIngestionPipeline.FailureStage stage) {
        return switch (stage) {
            case LOOKUP -> FailureStage.LOOKUP;
            case PARSE -> FailureStage.PARSE;
            case EMBEDDING -> FailureStage.EMBEDDING;
            case STORAGE -> FailureStage.STORAGE;
            case INDEX -> FailureStage.INDEX;
        };
    }

    private IngestRunItemDto registerItem(PreparedRun prepared, String itemKey) {
        if (!durableLeasesEnabled()) {
            return null;
        }
        try {
            return itemRepository.upsertItem(prepared.lease(), itemKey, "URL")
                    .orElseThrow(IngestRunLeaseLostException::new);
        } catch (IngestRunLeaseLostException exception) {
            throw new LeaseLostException();
        }
    }

    private void checkpoint(
            PreparedRun prepared,
            IngestRunItemDto item,
            IngestRunItemStatus status,
            Long documentId,
            Map<String, Object> checkpoint
    ) {
        if (!durableLeasesEnabled()) {
            return;
        }
        try {
            leaseGuard.inTransaction(prepared.lease(), operations -> {
                operations.checkpoint(item.id(), status, documentId, checkpoint);
                return null;
            });
        } catch (IngestRunLeaseLostException exception) {
            throw new LeaseLostException();
        }
    }

    private void checkpointFailure(
            PreparedRun prepared,
            IngestRunItemDto item,
            FailureStage stage
    ) {
        if (item != null) {
            checkpoint(prepared, item, IngestRunItemStatus.FAILED, null, Map.of(
                    "stage", stage.label));
        }
    }

    private String storageNamespace(PreparedRun prepared, IngestRunItemDto item) {
        if (!durableLeasesEnabled()) {
            return DOCUMENT_NAMESPACE;
        }
        IngestRunLease lease = prepared.lease();
        return DOCUMENT_NAMESPACE + "-run-" + lease.runId()
                + "-item-" + item.id()
                + "-attempt-" + lease.attempt()
                + "-lease-" + lease.leaseToken();
    }

    private List<PreparedEmbeddingChunk> prepareChunks(
            SourceRecord source,
            CrawledDocument crawled,
            ParsedDocument parsed,
            List<TextChunk> chunks,
            List<List<Double>> vectors
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>(parsed.metadata());
        metadata.putIfAbsent("mediaType", parsed.mediaType());
        String degree = audienceValue(parsed.metadata(), "degree");
        String college = audienceValue(parsed.metadata(), "college");
        String campus = audienceValue(parsed.metadata(), "campus");
        String gradeRange = audienceValue(
                parsed.metadata(), "gradeRange", "grade_range");

        List<PreparedEmbeddingChunk> prepared = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            TextChunk chunk = chunks.get(index);
            prepared.add(new PreparedEmbeddingChunk(
                    chunk.index(),
                    chunk.text(),
                    vectors.get(index),
                    OFFICIAL,
                    crawled.sourceUrl(),
                    parsed.title(),
                    source.name(),
                    crawled.lastModified(),
                    degree,
                    college,
                    campus,
                    gradeRange,
                    metadata));
        }
        return List.copyOf(prepared);
    }

    private String audienceValue(Map<String, String> metadata, String... names) {
        for (String name : names) {
            String value = metadata.get(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return GENERAL;
    }

    private void deleteReplacedObject(
            Optional<DocumentRecord> existing,
            String retainedObjectKey,
            Long runId
    ) {
        existing.map(DocumentRecord::fileObjectKey)
                .filter(key -> key != null && !key.isBlank())
                .filter(key -> !key.equals(retainedObjectKey))
                .ifPresent(key -> deleteWithRetry(key, runId, "cleanup-old-object"));
    }

    private void deleteWithRetry(String objectKey, Long runId, String stage) {
        for (int attempt = 1; attempt <= MAX_CLEANUP_ATTEMPTS; attempt++) {
            try {
                objectStorage.delete(objectKey);
                return;
            } catch (RuntimeException exception) {
                if (attempt == MAX_CLEANUP_ATTEMPTS) {
                    LOGGER.error(
                            "Ingestion cleanup failed: runId={} stage={} causeType={}",
                            runId,
                            stage,
                            exception.getClass().getName());
                }
            }
        }
    }

    private IngestRunDto deferRun(PreparedRun prepared, OffsetDateTime retryAt) {
        if (!durableLeasesEnabled()) {
            return runRepository.defer(prepared.running().id(), retryAt);
        }
        if (!runRepository.defer(prepared.lease(), retryAt)) {
            throw new LeaseLostException();
        }
        return runRepository.findById(prepared.lease().runId()).orElseThrow(() ->
                new BusinessException(ErrorCode.INGEST_FAILED, "Deferred run could not be loaded"));
    }

    private IngestRunDto finishFailedCrawl(PreparedRun prepared, String errorDetail) {
        requireLease(prepared);
        return finishRun(prepared, IngestRunStatus.FAILED, 0, 0, 0, 1,
                "CRAWL_FAILED", errorDetail);
    }

    private IngestRunDto finishMissingRetryItems(PreparedRun prepared, String errorDetail) {
        Counters counters = new Counters();
        for (IngestRunItemDto retryItem : prepared.retryItems()) {
            checkpointFailure(prepared, retryItem, FailureStage.CRAWL);
            counters.recordFailure(FailureStage.CRAWL);
        }
        return finishRun(prepared, IngestRunStatus.FAILED, 0, 0, 0, counters.failed,
                "RETRY_ITEMS_MISSING", errorDetail);
    }

    private void finishAfterSubmissionFailure(PreparedRun prepared, RuntimeException cause) {
        try {
            finishRun(prepared, IngestRunStatus.FAILED, 0, 0, 0, 1,
                    "SUBMISSION_REJECTED", "Background submission failed");
        } catch (RuntimeException finishException) {
            logFailure(prepared.running().id(), "finish-after-rejection", finishException);
        }
        logFailure(prepared.running().id(), "submit", cause);
    }

    private void finishQuietly(PreparedRun prepared, String errorDetail) {
        try {
            finishRun(prepared, IngestRunStatus.FAILED, 0, 0, 0, 1,
                    "BACKGROUND_FAILED", errorDetail);
        } catch (RuntimeException exception) {
            logFailure(prepared.running().id(), "finish", exception);
        }
    }

    private IngestRunDto finishRun(
            PreparedRun prepared,
            IngestRunStatus status,
            long added,
            long updated,
            long skipped,
            long failed,
            String errorCode,
            String errorDetail
    ) {
        AdminAuditEvent terminal = ingestionTerminalEvent(
                prepared, status, added, updated, skipped, failed, errorCode);
        if (!durableLeasesEnabled()) {
            Supplier<IngestRunDto> finish = () -> runRepository.finish(
                    prepared.running().id(), status, added, updated, skipped, failed, errorDetail);
            return auditService == null
                    ? finish.get()
                    : auditService.auditedOutcome(terminal, finish, ignored -> true);
        }
        Supplier<Boolean> finish = () -> runRepository.finish(
                prepared.lease(), status, added, updated, skipped, failed,
                errorCode, errorDetail);
        boolean changed = auditService == null
                ? finish.get()
                : auditService.auditedOutcome(terminal, finish, Boolean.TRUE::equals);
        if (!changed) {
            throw new LeaseLostException();
        }
        return runRepository.findById(prepared.lease().runId()).orElseThrow(() ->
                new BusinessException(ErrorCode.INGEST_FAILED, "Ingestion run could not be loaded"));
    }

    private AdminAuditEvent ingestionTerminalEvent(
            PreparedRun prepared,
            IngestRunStatus status,
            long added,
            long updated,
            long skipped,
            long failed,
            String errorCode
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", status.name());
        summary.put("added", added);
        summary.put("updated", updated);
        summary.put("skipped", skipped);
        summary.put("failed", failed);
        if (errorCode != null && !errorCode.isBlank()) {
            summary.put("runErrorCode", errorCode);
        }
        AdminAuditEvent event = AdminAuditEvent.requested(
                prepared.actorId(), "INGESTION_FINISHED", "INGEST_RUN",
                prepared.running().id(), summary);
        return status == IngestRunStatus.SUCCESS
                ? event.succeeded()
                : event.failed(ErrorCode.INGEST_FAILED);
    }

    private void requireLease(PreparedRun prepared) {
        if (!durableLeasesEnabled()) {
            if (!runRepository.heartbeat(prepared.running().id())) {
                throw new LeaseLostException();
            }
            return;
        }
        try {
            leaseGuard.assertActive(prepared.lease());
        } catch (IngestRunLeaseLostException exception) {
            throw new LeaseLostException();
        }
    }

    private boolean durableLeasesEnabled() {
        return itemRepository != null && leaseGuard != null && leaseHeartbeatScheduler != null;
    }

    private void registerLease(PreparedRun prepared) {
        if (durableLeasesEnabled() && prepared.lease() != null) {
            leaseHeartbeatScheduler.register(prepared.lease());
        }
    }

    private void unregisterLease(PreparedRun prepared) {
        if (durableLeasesEnabled() && prepared.lease() != null) {
            leaseHeartbeatScheduler.unregister(prepared.lease());
        }
    }

    private <T> T audited(AdminAuditEvent event, Supplier<T> operation) {
        return auditService == null ? operation.get() : auditService.audited(event, operation);
    }

    private Long positiveId(Long value) {
        return value != null && value > 0 ? value : null;
    }

    private void logFailure(Long runId, String stage, Throwable cause) {
        String causeType = cause == null ? "unknown" : cause.getClass().getName();
        LOGGER.warn(
                "Ingestion operation failed: runId={} stage={} causeType={}",
                runId,
                stage,
                causeType);
    }

    private IngestRunStatus terminalStatus(Counters counters) {
        if (counters.failed == 0) {
            return IngestRunStatus.SUCCESS;
        }
        if (counters.added + counters.updated + counters.skipped > 0) {
            return IngestRunStatus.PARTIAL_SUCCESS;
        }
        return IngestRunStatus.FAILED;
    }

    private String safeFilename(String filename) {
        String safe = filename == null ? "document" : filename.strip()
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (safe.isBlank() || safe.equals(".") || safe.equals("..")) {
            safe = "document";
        }
        return truncateUtf8(safe, MAX_FILENAME_UTF8_BYTES);
    }

    private String truncateUtf8(String value, int maxBytes) {
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + characterBytes > maxBytes) {
                break;
            }
            result.appendCodePoint(codePoint);
            bytes += characterBytes;
            offset += Character.charCount(codePoint);
        }
        return result.isEmpty() ? "document" : result.toString();
    }

    private String sha256(byte[] rawBytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(rawBytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void requireAdmin(AuthPrincipal principal) {
        if (principal == null || !UserRole.ADMIN.name().equals(principal.role())) {
            throw new BusinessException(ErrorCode.ADMIN_REQUIRED, "Administrator role required");
        }
    }

    private static final class Counters {
        private long added;
        private long updated;
        private long skipped;
        private long failed;
        private final EnumMap<FailureStage, Long> failures = new EnumMap<>(FailureStage.class);

        private void recordFailure(FailureStage stage) {
            failed++;
            failures.merge(stage, 1L, Long::sum);
        }

        private String errorDetail() {
            if (failed == 0) {
                return null;
            }
            StringJoiner stages = new StringJoiner(", ", "[", "]");
            for (FailureStage stage : FailureStage.values()) {
                long count = failures.getOrDefault(stage, 0L);
                if (count > 0) {
                    stages.add(stage.label + "=" + count);
                }
            }
            return failed + " document(s) failed " + stages;
        }
    }

    private enum FailureStage {
        CRAWL("crawl"),
        PARSE("parse"),
        EMBEDDING("embedding"),
        STORAGE("storage"),
        INDEX("index"),
        LOOKUP("lookup"),
        UNKNOWN("unknown");

        private final String label;

        FailureStage(String label) {
            this.label = label;
        }
    }

    private static final class LeaseLostException extends BusinessException {

        private LeaseLostException() {
            super(ErrorCode.INGEST_FAILED, "Ingestion lease is no longer active");
        }
    }

    private static final class StageFailure extends RuntimeException {

        private final FailureStage stage;

        private StageFailure(FailureStage stage, Throwable cause) {
            super(cause);
            this.stage = stage;
        }

        private FailureStage stage() {
            return stage;
        }
    }

    private record PreparedRun(
            SourceRecord source,
            RunIngestRequest request,
            IngestRunDto running,
            IngestRunLease lease,
            List<IngestRunItemDto> retryItems,
            Long actorId
    ) {
    }
}
