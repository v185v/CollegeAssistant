package xyz.v185v.collegeassistant.ingestion.application;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.crawl.CrawledDocument;
import xyz.v185v.collegeassistant.ingestion.IngestRunExecutionContext;
import xyz.v185v.collegeassistant.ingestion.IngestRunItemStatus;
import xyz.v185v.collegeassistant.ingestion.IngestRunLease;
import xyz.v185v.collegeassistant.ingestion.IngestRunLeaseLostException;
import xyz.v185v.collegeassistant.ingestion.parsing.DocumentParser;
import xyz.v185v.collegeassistant.ingestion.parsing.ParsedDocument;
import xyz.v185v.collegeassistant.ingestion.parsing.PreparedEmbeddingChunk;
import xyz.v185v.collegeassistant.ingestion.parsing.TextChunk;
import xyz.v185v.collegeassistant.ingestion.parsing.TextChunker;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import xyz.v185v.collegeassistant.ai.EffectiveEmbeddingContext;
import xyz.v185v.collegeassistant.ai.EmbeddingGateway;
import xyz.v185v.collegeassistant.ai.ModelRegistry;
import xyz.v185v.collegeassistant.admin.job.AdminJobExecutionContext;
import xyz.v185v.collegeassistant.admin.job.AdminJobLeaseLostException;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.document.DocumentRecord;
import xyz.v185v.collegeassistant.document.DocumentReferenceType;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.DocumentStatus;
import xyz.v185v.collegeassistant.document.DocumentWriteResult;
import xyz.v185v.collegeassistant.document.DocumentWriteStatus;
import xyz.v185v.collegeassistant.document.SourceRecord;
import xyz.v185v.collegeassistant.storage.ObjectStorage;
import xyz.v185v.collegeassistant.storage.StoredObject;

/** 爬虫和本地导入摄取共用的解析、分块、向量化、存储与索引流程。 */
@Component
public class DocumentIngestionPipeline {

    private static final String DOCUMENT_NAMESPACE = "documents";
    private static final String OFFICIAL = "OFFICIAL";
    private static final String GENERAL = "GENERAL";
    private static final int MAX_CLEANUP_ATTEMPTS = 3;

    private final DocumentRepository documentRepository;
    private final DocumentParser parser;
    private final TextChunker chunker;
    private final Supplier<EffectiveEmbeddingContext> embeddingContextSupplier;
    private final ObjectStorage objectStorage;
    private final DocumentIndexWriter indexWriter;

    @Autowired
    public DocumentIngestionPipeline(
            DocumentRepository documentRepository,
            DocumentParser parser,
            TextChunker chunker,
            ModelRegistry modelRegistry,
            ObjectStorage objectStorage,
            DocumentIndexWriter indexWriter
    ) {
        this(
                documentRepository,
                parser,
                chunker,
                java.util.Objects.requireNonNull(modelRegistry, "modelRegistry")::requireEmbedding,
                objectStorage,
                indexWriter);
    }

/** 为使用固定向量空间的针对性单元测试保留的兼容构造函数。 */
    public DocumentIngestionPipeline(
            DocumentRepository documentRepository,
            DocumentParser parser,
            TextChunker chunker,
            EmbeddingGateway embeddingGateway,
            ObjectStorage objectStorage,
            DocumentIndexWriter indexWriter
    ) {
        this(
                documentRepository,
                parser,
                chunker,
                () -> new EffectiveEmbeddingContext(
                        1L, java.util.Objects.requireNonNull(embeddingGateway, "embeddingGateway")),
                objectStorage,
                indexWriter);
    }

    private DocumentIngestionPipeline(
            DocumentRepository documentRepository,
            DocumentParser parser,
            TextChunker chunker,
            Supplier<EffectiveEmbeddingContext> embeddingContextSupplier,
            ObjectStorage objectStorage,
            DocumentIndexWriter indexWriter
    ) {
        this.documentRepository = documentRepository;
        this.parser = parser;
        this.chunker = chunker;
        this.embeddingContextSupplier = java.util.Objects.requireNonNull(
                embeddingContextSupplier, "embeddingContextSupplier");
        this.objectStorage = objectStorage;
        this.indexWriter = indexWriter;
    }

/** 仅在调用方运行租约仍有效时发布文档。 */
    public PipelineResult ingest(
            PipelineRequest request,
            IngestRunExecutionContext context,
            Long itemId
    ) {
        return ingestInternal(request, context, null, itemId);
    }

/** 仅在持久化管理任务仍拥有租约时发布重建索引。 */
    public PipelineResult ingestForAdminJob(
            PipelineRequest request,
            AdminJobExecutionContext context,
            Long itemId
    ) {
        if (context == null) {
            throw new IllegalArgumentException("Administration job context is required");
        }
        return ingestInternal(request, null, context, itemId);
    }

    private PipelineResult ingestInternal(
            PipelineRequest request,
            IngestRunExecutionContext context,
            AdminJobExecutionContext adminContext,
            Long itemId
    ) {
        if (request == null) {
            throw new IllegalArgumentException("Pipeline request is required");
        }
        if (!"NONE".equals(request.source().deletionStatus())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Document source is being deleted");
        }
        String contentHash = sha256(request.rawBytes());
        ExistingIdentity identity = resolveIdentity(request, contentHash);
        if (identity.unchanged()) {
            return refreshUnchanged(request, identity.existing(), context, adminContext, itemId);
        }

        ParsedDocument parsed;
        List<TextChunk> chunks;
        try {
            parsed = parser.parse(new ByteArrayInputStream(request.rawBytes()), request.filename());
            chunks = chunker.chunk(parsed.text());
        } catch (RuntimeException exception) {
            throw failure(FailureStage.PARSE, exception);
        }
        EffectiveEmbeddingContext embeddingContext;
        List<List<Double>> vectors;
        try {
            embeddingContext = embeddingContextSupplier.get();
            vectors = embeddingContext.gateway().embedAll(
                    chunks.stream().map(TextChunk::text).toList());
            if (vectors == null || vectors.size() != chunks.size()) {
                throw new BusinessException(ErrorCode.EMBEDDING_FAILED, "Embedding generation failed");
            }
        } catch (BusinessException exception) {
            if (exception.getCode() == ErrorCode.MODEL_CONFIG_UNAVAILABLE) {
                throw exception;
            }
            throw failure(FailureStage.EMBEDDING, exception);
        } catch (RuntimeException exception) {
            throw failure(FailureStage.EMBEDDING, exception);
        }

        boolean reuseOldObject = request.rebuildVectors()
                && identity.existing() != null
                && contentHash.equals(identity.existing().contentHash())
                && identity.existing().fileObjectKey() != null
                && !identity.existing().fileObjectKey().isBlank();
        String newObjectKey = null;
        String objectKey;
        try {
            if (reuseOldObject) {
                objectKey = identity.existing().fileObjectKey();
            } else {
                heartbeat(context, adminContext);
                StoredObject stored = objectStorage.save(
                        storageNamespace(context, adminContext, itemId), safeFilename(request.filename()),
                        new ByteArrayInputStream(request.rawBytes()));
                objectKey = stored.objectKey();
                newObjectKey = objectKey;
            }
        } catch (RuntimeException exception) {
            if (exception instanceof IngestRunLeaseLostException) {
                throw exception;
            }
            throw failure(FailureStage.STORAGE, exception);
        }

        try {
            heartbeat(context, adminContext);
            DocumentRecord candidate = candidate(request, parsed, contentHash, objectKey, identity);
            List<PreparedEmbeddingChunk> preparedChunks = prepareChunks(request, candidate, parsed, chunks, vectors);
            PipelineResult result = publish(
                    candidate, request.force() || request.rebuildVectors() || identity.replacement(),
                    preparedChunks, embeddingContext, context, adminContext, itemId);
            if (result.status() == DocumentWriteStatus.UNCHANGED && newObjectKey != null) {
                deleteQuietly(newObjectKey);
                newObjectKey = null;
            }
            if (result.status() != DocumentWriteStatus.UNCHANGED) {
                deleteReplacedQuietly(identity.existing(), result.document().fileObjectKey());
            }
            return result;
        } catch (RuntimeException exception) {
            if (newObjectKey != null) {
                deleteQuietly(newObjectKey);
            }
            if (exception instanceof IngestRunLeaseLostException) {
                throw exception;
            }
            if (exception instanceof PipelineException || exception instanceof BusinessException) {
                throw exception;
            }
            throw failure(FailureStage.INDEX, exception);
        }
    }

    private ExistingIdentity resolveIdentity(PipelineRequest request, String contentHash) {
        try {
            if (request.referenceType() == DocumentReferenceType.EXTERNAL) {
                DocumentRecord existing = documentRepository.findBySourceIdAndSourceUrl(
                        request.source().id(), request.externalSourceUrl()).orElse(null);
                requireIngestible(existing);
                boolean unchanged = existing != null && contentHash.equals(existing.contentHash())
                        && !request.rebuildVectors();
                return new ExistingIdentity(existing, null, unchanged, false);
            }
            documentRepository.findInternalBySourceIdAndContentHash(
                            request.source().id(), contentHash)
                    .ifPresent(this::requireIngestible);
            DocumentRecord sameContent = documentRepository.findActiveInternalBySourceIdAndContentHash(
                    request.source().id(), contentHash).orElse(null);
            if (sameContent != null) {
                boolean unchanged = !request.force() && !request.rebuildVectors();
                return new ExistingIdentity(
                        sameContent, sameContent.internalLocator(), unchanged, false);
            }
            if (request.replaceDocumentId() != null) {
                DocumentRecord replacement = documentRepository.findById(request.replaceDocumentId())
                        .filter(document -> document.sourceId().equals(request.source().id()))
                        .filter(document -> document.referenceType() == DocumentReferenceType.INTERNAL)
                        .filter(document -> document.documentStatus() != DocumentStatus.DELETING
                                && document.documentStatus() != DocumentStatus.DELETE_FAILED)
                        .orElseThrow(() -> new BusinessException(
                                ErrorCode.RESOURCE_NOT_FOUND, "Replacement document not found"));
                return new ExistingIdentity(replacement, replacement.internalLocator(), false, true);
            }
            String normalizedFilename = normalizeFilename(request.filename());
            documentRepository.findInternalBySourceIdAndNormalizedFilename(
                            request.source().id(), normalizedFilename)
                    .ifPresent(this::requireIngestible);
            DocumentRecord sameName = documentRepository.findActiveInternalBySourceIdAndNormalizedFilename(
                    request.source().id(), normalizedFilename).orElse(null);
            if (sameName != null) {
                throw new BusinessException(
                        ErrorCode.DOCUMENT_REPLACEMENT_REQUIRED,
                        "A replacement document must be selected for this filename");
            }
            return new ExistingIdentity(null, UUID.randomUUID(), false, false);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(FailureStage.LOOKUP, exception);
        }
    }

    private void requireIngestible(DocumentRecord document) {
        if (document != null && (document.documentStatus() == DocumentStatus.DELETING
                || document.documentStatus() == DocumentStatus.DELETE_FAILED)) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Document is being deleted");
        }
    }

    private DocumentRecord candidate(
            PipelineRequest request,
            ParsedDocument parsed,
            String contentHash,
            String objectKey,
            ExistingIdentity identity
    ) {
        String title = request.displayTitle() == null || request.displayTitle().isBlank()
                ? parsed.title() : request.displayTitle().strip();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (request.referenceType() == DocumentReferenceType.EXTERNAL) {
            return new DocumentRecord(
                    identity.existing() == null ? null : identity.existing().id(),
                    request.source().id(), title, request.externalSourceUrl(), request.source().name(),
                    request.publishedAt(), contentHash, objectKey, null,
                    DocumentReferenceType.EXTERNAL, request.externalSourceUrl(), null,
                    null, null, DocumentStatus.ACTIVE, request.etag(),
                    request.httpLastModified(), now,
                    request.checkedAt() == null ? now : request.checkedAt(),
                    audience(request.degree()), audience(request.college()), audience(request.campus()),
                    audience(request.gradeRange()));
        }
        UUID locator = identity.locator();
        return new DocumentRecord(
                identity.existing() == null ? null : identity.existing().id(), request.source().id(), title,
                "internal://document/" + locator, request.source().name(), request.publishedAt(),
                contentHash, objectKey, null, DocumentReferenceType.INTERNAL,
                request.externalSourceUrl(), locator, request.filename(), normalizeFilename(request.filename()),
                DocumentStatus.ACTIVE, null, null, now, now,
                audience(request.degree()), audience(request.college()), audience(request.campus()),
                audience(request.gradeRange()));
    }

    private List<PreparedEmbeddingChunk> prepareChunks(
            PipelineRequest request,
            DocumentRecord document,
            ParsedDocument parsed,
            List<TextChunk> chunks,
            List<List<Double>> vectors
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>(parsed.metadata());
        metadata.putIfAbsent("mediaType", parsed.mediaType());
        List<PreparedEmbeddingChunk> prepared = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            TextChunk chunk = chunks.get(index);
            prepared.add(new PreparedEmbeddingChunk(
                    chunk.index(), chunk.text(), vectors.get(index), OFFICIAL, document.sourceUrl(),
                    document.title(), request.source().name(), request.publishedAt(),
                    audience(request.degree(), parsed.metadata().get("degree")),
                    audience(request.college(), parsed.metadata().get("college")),
                    audience(request.campus(), parsed.metadata().get("campus")),
                    audience(request.gradeRange(), parsed.metadata().get("gradeRange"),
                            parsed.metadata().get("grade_range")),
                    metadata));
        }
        return List.copyOf(prepared);
    }

    private PipelineResult publish(
            DocumentRecord candidate,
            boolean force,
            List<PreparedEmbeddingChunk> chunks,
            EffectiveEmbeddingContext embeddingContext,
            IngestRunExecutionContext context,
            AdminJobExecutionContext adminContext,
            Long itemId
    ) {
        try {
            if (adminContext != null) {
                return adminContext.inLeaseTransaction(operations -> {
                    DocumentRecord locked = documentRepository.lockActiveForReindex(candidate.id())
                            .filter(document -> document.sourceId().equals(candidate.sourceId()))
                            .filter(document -> java.util.Objects.equals(
                                    document.internalLocator(), candidate.internalLocator()))
                            .orElseThrow(() -> new BusinessException(
                                    ErrorCode.CONFLICT,
                                    "Document or source entered deletion during reindex"));
                    if (!locked.sourceUrl().equals(candidate.sourceUrl())) {
                        throw new BusinessException(
                                ErrorCode.CONFLICT, "Document identity changed during reindex");
                    }
                    DocumentWriteResult result = indexWriter.write(
                            candidate, force, chunks, embeddingContext);
                    if (itemId != null) {
                        operations.checkpoint(itemId, AdminJobStatus.RUNNING,
                                Map.of("stage", "INDEXED",
                                        "documentId", result.document().id(),
                                        "writeStatus", result.status().name()));
                    }
                    return new PipelineResult(result.status(), result.document());
                });
            }
            if (context == null) {
                DocumentWriteResult result = indexWriter.write(
                        candidate, force, chunks, embeddingContext);
                return new PipelineResult(result.status(), result.document());
            }
            return context.inLeaseTransaction(operations -> {
                DocumentWriteResult result = indexWriter.write(
                        candidate, force, chunks, embeddingContext);
                if (itemId != null) {
                    operations.checkpoint(
                            itemId,
                            result.status() == DocumentWriteStatus.UNCHANGED
                                    ? IngestRunItemStatus.SKIPPED : IngestRunItemStatus.SUCCEEDED,
                            result.document().id(),
                            Map.of("documentId", result.document().id(),
                                    "writeStatus", result.status().name()));
                }
                return new PipelineResult(result.status(), result.document());
            });
        } catch (RuntimeException exception) {
            if (exception instanceof IngestRunLeaseLostException
                    || exception instanceof AdminJobLeaseLostException
                    || exception instanceof BusinessException) {
                throw exception;
            }
            throw failure(FailureStage.INDEX, exception);
        }
    }

    private void checkpoint(
            IngestRunExecutionContext context,
            AdminJobExecutionContext adminContext,
            Long itemId,
            PipelineResult result
    ) {
        if (itemId == null) {
            return;
        }
        if (adminContext != null) {
            adminContext.inLeaseTransaction(operations -> {
                operations.checkpoint(itemId, AdminJobStatus.RUNNING,
                        Map.of("stage", "INDEX_UNCHANGED",
                                "documentId", result.document().id(),
                                "writeStatus", "UNCHANGED"));
                return null;
            });
        } else if (context != null) {
            context.inLeaseTransaction(operations -> {
                operations.checkpoint(itemId, IngestRunItemStatus.SKIPPED, result.document().id(),
                        Map.of("documentId", result.document().id(), "writeStatus", "UNCHANGED"));
                return null;
            });
        }
    }

    private PipelineResult refreshUnchanged(
            PipelineRequest request,
            DocumentRecord existing,
            IngestRunExecutionContext context,
            AdminJobExecutionContext adminContext,
            Long itemId
    ) {
        if (request.referenceType() != DocumentReferenceType.EXTERNAL
                || request.checkedAt() == null) {
            PipelineResult skipped = new PipelineResult(DocumentWriteStatus.UNCHANGED, existing);
            checkpoint(context, adminContext, itemId, skipped);
            return skipped;
        }
        DocumentRecord refreshed = new DocumentRecord(
                existing.id(), existing.sourceId(), existing.title(), existing.sourceUrl(),
                existing.sourceOrg(), existing.publishedAt(), existing.contentHash(),
                existing.fileObjectKey(), existing.ingestedAt(), existing.referenceType(),
                existing.externalSourceUrl(), existing.internalLocator(), existing.originalFilename(),
                existing.normalizedFilename(), existing.documentStatus(), request.etag(),
                request.httpLastModified(), request.checkedAt(), request.checkedAt(),
                existing.degree(), existing.college(), existing.campus(), existing.gradeRange());
        if (context == null) {
            DocumentRecord stored = documentRepository.write(refreshed, true).document();
            PipelineResult skipped = new PipelineResult(DocumentWriteStatus.UNCHANGED, stored);
            checkpoint(null, adminContext, itemId, skipped);
            return skipped;
        }
        return context.inLeaseTransaction(operations -> {
            DocumentRecord stored = documentRepository.write(refreshed, true).document();
            if (itemId != null) {
                operations.checkpoint(itemId, IngestRunItemStatus.SKIPPED, stored.id(), Map.of(
                        "documentId", stored.id(), "writeStatus", "UNCHANGED"));
            }
            return new PipelineResult(DocumentWriteStatus.UNCHANGED, stored);
        });
    }

    private void heartbeat(
            IngestRunExecutionContext context,
            AdminJobExecutionContext adminContext
    ) {
        if (context != null) {
            context.heartbeat();
        }
        if (adminContext != null) {
            adminContext.heartbeat();
        }
    }

    private String storageNamespace(
            IngestRunExecutionContext context,
            AdminJobExecutionContext adminContext,
            Long itemId
    ) {
        if (adminContext != null && itemId != null && adminContext.lease() != null) {
            return DOCUMENT_NAMESPACE + "-job-" + adminContext.lease().jobId()
                    + "-item-" + itemId + "-attempt-" + adminContext.lease().attempt()
                    + "-lease-" + adminContext.lease().leaseToken();
        }
        if (context == null || itemId == null) {
            return DOCUMENT_NAMESPACE;
        }
        IngestRunLease lease = context.lease();
        if (lease == null) {
            return DOCUMENT_NAMESPACE;
        }
        return DOCUMENT_NAMESPACE + "-run-" + lease.runId()
                + "-item-" + itemId + "-attempt-" + lease.attempt()
                + "-lease-" + lease.leaseToken();
    }

    private void deleteReplacedQuietly(DocumentRecord existing, String retainedObjectKey) {
        if (existing != null && existing.fileObjectKey() != null && !existing.fileObjectKey().isBlank()
                && !existing.fileObjectKey().equals(retainedObjectKey)) {
            deleteQuietly(existing.fileObjectKey());
        }
    }

    private void deleteQuietly(String objectKey) {
        for (int attempt = 1; attempt <= MAX_CLEANUP_ATTEMPTS; attempt++) {
            try {
                objectStorage.delete(objectKey);
                return;
            } catch (RuntimeException ignored) {
// 发布已经成功；清理特意采用尽力而为策略。
            }
        }
    }

    private static String normalizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Document filename is required");
        }
        String normalized = java.text.Normalizer.normalize(filename.strip(), java.text.Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT);
        if (normalized.length() > 512) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Document filename is too long");
        }
        return normalized;
    }

    private static String safeFilename(String filename) {
        String safe = filename == null ? "document" : filename.strip()
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (safe.isBlank() || safe.equals(".") || safe.equals("..")) {
            return "document";
        }
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < safe.length();) {
            int codePoint = safe.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + characterBytes > 180) {
                break;
            }
            result.appendCodePoint(codePoint);
            bytes += characterBytes;
            offset += Character.charCount(codePoint);
        }
        return result.isEmpty() ? "document" : result.toString();
    }

    private static String audience(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return GENERAL;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static PipelineException failure(FailureStage stage, Throwable cause) {
        return new PipelineException(stage, cause);
    }

    public record PipelineRequest(
            SourceRecord source,
            byte[] rawBytes,
            String filename,
            String displayTitle,
            String externalSourceUrl,
            OffsetDateTime publishedAt,
            DocumentReferenceType referenceType,
            Long replaceDocumentId,
            String degree,
            String college,
            String campus,
            String gradeRange,
            boolean force,
            boolean rebuildVectors,
            String etag,
            OffsetDateTime httpLastModified,
            OffsetDateTime checkedAt
    ) {
        public PipelineRequest(
                SourceRecord source,
                byte[] rawBytes,
                String filename,
                String displayTitle,
                String externalSourceUrl,
                OffsetDateTime publishedAt,
                DocumentReferenceType referenceType,
                Long replaceDocumentId,
                String degree,
                String college,
                String campus,
                String gradeRange,
                boolean force,
                boolean rebuildVectors
        ) {
            this(source, rawBytes, filename, displayTitle, externalSourceUrl, publishedAt,
                    referenceType, replaceDocumentId, degree, college, campus, gradeRange,
                    force, rebuildVectors, null, null, null);
        }

        public PipelineRequest {
            if (source == null || rawBytes == null || rawBytes.length > CrawledDocument.MAX_RAW_BYTES
                    || filename == null || filename.isBlank() || referenceType == null) {
                throw new IllegalArgumentException("Pipeline request is invalid");
            }
            if (referenceType == DocumentReferenceType.EXTERNAL
                    && (externalSourceUrl == null || externalSourceUrl.isBlank())) {
                throw new IllegalArgumentException("External document URL is required");
            }
            if (replaceDocumentId != null && replaceDocumentId <= 0) {
                throw new IllegalArgumentException("Replacement document id must be positive");
            }
            if (etag != null && (etag.length() > 512
                    || etag.indexOf('\r') >= 0 || etag.indexOf('\n') >= 0)) {
                throw new IllegalArgumentException("Pipeline etag is invalid");
            }
            rawBytes = rawBytes.clone();
        }

        @Override
        public byte[] rawBytes() {
            return rawBytes.clone();
        }
    }

    public record PipelineResult(DocumentWriteStatus status, DocumentRecord document) {
        public PipelineResult {
            if (status == null || document == null) {
                throw new IllegalArgumentException("Pipeline result is incomplete");
            }
        }
    }

    public enum FailureStage {
        LOOKUP("lookup"),
        PARSE("parse"),
        EMBEDDING("embedding"),
        STORAGE("storage"),
        INDEX("index");

        private final String label;

        FailureStage(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static final class PipelineException extends RuntimeException {
        private final FailureStage stage;

        private PipelineException(FailureStage stage, Throwable cause) {
            super(cause);
            this.stage = stage;
        }

        public FailureStage stage() {
            return stage;
        }
    }

    private record ExistingIdentity(
            DocumentRecord existing,
            UUID locator,
            boolean unchanged,
            boolean replacement
    ) {
    }
}
