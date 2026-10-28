package xyz.v185v.collegeassistant.admin.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import xyz.v185v.collegeassistant.admin.audit.AdminAuditEvent;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditService;
import xyz.v185v.collegeassistant.admin.model.EmbeddingMaintenanceGuard;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.document.DocumentReferenceType;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.DocumentStatus;
import xyz.v185v.collegeassistant.document.DocumentWriteStatus;
import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.ingestion.application.DocumentIngestionPipeline;
import xyz.v185v.collegeassistant.ingestion.IngestRunDetailDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunExecutionContext;
import xyz.v185v.collegeassistant.ingestion.IngestRunItemDto;
import xyz.v185v.collegeassistant.ingestion.IngestRunItemStatus;
import xyz.v185v.collegeassistant.ingestion.IngestRunLease;
import xyz.v185v.collegeassistant.ingestion.IngestRunLeaseGuard;
import xyz.v185v.collegeassistant.ingestion.IngestRunLeaseHeartbeatScheduler;
import xyz.v185v.collegeassistant.ingestion.IngestRunLeaseLostException;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;
import xyz.v185v.collegeassistant.ingestion.IngestRunStatus;
import xyz.v185v.collegeassistant.ingestion.application.IngestionRunCoordinator;
import xyz.v185v.collegeassistant.ingestion.LeaseGuardedIngestOperations;

/** 从私有暂存到租约保护发布的持久化本地文件导入流程。 */
@Service
public class ImportService {

    public static final int MAX_FILES = 20;
    public static final int MAX_FILE_BYTES = 20 * 1024 * 1024;
    public static final long MAX_BATCH_BYTES = 100L * 1024 * 1024;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx",
            "html", "htm", "txt", "md");

    private final IngestionRunCoordinator coordinator;
    private final IngestRunRepository runRepository;
    private final IngestRunLeaseGuard leaseGuard;
    private final IngestRunLeaseHeartbeatScheduler heartbeatScheduler;
    private final ImportStagingStore stagingStore;
    private final DocumentIngestionPipeline pipeline;
    private final DocumentRepository documentRepository;
    private final Executor executor;
    private final AdminAuditService auditService;
    private EmbeddingMaintenanceGuard maintenanceGuard;
    private final Tika tika = new Tika();
    private final String workerId = "import-" + UUID.randomUUID();

    @Autowired
    public ImportService(
            IngestionRunCoordinator coordinator,
            IngestRunRepository runRepository,
            IngestRunLeaseGuard leaseGuard,
            IngestRunLeaseHeartbeatScheduler heartbeatScheduler,
            ImportStagingStore stagingStore,
            DocumentIngestionPipeline pipeline,
            DocumentRepository documentRepository,
            @Qualifier("ingestionTaskExecutor") Executor executor,
            AdminAuditService auditService
    ) {
        this.coordinator = coordinator;
        this.runRepository = runRepository;
        this.leaseGuard = leaseGuard;
        this.heartbeatScheduler = heartbeatScheduler;
        this.stagingStore = stagingStore;
        this.pipeline = pipeline;
        this.documentRepository = documentRepository;
        this.executor = executor;
        this.auditService = auditService;
    }

    @Autowired
    public void setEmbeddingMaintenanceGuard(EmbeddingMaintenanceGuard maintenanceGuard) {
        this.maintenanceGuard = maintenanceGuard;
    }

/** 为针对性导入测试保留的兼容构造函数。 */
    public ImportService(
            IngestionRunCoordinator coordinator,
            IngestRunRepository runRepository,
            IngestRunLeaseGuard leaseGuard,
            IngestRunLeaseHeartbeatScheduler heartbeatScheduler,
            ImportStagingStore stagingStore,
            DocumentIngestionPipeline pipeline,
            DocumentRepository documentRepository,
            Executor executor
    ) {
        this(
                coordinator, runRepository, leaseGuard, heartbeatScheduler,
                stagingStore, pipeline, documentRepository, executor, null);
    }

    public IngestRunDetailDto start(
            AuthPrincipal actor,
            ImportMetadata metadata,
            List<MultipartFile> files
    ) {
        requireAdmin(actor);
        requireWritesAllowed();
        ValidatedImport validated = validateStructure(metadata, files);
        IngestionRunCoordinator.ClaimedImportRun claimed = audited(
                AdminAuditEvent.requested(
                        actor.userId(), "IMPORT_REQUESTED", "SOURCE", metadata.sourceId(),
                        Map.of("fileCount", validated.files().size())),
                () -> coordinator.claimImport(
                        metadata.sourceId(), "local upload", validated.clientIds(), workerId));
        heartbeatScheduler.register(claimed.lease());
        List<StagedImportObject> staged = new ArrayList<>();
        List<ReadyItem> readyItems = new ArrayList<>();
        Map<String, String> batchFilenameHashes = new LinkedHashMap<>();
        long acceptedBytes = 0;
        try {
            for (int index = 0; index < validated.files().size(); index++) {
                ValidatedFile file = validated.files().get(index);
                IngestRunItemDto item = claimed.items().get(index);
                byte[] bytes = readAcceptedBytes(file.file());
                acceptedBytes = accumulateBatchBytes(acceptedBytes, bytes.length);
                validateDetectedType(bytes, file.filename());
                String contentHash = sha256(bytes);
                preflightBatchIdentity(
                        batchFilenameHashes, normalizedFilename(file.filename()),
                        contentHash, file.metadata().replaceDocumentId());
                preflightIdentity(
                        claimed.source(), file.metadata(), file.filename(), contentHash);
                StagedImportObject object = stagingStore.save(
                        claimed.lease().runId(), item.id(), new ByteArrayInputStream(bytes));
                staged.add(object);
                Map<String, Object> checkpoint = checkpointStaged(
                        claimed.lease(), item.id(), file, object);
                readyItems.add(new ReadyItem(item, checkpoint));
            }
            submit(claimed, actor.userId(), readyItems);
            return detail(claimed.running().id());
        } catch (RuntimeException exception) {
            staged.forEach(object -> deleteStagedQuietly(object.runId(), object.itemId()));
            failAcceptedRun(
                    claimed, actor.userId(),
                    "IMPORT_REJECTED", "Import request could not be accepted");
            throw exception;
        }
    }

    public PageResult<IngestRunDto> list(AuthPrincipal actor, int page, int size) {
        requireAdmin(actor);
        return runRepository.findImports(page, size);
    }

    public IngestRunDetailDto get(AuthPrincipal actor, Long runId) {
        requireAdmin(actor);
        return detail(runId);
    }

    public IngestRunDetailDto retry(AuthPrincipal actor, Long runId) {
        requireAdmin(actor);
        IngestionRunCoordinator.ClaimedImportRun claimed = audited(
                AdminAuditEvent.requested(
                        actor.userId(), "IMPORT_RETRY_REQUESTED", "INGEST_RUN", runId,
                        Map.of("retry", true)),
                () -> coordinator.claimImportRetry(runId, workerId));
        heartbeatScheduler.register(claimed.lease());
        try {
            submit(claimed, actor.userId());
            return detail(claimed.running().id());
        } catch (RuntimeException exception) {
            failAcceptedRun(
                    claimed, actor.userId(),
                    "IMPORT_RETRY_REJECTED", "Import retry could not be accepted");
            throw exception;
        }
    }

    private void submit(IngestionRunCoordinator.ClaimedImportRun claimed, Long actorId) {
        submit(claimed, actorId, claimed.items().stream()
                .map(item -> new ReadyItem(item, item.checkpoint()))
                .toList());
    }

    private void submit(
            IngestionRunCoordinator.ClaimedImportRun claimed,
            Long actorId,
            List<ReadyItem> readyItems
    ) {
        try {
            executor.execute(() -> execute(claimed, actorId, readyItems));
        } catch (RejectedExecutionException exception) {
            throw new BusinessException(
                    ErrorCode.INGEST_FAILED, "Import could not be scheduled", exception);
        }
    }

    private void execute(
            IngestionRunCoordinator.ClaimedImportRun claimed,
            Long actorId,
            List<ReadyItem> readyItems
    ) {
        Counters counters = new Counters();
        IngestRunExecutionContext context = context(claimed.lease());
        try {
            for (ReadyItem readyItem : readyItems) {
                IngestRunItemDto item = readyItem.item();
                try {
                    processItem(claimed.source(), claimed.lease(), context,
                            item, readyItem.checkpoint(), counters);
                } catch (IngestRunLeaseLostException exception) {
                    return;
                } catch (DocumentIngestionPipeline.PipelineException exception) {
                    checkpointFailure(claimed.lease(), item.id(),
                            readyItem.checkpoint(), exception.stage().label());
                    counters.failure(exception.stage().label());
                } catch (RuntimeException exception) {
                    checkpointFailure(claimed.lease(), item.id(),
                            readyItem.checkpoint(), "unknown");
                    counters.failure("unknown");
                }
            }
            IngestRunStatus status = counters.status();
            finishRun(
                    actorId,
                    claimed.lease(), status, counters.added, counters.updated,
                    counters.skipped, counters.failed,
                    counters.failed == 0 ? null : "IMPORT_FAILED", counters.errorDetail());
        } finally {
            heartbeatScheduler.unregister(claimed.lease());
        }
    }

    private void processItem(
            SourceRecord source,
            IngestRunLease lease,
            IngestRunExecutionContext context,
            IngestRunItemDto item,
            Map<String, Object> checkpoint,
            Counters counters
    ) {
        long stagingRunId = longCheckpoint(checkpoint, "stagingRunId", lease.runId());
        long stagingItemId = longCheckpoint(checkpoint, "stagingItemId", item.id());
        String filename = stringCheckpoint(checkpoint, "originalFilename");
        if (filename == null || filename.isBlank()) {
            throw new BusinessException(
                    ErrorCode.INGEST_FAILED, "Import staging metadata is unavailable");
        }
        byte[] bytes;
        try (InputStream input = stagingStore.open(stagingRunId, stagingItemId)) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(
                    ErrorCode.INGEST_FAILED, "Import staging object is unavailable", exception);
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new BusinessException(
                    ErrorCode.INGEST_FAILED, "Import staging object exceeds the file limit");
        }
        DocumentIngestionPipeline.PipelineResult result = pipeline.ingest(
                new DocumentIngestionPipeline.PipelineRequest(
                        source, bytes, filename, stringCheckpoint(checkpoint, "displayTitle"),
                        stringCheckpoint(checkpoint, "externalSourceUrl"), OffsetDateTime.now(),
                        DocumentReferenceType.INTERNAL,
                        nullableLongCheckpoint(checkpoint, "replaceDocumentId"),
                        stringCheckpoint(checkpoint, "degree"),
                        stringCheckpoint(checkpoint, "college"),
                        stringCheckpoint(checkpoint, "campus"),
                        stringCheckpoint(checkpoint, "gradeRange"), false, false),
                context, item.id());
        if (result.status() == DocumentWriteStatus.UNCHANGED) {
            counters.skipped++;
        } else if (result.status() == DocumentWriteStatus.ADDED) {
            counters.added++;
        } else if (result.status() == DocumentWriteStatus.UPDATED) {
            counters.updated++;
        }
        deleteStagedQuietly(stagingRunId, stagingItemId);
    }

    private IngestRunExecutionContext context(IngestRunLease lease) {
        return new IngestRunExecutionContext() {
            @Override
            public IngestRunLease lease() {
                return lease;
            }

            @Override
            public <T> T inLeaseTransaction(
                    java.util.function.Function<LeaseGuardedIngestOperations, T> work
            ) {
                return leaseGuard.inTransaction(lease, work);
            }

            @Override
            public void heartbeat() {
                leaseGuard.assertActive(lease);
            }
        };
    }

    private Map<String, Object> checkpointStaged(
            IngestRunLease lease,
            Long itemId,
            ValidatedFile file,
            StagedImportObject staged
    ) {
        Map<String, Object> checkpoint = stagingCheckpoint(file, staged);
        leaseGuard.inTransaction(lease, operations -> {
            operations.checkpoint(
                    itemId, IngestRunItemStatus.QUEUED, null,
                    checkpoint);
            return null;
        });
        return checkpoint;
    }

    private Map<String, Object> stagingCheckpoint(
            ValidatedFile file,
            StagedImportObject staged
    ) {
        ImportFileMetadata metadata = file.metadata();
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("clientId", metadata.clientId());
        checkpoint.put("originalFilename", file.filename());
        checkpoint.put("normalizedFilename", normalizedFilename(file.filename()));
        checkpoint.put("stagingRunId", staged.runId());
        checkpoint.put("stagingItemId", staged.itemId());
        putIfPresent(checkpoint, "displayTitle", metadata.displayTitle());
        putIfPresent(checkpoint, "externalSourceUrl", metadata.externalSourceUrl());
        if (metadata.replaceDocumentId() != null) {
            checkpoint.put("replaceDocumentId", metadata.replaceDocumentId());
        }
        putIfPresent(checkpoint, "degree", metadata.degree());
        putIfPresent(checkpoint, "college", metadata.college());
        putIfPresent(checkpoint, "campus", metadata.campus());
        putIfPresent(checkpoint, "gradeRange", metadata.gradeRange());
        return Map.copyOf(checkpoint);
    }

    private void checkpointFailure(
            IngestRunLease lease,
            Long itemId,
            Map<String, Object> currentCheckpoint,
            String stage
    ) {
        Map<String, Object> failedCheckpoint = new LinkedHashMap<>(
                currentCheckpoint == null ? Map.of() : currentCheckpoint);
        failedCheckpoint.put("stage", stage);
        leaseGuard.inTransaction(lease, operations -> {
            operations.checkpoint(
                    itemId, IngestRunItemStatus.FAILED, null, Map.copyOf(failedCheckpoint));
            return null;
        });
    }

    private boolean finishRun(
            Long actorId,
            IngestRunLease lease,
            IngestRunStatus status,
            long added,
            long updated,
            long skipped,
            long failed,
            String errorCode,
            String errorDetail
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
                actorId, "IMPORT_FINISHED", "INGEST_RUN", lease.runId(), summary);
        if (status != IngestRunStatus.SUCCESS) {
            event = event.failed(ErrorCode.INGEST_FAILED);
        }
        Supplier<Boolean> finish = () -> runRepository.finish(
                lease, status, added, updated, skipped, failed, errorCode, errorDetail);
        return auditService == null
                ? finish.get()
                : auditService.auditedOutcome(event, finish, Boolean.TRUE::equals);
    }

    private void failAcceptedRun(
            IngestionRunCoordinator.ClaimedImportRun claimed,
            Long actorId,
            String errorCode,
            String errorDetail
    ) {
        try {
            for (IngestRunItemDto item : claimed.items()) {
                checkpointFailure(
                        claimed.lease(), item.id(), item.checkpoint(), "validation");
            }
            finishRun(
                    actorId,
                    claimed.lease(), IngestRunStatus.FAILED, 0, 0, 0,
                    claimed.items().size(), errorCode, errorDetail);
        } catch (RuntimeException ignored) {
// 本请求线程不得覆盖已被抢占的租约。
        } finally {
            heartbeatScheduler.unregister(claimed.lease());
        }
    }

    private IngestRunDetailDto detail(Long runId) {
        IngestRunDetailDto detail = runRepository.findDetailById(runId).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Import run not found"));
        if (!"IMPORT".equals(detail.triggerType())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Import run not found");
        }
        return detail;
    }

    private ValidatedImport validateStructure(
            ImportMetadata metadata,
            List<MultipartFile> files
    ) {
        if (metadata == null || metadata.sourceId() == null || metadata.sourceId() <= 0) {
            throw validation("A local upload source is required");
        }
        List<MultipartFile> supplied = files == null ? List.of() : List.copyOf(files);
        if (supplied.isEmpty() || supplied.size() > MAX_FILES
                || metadata.items().size() != supplied.size()) {
            throw validation(
                    "Import metadata and files must have matching counts of at most 20");
        }
        long total = 0;
        Set<String> uniqueClientIds = new HashSet<>();
        List<String> orderedClientIds = new ArrayList<>(supplied.size());
        List<ValidatedFile> validated = new ArrayList<>(supplied.size());
        for (int index = 0; index < supplied.size(); index++) {
            MultipartFile file = supplied.get(index);
            ImportFileMetadata item = metadata.items().get(index);
            if (file == null || item == null) {
                throw validation("Import files and metadata cannot contain null values");
            }
            String filename = validateFilename(file.getOriginalFilename());
            validateMetadata(item);
            if (!uniqueClientIds.add(item.clientId())) {
                throw validation("Import client IDs must be unique");
            }
            orderedClientIds.add(item.clientId());
            long size = file.getSize();
            if (size < 0 || size > MAX_FILE_BYTES) {
                throw validation("Each import file must be at most 20 MiB");
            }
            total += size;
            if (total > MAX_BATCH_BYTES) {
                throw validation("An import batch must be at most 100 MiB");
            }
            validated.add(new ValidatedFile(file, item, filename));
        }
        return new ValidatedImport(
                metadata, List.copyOf(validated), List.copyOf(orderedClientIds));
    }

    private void validateMetadata(ImportFileMetadata metadata) {
        text(metadata.clientId(), "Import client ID", 128);
        optionalText(metadata.displayTitle(), "Import display title", 512);
        optionalText(metadata.degree(), "Import degree", 32);
        optionalText(metadata.college(), "Import college", 64);
        optionalText(metadata.campus(), "Import campus", 64);
        optionalText(metadata.gradeRange(), "Import grade range", 64);
        if (metadata.replaceDocumentId() != null && metadata.replaceDocumentId() <= 0) {
            throw validation("Replacement document ID must be positive");
        }
        if (metadata.externalSourceUrl() != null && !metadata.externalSourceUrl().isBlank()) {
            if (metadata.externalSourceUrl().strip().length() > 4096) {
                throw validation("External source URL is too long");
            }
            try {
                URI uri = URI.create(metadata.externalSourceUrl().strip());
                if (!uri.isAbsolute() || uri.getHost() == null
                        || !("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme()))) {
                    throw validation("External source URL must be HTTP(S)");
                }
            } catch (IllegalArgumentException exception) {
                throw validation("External source URL must be HTTP(S)");
            }
        }
    }

    private String validateFilename(String originalFilename) {
        String filename = originalFilename == null ? "" : originalFilename.strip();
        if (filename.isBlank() || filename.length() > 512 || filename.indexOf('/') >= 0
                || filename.indexOf('\\') >= 0 || filename.indexOf('\0') >= 0) {
            throw validation("Import filename is invalid");
        }
        int separator = filename.lastIndexOf('.');
        String extension = separator < 1
                ? "" : filename.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw validation("Import file type is not supported");
        }
        return filename;
    }

    private byte[] readAcceptedBytes(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw validation("Each import file must be at most 20 MiB");
            }
            return bytes;
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw validation("Import file could not be read");
        }
    }

    static long accumulateBatchBytes(long acceptedBytes, int nextFileBytes) {
        if (acceptedBytes < 0 || nextFileBytes < 0
                || acceptedBytes > MAX_BATCH_BYTES - nextFileBytes) {
            throw validation("An import batch must be at most 100 MiB");
        }
        return acceptedBytes + nextFileBytes;
    }

    private void validateDetectedType(byte[] bytes, String filename) {
        try {
            String detected = tika.detect(
                    new ByteArrayInputStream(bytes), filename).toLowerCase(Locale.ROOT);
            boolean executableMagic = bytes.length >= 2 && bytes[0] == 'M' && bytes[1] == 'Z';
            if (executableMagic || detected.contains("dosexec")
                    || detected.contains("msdownload") || detected.contains("x-executable")) {
                throw validation("Executable content is not allowed for import");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw validation("Import file type could not be detected");
        }
    }

    private void preflightIdentity(
            SourceRecord source,
            ImportFileMetadata metadata,
            String filename,
            String hash
    ) {
        if (documentRepository.findActiveInternalBySourceIdAndContentHash(
                source.id(), hash).isPresent()) {
            return;
        }
        if (metadata.replaceDocumentId() != null) {
            documentRepository.findById(metadata.replaceDocumentId())
                    .filter(document -> document.sourceId().equals(source.id()))
                    .filter(document -> document.referenceType() == DocumentReferenceType.INTERNAL)
                    .filter(document -> document.documentStatus() != DocumentStatus.DELETING
                            && document.documentStatus() != DocumentStatus.DELETE_FAILED)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.RESOURCE_NOT_FOUND, "Replacement document not found"));
            return;
        }
        if (documentRepository.findActiveInternalBySourceIdAndNormalizedFilename(
                source.id(), normalizedFilename(filename)).isPresent()) {
            throw new BusinessException(
                    ErrorCode.DOCUMENT_REPLACEMENT_REQUIRED,
                    "A replacement document must be selected for this filename");
        }
    }

    static void preflightBatchIdentity(
            Map<String, String> filenameHashes,
            String normalizedFilename,
            String contentHash,
            Long replaceDocumentId
    ) {
        String previousHash = filenameHashes.putIfAbsent(normalizedFilename, contentHash);
        if (previousHash != null && !previousHash.equals(contentHash)
                && replaceDocumentId == null) {
            throw new BusinessException(
                    ErrorCode.DOCUMENT_REPLACEMENT_REQUIRED,
                    "A replacement document must be selected for this filename");
        }
    }

    private <T> T audited(AdminAuditEvent event, Supplier<T> operation) {
        return auditService == null ? operation.get() : auditService.audited(event, operation);
    }

    private void requireAdmin(AuthPrincipal actor) {
        if (actor == null || !UserRole.ADMIN.name().equals(actor.role())) {
            throw new BusinessException(ErrorCode.ADMIN_REQUIRED, "Administrator role required");
        }
    }

    private void requireWritesAllowed() {
        if (maintenanceGuard != null) {
            maintenanceGuard.requireWritesAllowed();
        }
    }

    private String text(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.strip().length() > maxLength) {
            throw validation(field + " is invalid");
        }
        return value.strip();
    }

    private void optionalText(String value, String field, int maxLength) {
        if (value != null && !value.isBlank() && value.strip().length() > maxLength) {
            throw validation(field + " is invalid");
        }
    }

    private String normalizedFilename(String filename) {
        return java.text.Normalizer.normalize(filename, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.strip());
        }
    }

    private long longCheckpoint(Map<String, Object> checkpoint, String key, long fallback) {
        Long value = nullableLongCheckpoint(checkpoint, key);
        return value == null ? fallback : value;
    }

    private Long nullableLongCheckpoint(Map<String, Object> checkpoint, String key) {
        Object value = checkpoint.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INGEST_FAILED, "Import checkpoint is invalid");
        }
    }

    private String stringCheckpoint(Map<String, Object> checkpoint, String key) {
        Object value = checkpoint.get(key);
        return value == null ? null : value.toString();
    }

    private void deleteStagedQuietly(long runId, long itemId) {
        try {
            stagingStore.delete(runId, itemId);
        } catch (RuntimeException ignored) {
// 计划清理器会在非关键清理失败后回收文件。
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    private record ValidatedImport(
            ImportMetadata metadata,
            List<ValidatedFile> files,
            List<String> clientIds
    ) {
    }

    private record ValidatedFile(
            MultipartFile file,
            ImportFileMetadata metadata,
            String filename
    ) {
    }

    private record ReadyItem(
            IngestRunItemDto item,
            Map<String, Object> checkpoint
    ) {
        private ReadyItem {
            checkpoint = checkpoint == null ? Map.of() : Map.copyOf(checkpoint);
        }
    }

    private static final class Counters {
        private long added;
        private long updated;
        private long skipped;
        private long failed;
        private final Map<String, Long> failures = new LinkedHashMap<>();

        private void failure(String stage) {
            failed++;
            failures.merge(stage, 1L, Long::sum);
        }

        private IngestRunStatus status() {
            if (failed == 0) {
                return IngestRunStatus.SUCCESS;
            }
            return added + updated + skipped > 0
                    ? IngestRunStatus.PARTIAL_SUCCESS : IngestRunStatus.FAILED;
        }

        private String errorDetail() {
            if (failed == 0) {
                return null;
            }
            StringJoiner summary = new StringJoiner(", ", "[", "]");
            failures.forEach((stage, count) -> summary.add(stage + "=" + count));
            return failed + " import file(s) failed " + summary;
        }
    }
}
