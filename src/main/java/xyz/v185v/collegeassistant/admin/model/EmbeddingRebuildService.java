package xyz.v185v.collegeassistant.admin.model;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobExecutionContext;
import xyz.v185v.collegeassistant.admin.job.AdminJobHandler;
import xyz.v185v.collegeassistant.admin.job.AdminJobItemDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobLeaseLostException;
import xyz.v185v.collegeassistant.admin.job.AdminJobPartialSuccessException;
import xyz.v185v.collegeassistant.admin.job.AdminJobRepository;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.ai.EffectiveEmbeddingContext;
import xyz.v185v.collegeassistant.ai.ModelRegistry;
import xyz.v185v.collegeassistant.ai.ModelRuntimeFactory;
import xyz.v185v.collegeassistant.ai.ModelRuntimeSpec;
import xyz.v185v.collegeassistant.ai.config.ModelConfiguration;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationRepository;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationStatus;
import xyz.v185v.collegeassistant.ai.config.ModelKind;
import xyz.v185v.collegeassistant.ai.config.ModelSecretCipher;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.document.DocumentEmbeddingRebuildCandidate;
import xyz.v185v.collegeassistant.document.DocumentRecord;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRecord;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;
import xyz.v185v.collegeassistant.document.EmbeddingChunkTarget;
import xyz.v185v.collegeassistant.document.DocumentStatus;
import xyz.v185v.collegeassistant.ingestion.parsing.DocumentParser;
import xyz.v185v.collegeassistant.ingestion.parsing.ParsedDocument;
import xyz.v185v.collegeassistant.ingestion.parsing.TextChunk;
import xyz.v185v.collegeassistant.ingestion.parsing.TextChunker;
import xyz.v185v.collegeassistant.storage.ObjectStorage;
import xyz.v185v.collegeassistant.ugc.UgcEmbeddingRebuildCandidate;
import xyz.v185v.collegeassistant.ugc.UgcIndexingService;
import xyz.v185v.collegeassistant.ugc.UgcPostDto;
import xyz.v185v.collegeassistant.ugc.UgcRepository;

/** 构建、校验、激活并放弃隔离的候选向量空间。 */
@Service
public class EmbeddingRebuildService implements AdminJobHandler {

    static final String CONFIGURATION_TARGET_TYPE = "EMBEDDING_CONFIGURATION";
    private static final String DOCUMENT_TARGET_TYPE = "DOCUMENT";
    private static final String UGC_TARGET_TYPE = "UGC";
    private static final String MANIFEST_VERIFICATION_ITEM_KEY = "manifest:verified";
    private static final String MANIFEST_VERIFICATION_ITEM_TYPE = "EMBEDDING_REBUILD_MANIFEST";
    private static final String OFFICIAL_SOURCE_TYPE = "OFFICIAL";
    private static final String GENERAL = "GENERAL";

    private final AdminJobRepository jobRepository;
    private final ModelConfigurationRepository configurationRepository;
    private final EmbeddingRebuildManifestRepository manifestRepository;
    private final DocumentRepository documentRepository;
    private final UgcRepository ugcRepository;
    private final EmbeddingChunkRepository chunkRepository;
    private final ObjectStorage objectStorage;
    private final DocumentParser parser;
    private final TextChunker chunker;
    private final ModelSecretCipher cipher;
    private final ModelRuntimeFactory runtimeFactory;
    private final ModelRegistry modelRegistry;

    public EmbeddingRebuildService(
            AdminJobRepository jobRepository,
            ModelConfigurationRepository configurationRepository,
            EmbeddingRebuildManifestRepository manifestRepository,
            DocumentRepository documentRepository,
            UgcRepository ugcRepository,
            EmbeddingChunkRepository chunkRepository,
            ObjectStorage objectStorage,
            DocumentParser parser,
            TextChunker chunker,
            ModelSecretCipher cipher,
            ModelRuntimeFactory runtimeFactory,
            ModelRegistry modelRegistry
    ) {
        this.jobRepository = jobRepository;
        this.configurationRepository = configurationRepository;
        this.manifestRepository = manifestRepository;
        this.documentRepository = documentRepository;
        this.ugcRepository = ugcRepository;
        this.chunkRepository = chunkRepository;
        this.objectStorage = objectStorage;
        this.parser = parser;
        this.chunker = chunker;
        this.cipher = cipher;
        this.runtimeFactory = runtimeFactory;
        this.modelRegistry = modelRegistry;
    }

    @Override
    public AdminJobType type() {
        return AdminJobType.EMBEDDING_REBUILD;
    }

/** 列出所有持久化尝试，包括仍受栅栏保护、等待放弃的终态尝试。 */
    public List<AdminJobDto> list(AuthPrincipal actor) {
        requireAdmin(actor);
        return jobRepository.findEmbeddingRebuilds();
    }

/** 在共享维护锁阻止内容变更期间创建冻结的目标清单。 */
    @Transactional
    public AdminJobDto start(AuthPrincipal actor, long configurationId) {
        requireAdmin(actor);
        requireConfigurationId(configurationId);
        jobRepository.lockEmbeddingMaintenanceState();
        if (jobRepository.hasBlockingEmbeddingRebuildForPendingConfiguration()) {
            throw conflict("Embedding maintenance window is already active");
        }
        ModelConfiguration candidate = requirePendingCandidate(configurationId, true);
        if (candidate.isBootstrap()) {
            throw conflict("Bootstrap embedding configuration cannot be rebuilt");
        }
        if (jobRepository.hasConflictingEmbeddingRebuildWork()) {
            throw conflict("Cannot start embedding rebuild while active ingestion or indexing work exists");
        }
        if (jobRepository.findLatestEmbeddingRebuild(configurationId).isPresent()) {
            throw conflict("Embedding configuration already has a rebuild attempt; retry or discard it");
        }

        List<EmbeddingRebuildManifestItem> manifest = snapshotManifest();
        AdminJobDto job = jobRepository.create(
                AdminJobType.EMBEDDING_REBUILD,
                CONFIGURATION_TARGET_TYPE,
                configurationId,
                actor.userId(),
                Map.of(
                        "embeddingConfigurationId", configurationId,
                        "targetCount", manifest.size()));
        manifestRepository.insertBatch(job.id(), manifest);
        return job;
    }

/** 仅在证明候选分区仍匹配后激活已完成候选项。 */
    @Transactional
    public void activate(AuthPrincipal actor, long configurationId) {
        requireAdmin(actor);
        requireConfigurationId(configurationId);
        jobRepository.lockEmbeddingMaintenanceState();
        ModelConfiguration candidate = requirePendingCandidate(configurationId, true);
        AdminJobDto job = jobRepository.findLatestEmbeddingRebuild(configurationId)
                .filter(current -> current.status() == AdminJobStatus.SUCCEEDED)
                .orElseThrow(() -> conflict("Embedding rebuild has not completed successfully"));
        List<EmbeddingRebuildManifestItem> manifest = manifestRepository.findByJobId(job.id());
        if (manifestRepository.countIncomplete(job.id()) != 0) {
            throw conflict("Embedding rebuild manifest still has incomplete targets");
        }
        verifyCandidateTargets(configurationId, manifest);
        verifyCurrentTargetVersions(manifest);

        ModelConfiguration retired = configurationRepository.findActive(ModelKind.EMBEDDING)
                .filter(configuration -> !configuration.isBootstrap())
                .orElse(null);
        EffectiveEmbeddingContext context = candidateContext(candidate);
        configurationRepository.activateEmbedding(configurationId);
        if (retired != null) {
            jobRepository.create(
                    AdminJobType.INDEX_CLEANUP,
                    CONFIGURATION_TARGET_TYPE,
                    retired.id(),
                    actor.userId(),
                    Map.of("embeddingConfigurationId", retired.id()));
        }
        afterCommit(() -> {
            modelRegistry.activateEmbedding(context);
            modelRegistry.clearEmbeddingCandidate();
        });
    }

/** 取消排队尝试，或放弃终态候选项并计划清理其分区。 */
    @Transactional
    public AdminJobDto discard(AuthPrincipal actor, long configurationId) {
        requireAdmin(actor);
        requireConfigurationId(configurationId);
        jobRepository.lockEmbeddingMaintenanceState();
        ModelConfiguration candidate = requirePendingCandidate(configurationId, true);
        if (candidate.isBootstrap()) {
            throw conflict("Bootstrap embedding configuration cannot be discarded");
        }
        Optional<AdminJobDto> latest = jobRepository.findLatestEmbeddingRebuild(configurationId);
        if (latest.map(AdminJobDto::status).orElse(null) == AdminJobStatus.RUNNING) {
            throw conflict("A running embedding rebuild cannot be discarded");
        }
        if (latest.map(AdminJobDto::status).orElse(null) == AdminJobStatus.QUEUED
                && !jobRepository.cancelQueuedEmbeddingRebuild(configurationId)) {
            AdminJobStatus racedStatus = jobRepository.findLatestEmbeddingRebuild(configurationId)
                    .map(AdminJobDto::status)
                    .orElse(null);
            if (racedStatus == AdminJobStatus.RUNNING) {
                throw conflict("A running embedding rebuild cannot be discarded");
            }
            throw conflict("Embedding rebuild cancellation raced with another operation");
        }
        configurationRepository.retirePendingEmbedding(
                configurationId, "Embedding rebuild discarded by administrator");
        AdminJobDto cleanup = jobRepository.create(
                AdminJobType.INDEX_CLEANUP,
                CONFIGURATION_TARGET_TYPE,
                configurationId,
                actor.userId(),
                Map.of("embeddingConfigurationId", configurationId));
        afterCommit(() -> clearPendingCandidate(configurationId));
        return cleanup;
    }

    @Override
    public void execute(AdminJobExecutionContext context) {
        long configurationId = requireTargetConfiguration(context.job());
        ModelConfiguration candidate = configurationRepository.findById(configurationId)
                .filter(configuration -> configuration.kind() == ModelKind.EMBEDDING
                        && configuration.status() == ModelConfigurationStatus.PENDING)
                .orElseThrow(() -> conflict("Embedding rebuild candidate is no longer pending"));
        EffectiveEmbeddingContext embedding = candidateContext(candidate);
        List<EmbeddingRebuildManifestItem> manifest = manifestRepository.findByJobId(context.job().id());
        int failures = 0;
        for (EmbeddingRebuildManifestItem target : manifest) {
            context.heartbeat();
            AdminJobItemDto item = context.upsertItem(
                    itemKey(target), "EMBEDDING_REBUILD", target.targetType(), target.targetId());
            if (item.status() == AdminJobStatus.SUCCEEDED && target.isComplete()) {
                continue;
            }
            try {
                List<EmbeddingChunkRecord> records = prepareTarget(target, embedding);
                context.inLeaseTransaction(operations -> {
                    assertCurrentTargetVersion(target);
                    chunkRepository.replace(
                            target.targetType(), target.targetId(), configurationId, records);
                    if (!manifestRepository.markCompleted(
                            context.job().id(), target.targetType(), target.targetId(),
                            records.size(), checksum(records))) {
                        throw conflict("Embedding rebuild manifest completion changed unexpectedly");
                    }
                    operations.checkpoint(item.id(), AdminJobStatus.SUCCEEDED, Map.of(
                            "targetType", target.targetType(),
                            "targetId", target.targetId(),
                            "chunkCount", records.size(),
                            "chunkTextChecksum", checksum(records)));
                    return null;
                });
            } catch (AdminJobLeaseLostException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                failures++;
                context.checkpoint(item.id(), AdminJobStatus.FAILED, Map.of(
                        "targetType", target.targetType(),
                        "targetId", target.targetId(),
                        "error", "Embedding rebuild target failed"));
            }
        }
        if (failures > 0) {
            String summary = failures + " embedding rebuild target(s) failed";
            if (failures < manifest.size()) {
                throw new AdminJobPartialSuccessException(
                        "EMBEDDING_REBUILD_PARTIAL", summary);
            }
            throw new BusinessException(ErrorCode.INGEST_FAILED, summary);
        }
        verifyManifestForActivation(context, configurationId, manifest);
    }

    /**
* 仅在任务租约下重新检查候选分区和冻结来源版本后，才持久化明确证明。
* 管理界面必须使用此检查点，不能把没有失败条目记录误认为可以激活。
     */
    private void verifyManifestForActivation(
            AdminJobExecutionContext context,
            long configurationId,
            List<EmbeddingRebuildManifestItem> manifest
    ) {
        AdminJobItemDto verification = context.upsertItem(
                MANIFEST_VERIFICATION_ITEM_KEY,
                MANIFEST_VERIFICATION_ITEM_TYPE,
                CONFIGURATION_TARGET_TYPE,
                configurationId);
        context.inLeaseTransaction(operations -> {
            verifyCandidateTargets(configurationId, manifest);
            verifyCurrentTargetVersions(manifest);
            operations.checkpoint(verification.id(), AdminJobStatus.SUCCEEDED, Map.of(
                    "manifestVerified", true,
                    "targetCount", manifest.size()));
            return null;
        });
    }

    private List<EmbeddingRebuildManifestItem> snapshotManifest() {
        List<EmbeddingRebuildManifestItem> manifest = new ArrayList<>();
        for (DocumentEmbeddingRebuildCandidate document :
                documentRepository.findActiveForEmbeddingRebuild()) {
            manifest.add(EmbeddingRebuildManifestItem.pending(
                    DOCUMENT_TARGET_TYPE, document.documentId(), document.contentVersion()));
        }
        for (UgcEmbeddingRebuildCandidate post : ugcRepository.findApprovedForEmbeddingRebuild()) {
            manifest.add(EmbeddingRebuildManifestItem.pending(
                    UGC_TARGET_TYPE, post.postId(), post.contentVersion()));
        }
        return List.copyOf(manifest);
    }

    private void verifyCandidateTargets(
            long configurationId,
            List<EmbeddingRebuildManifestItem> manifest
    ) {
        Set<EmbeddingChunkTarget> expected = manifest.stream()
                .map(item -> new EmbeddingChunkTarget(item.targetType(), item.targetId()))
                .collect(Collectors.toUnmodifiableSet());
        Set<EmbeddingChunkTarget> actual = Set.copyOf(
                chunkRepository.findTargetsByConfiguration(configurationId));
        if (!actual.equals(expected)) {
            throw conflict("Candidate embedding partition has missing or extra targets");
        }
        for (EmbeddingRebuildManifestItem item : manifest) {
            List<EmbeddingChunkRecord> records = chunkRepository.findForReference(
                    item.targetType(), item.targetId(), configurationId);
            if (records.size() != item.chunkCount()
                    || !checksum(records).equals(item.chunkTextChecksum())) {
                throw conflict("Candidate embedding chunks do not match the rebuild manifest");
            }
        }
    }

    private void verifyCurrentTargetVersions(List<EmbeddingRebuildManifestItem> manifest) {
        for (EmbeddingRebuildManifestItem item : manifest) {
            assertCurrentTargetVersion(item);
        }
    }

    private void assertCurrentTargetVersion(EmbeddingRebuildManifestItem target) {
        if (DOCUMENT_TARGET_TYPE.equals(target.targetType())) {
            DocumentRecord document = documentRepository.lockActiveForEmbeddingRebuild(target.targetId())
                    .orElseThrow(() -> conflict("A snapshotted document is no longer active"));
            if (!target.contentVersion().equals(document.contentHash())) {
                throw conflict("A snapshotted document changed during embedding maintenance");
            }
            return;
        }
        UgcEmbeddingRebuildCandidate post = ugcRepository.lockApprovedForEmbeddingRebuild(target.targetId())
                .orElseThrow(() -> conflict("A snapshotted UGC post is no longer approved"));
        if (!target.contentVersion().equals(post.contentVersion())) {
            throw conflict("A snapshotted UGC post changed during embedding maintenance");
        }
    }

    private List<EmbeddingChunkRecord> prepareTarget(
            EmbeddingRebuildManifestItem target,
            EffectiveEmbeddingContext embedding
    ) {
        if (DOCUMENT_TARGET_TYPE.equals(target.targetType())) {
            return prepareDocument(target, embedding);
        }
        if (UGC_TARGET_TYPE.equals(target.targetType())) {
            return prepareUgc(target, embedding);
        }
        throw new IllegalArgumentException("Unknown embedding rebuild target type");
    }

    private List<EmbeddingChunkRecord> prepareDocument(
            EmbeddingRebuildManifestItem target,
            EffectiveEmbeddingContext embedding
    ) {
        DocumentRecord document = documentRepository.findById(target.targetId())
                .orElseThrow(() -> conflict("Snapshotted document was not found"));
        if (document.documentStatus() != DocumentStatus.ACTIVE
                || !target.contentVersion().equals(document.contentHash())) {
            throw conflict("Snapshotted document changed before embedding rebuild");
        }
        if (document.fileObjectKey() == null || document.fileObjectKey().isBlank()) {
            throw conflict("Snapshotted document has no stored object");
        }
        ParsedDocument parsed;
        try (InputStream input = objectStorage.open(document.fileObjectKey())) {
            parsed = parser.parse(input, documentFilename(document));
        } catch (java.io.IOException exception) {
            throw new BusinessException(
                    ErrorCode.STORAGE_FAILED, "Stored document cannot be read", exception);
        }
        List<TextChunk> chunks = chunker.chunk(parsed.text());
        List<List<Double>> vectors = vectors(embedding, chunks);
        Map<String, Object> metadata = new LinkedHashMap<>(parsed.metadata());
        metadata.putIfAbsent("mediaType", parsed.mediaType());
        List<EmbeddingChunkRecord> records = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            TextChunk chunk = chunks.get(index);
            records.add(new EmbeddingChunkRecord(
                    null, embedding.configurationId(), DOCUMENT_TARGET_TYPE, document.id(),
                    chunk.index(), chunk.text(), vectors.get(index), OFFICIAL_SOURCE_TYPE,
                    document.sourceUrl(), document.title(), document.sourceOrg(), document.publishedAt(),
                    audience(document.degree()), audience(document.college()), audience(document.campus()),
                    audience(document.gradeRange()), metadata, null));
        }
        return List.copyOf(records);
    }

    private List<EmbeddingChunkRecord> prepareUgc(
            EmbeddingRebuildManifestItem target,
            EffectiveEmbeddingContext embedding
    ) {
        UgcEmbeddingRebuildCandidate candidate = ugcRepository.lockApprovedForEmbeddingRebuild(
                        target.targetId())
                .orElseThrow(() -> conflict("Snapshotted UGC post is no longer approved"));
        if (!target.contentVersion().equals(candidate.contentVersion())) {
            throw conflict("Snapshotted UGC post changed before embedding rebuild");
        }
        UgcPostDto post = candidate.post();
        List<TextChunk> chunks = chunker.chunk(post.content());
        List<List<Double>> vectors = vectors(embedding, chunks);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("authorId", post.authorId());
        metadata.put("authorName", post.authorName());
        metadata.put("tags", post.tags() == null ? List.of() : List.copyOf(post.tags()));
        metadata.put("likes", post.likes());
        metadata.put("reports", post.reports());
        if (post.relatedCourse() != null) {
            metadata.put("relatedCourse", post.relatedCourse());
        }
        if (post.relatedLocation() != null) {
            metadata.put("relatedLocation", post.relatedLocation());
        }
        List<EmbeddingChunkRecord> records = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            TextChunk chunk = chunks.get(index);
            records.add(new EmbeddingChunkRecord(
                    null, embedding.configurationId(), UGC_TARGET_TYPE, post.id(),
                    chunk.index(), chunk.text(), vectors.get(index), UgcIndexingService.UGC_SOURCE_TYPE,
                    null, post.title(), post.authorName(), post.createdAt(),
                    GENERAL, GENERAL, GENERAL, GENERAL, metadata, null));
        }
        return List.copyOf(records);
    }

    private List<List<Double>> vectors(
            EffectiveEmbeddingContext embedding,
            List<TextChunk> chunks
    ) {
        List<List<Double>> vectors = embedding.gateway().embedAll(
                chunks.stream().map(TextChunk::text).toList());
        if (vectors == null || vectors.size() != chunks.size()) {
            throw new IllegalStateException("Embedding count does not match chunk count");
        }
        return List.copyOf(vectors);
    }

    private EffectiveEmbeddingContext candidateContext(ModelConfiguration candidate) {
        Optional<EffectiveEmbeddingContext> pending = modelRegistry.pendingEmbedding();
        if (pending != null && pending.filter(context -> context.configurationId() == candidate.id())
                .isPresent()) {
            return pending.orElseThrow();
        }
        cipher.requireAvailable();
        char[] secret = candidate.hasPersistedSecret()
                ? cipher.decrypt(candidate.encryptedSecret(), candidate.aad())
                : new char[0];
        try (ModelRuntimeSpec spec = new ModelRuntimeSpec(
                candidate.kind(), candidate.provider(), candidate.model(), candidate.baseUrl(),
                candidate.temperature(), candidate.dimension(), secret)) {
            return runtimeFactory.buildEmbedding(candidate.id(), spec);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    private void clearPendingCandidate(long configurationId) {
        Optional<EffectiveEmbeddingContext> pending = modelRegistry.pendingEmbedding();
        if (pending != null && pending.map(EffectiveEmbeddingContext::configurationId)
                .filter(id -> id == configurationId).isPresent()) {
            modelRegistry.clearEmbeddingCandidate();
        }
    }

    private String itemKey(EmbeddingRebuildManifestItem target) {
        return target.targetType().toLowerCase(java.util.Locale.ROOT) + ':' + target.targetId();
    }

    private String documentFilename(DocumentRecord document) {
        if (document.originalFilename() != null && !document.originalFilename().isBlank()) {
            return document.originalFilename();
        }
        return "document-" + document.id();
    }

    private String audience(String value) {
        return value == null || value.isBlank() ? GENERAL : value;
    }

    private String checksum(List<EmbeddingChunkRecord> records) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (EmbeddingChunkRecord record : records) {
                digest.update(ByteBuffer.allocate(Integer.BYTES)
                        .putInt(record.chunkIndex()).array());
                byte[] text = record.chunkText().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(text.length).array());
                digest.update(text);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ModelConfiguration requirePendingCandidate(long configurationId, boolean locked) {
        Optional<ModelConfiguration> configuration = locked
                ? configurationRepository.lockById(configurationId)
                : configurationRepository.findById(configurationId);
        return configuration
                .filter(value -> value.kind() == ModelKind.EMBEDDING)
                .filter(value -> value.status() == ModelConfigurationStatus.PENDING)
                .orElseThrow(() -> conflict("Pending embedding configuration was not found"));
    }

    private long requireTargetConfiguration(AdminJobDto job) {
        if (job.targetId() == null || job.targetId() <= 0
                || !CONFIGURATION_TARGET_TYPE.equals(job.targetType())) {
            throw new IllegalArgumentException("Embedding rebuild jobs require an embedding configuration target");
        }
        return job.targetId();
    }

    private void requireConfigurationId(long configurationId) {
        if (configurationId <= 0) {
            throw new IllegalArgumentException("Embedding configuration id must be positive");
        }
    }

    private void requireAdmin(AuthPrincipal actor) {
        if (actor == null || !UserRole.ADMIN.name().equals(actor.role())) {
            throw new BusinessException(ErrorCode.ADMIN_REQUIRED, "Administrator role required");
        }
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}
