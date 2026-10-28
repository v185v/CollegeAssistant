package xyz.v185v.collegeassistant.admin.system;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import xyz.v185v.collegeassistant.admin.document.ImportStagingStore;
import xyz.v185v.collegeassistant.admin.document.StagedImportObject;
import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobExecutionContext;
import xyz.v185v.collegeassistant.admin.job.AdminJobHandler;
import xyz.v185v.collegeassistant.admin.job.AdminJobItemDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobService;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.admin.system.persistence.SystemDatabaseMapper;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.storage.ObjectStorage;
import xyz.v185v.collegeassistant.storage.StoredObjectMetadata;

/** 只读一致性检测器，唯一的写入是持久化的类型化发现结果。 */
@Service
public class ConsistencyScanService implements AdminJobHandler {

    private static final String DOCUMENT_NAMESPACE = "documents";
    private static final Duration STALE_RUN_TIMEOUT = Duration.ofHours(1);
    private static final Map<String, Object> DETECTED = Map.of("detected", true);
    private static final Set<String> FINDING_TYPES = Set.of(
            "MISSING_OBJECT",
            "MISSING_ACTIVE_VECTOR",
            "ORPHAN_VECTOR",
            "ORPHAN_OBJECT",
            "STALE_RUN",
            "ORPHAN_STAGING_OBJECT");

    private final SystemDatabaseMapper mapper;
    private final ObjectStorage objectStorage;
    private final ImportStagingStore stagingStore;
    private final AdminJobService jobService;

    public ConsistencyScanService(
            SystemDatabaseMapper mapper,
            ObjectStorage objectStorage,
            ImportStagingStore stagingStore,
            AdminJobService jobService
    ) {
        this.mapper = mapper;
        this.objectStorage = objectStorage;
        this.stagingStore = stagingStore;
        this.jobService = jobService;
    }

    @Override
    public AdminJobType type() {
        return AdminJobType.CONSISTENCY_SCAN;
    }

    public AdminJobDto start(AuthPrincipal actor) {
        return jobService.create(
                actor,
                AdminJobType.CONSISTENCY_SCAN,
                "DATABASE",
                null,
                Map.of());
    }

    public AdminJobDto get(AuthPrincipal actor, Long jobId) {
        AdminJobDto job = jobService.get(actor, jobId);
        if (job.jobType() != AdminJobType.CONSISTENCY_SCAN) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "Consistency scan not found");
        }
        return job;
    }

    @Override
    public void execute(AdminJobExecutionContext context) {
        validateJob(context.job());
        context.assertActive();

        List<StoredObjectMetadata> inventory = objectStorage.list(DOCUMENT_NAMESPACE);
        Set<String> storedObjectKeys = new HashSet<>();
        inventory.forEach(metadata -> storedObjectKeys.add(metadata.objectKey()));

        scanMissingObjects(context, storedObjectKeys);
        scanMissingActiveVectors(context);
        scanOrphanVectors(context);
        scanOrphanObjects(context, inventory);
        scanStaleRuns(context);
        scanOrphanStagingObjects(context);
        context.heartbeat();
    }

    private void scanMissingObjects(
            AdminJobExecutionContext context,
            Set<String> storedObjectKeys
    ) {
        List<SystemDatabaseMapper.DocumentObjectRow> documents =
                mapper.documentsMissingObjects();
        documents.stream()
                .filter(document -> !storedObjectKeys.contains(document.objectKey()))
                .forEach(document -> recordFinding(
                        context,
                        "missing-object:" + document.id(),
                        "MISSING_OBJECT",
                        "DOCUMENT",
                        document.id()));
    }

    private void scanMissingActiveVectors(AdminJobExecutionContext context) {
        List<Long> documentIds = mapper.activeDocumentsMissingVectors();
        documentIds.forEach(documentId -> recordFinding(
                context,
                "missing-active-vector:" + documentId,
                "MISSING_ACTIVE_VECTOR",
                "DOCUMENT",
                documentId));
    }

    private void scanOrphanVectors(AdminJobExecutionContext context) {
        List<SystemDatabaseMapper.VectorReferenceRow> references =
                mapper.orphanVectorReferences();
        references.forEach(reference -> recordFinding(
                context,
                "orphan-vector:" + reference.referenceType() + ":" + reference.referenceId(),
                "ORPHAN_VECTOR",
                reference.referenceType(),
                reference.referenceId()));
    }

    private void scanOrphanObjects(
            AdminJobExecutionContext context,
            List<StoredObjectMetadata> inventory
    ) {
        Set<String> referencedKeys = Set.copyOf(mapper.referencedObjectKeys());
        inventory.stream()
                .filter(metadata -> !referencedKeys.contains(metadata.objectKey()))
                .forEach(metadata -> recordFinding(
                        context,
                        "orphan-object:" + sha256(metadata.objectKey()),
                        "ORPHAN_OBJECT",
                        "STORAGE_OBJECT",
                        null));
    }

    private void scanStaleRuns(AdminJobExecutionContext context) {
        List<Long> runIds = mapper.staleIngestRuns(STALE_RUN_TIMEOUT.toString());
        runIds.forEach(runId -> recordFinding(
                context,
                "stale-run:" + runId,
                "STALE_RUN",
                "INGEST_RUN",
                runId));
    }

    private void scanOrphanStagingObjects(AdminJobExecutionContext context) {
        List<StagedImportObject> stagedObjects = stagingStore.findOlderThan(Instant.MAX);
        stagedObjects.stream()
                .filter(staged -> !ingestItemExists(staged.runId(), staged.itemId()))
                .forEach(staged -> recordFinding(
                        context,
                        "orphan-staging:" + staged.runId() + ":" + staged.itemId(),
                        "ORPHAN_STAGING_OBJECT",
                        "INGEST_RUN_ITEM",
                        staged.itemId()));
    }

    private boolean ingestItemExists(long runId, long itemId) {
        return mapper.ingestItemExists(runId, itemId);
    }

    private void recordFinding(
            AdminJobExecutionContext context,
            String itemKey,
            String itemType,
            String targetType,
            Long targetId
    ) {
        if (!FINDING_TYPES.contains(itemType)) {
            throw new IllegalArgumentException("Unsupported consistency finding type");
        }
        context.assertActive();
        AdminJobItemDto item = context.upsertItem(itemKey, itemType, targetType, targetId);
        context.checkpoint(item.id(), AdminJobStatus.SUCCEEDED, DETECTED);
    }

    private void validateJob(AdminJobDto job) {
        if (job.jobType() != AdminJobType.CONSISTENCY_SCAN
                || !"DATABASE".equals(job.targetType())
                || job.targetId() != null) {
            throw new IllegalArgumentException("Invalid consistency scan job target");
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

}
