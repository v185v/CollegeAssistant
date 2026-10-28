package xyz.v185v.collegeassistant.ingestion.application;

import xyz.v185v.collegeassistant.ingestion.crawl.*;

import xyz.v185v.collegeassistant.ingestion.parsing.PreparedEmbeddingChunk;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import xyz.v185v.collegeassistant.ai.EffectiveEmbeddingContext;
import xyz.v185v.collegeassistant.admin.model.EmbeddingMaintenanceGuard;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.document.DocumentRecord;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.DocumentWriteResult;
import xyz.v185v.collegeassistant.document.DocumentWriteStatus;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;

/**
 * 在单一事务中协调文档增量写入和对应向量分块替换。
 */
@Component
public class DocumentIndexWriter {

    private final DocumentRepository documentRepository;
    private final EmbeddingChunkRepository chunkRepository;
    private final EmbeddingMaintenanceGuard maintenanceGuard;

    @Autowired
    public DocumentIndexWriter(
            DocumentRepository documentRepository,
            EmbeddingChunkRepository chunkRepository,
            EmbeddingMaintenanceGuard maintenanceGuard
    ) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.maintenanceGuard = maintenanceGuard;
    }

/** 为针对性写入器测试保留的兼容构造函数。 */
    public DocumentIndexWriter(
            DocumentRepository documentRepository,
            EmbeddingChunkRepository chunkRepository
    ) {
        this(documentRepository, chunkRepository, null);
    }

    /**
     * 写入文档，并在文档新增或更新时替换其全部分块。
     *
     * @param candidate 待写入文档
     * @param force     是否强制更新
     * @param chunks    已生成向量的分块
     * @return 文档写入结果
     */
    @Transactional
    public DocumentWriteResult write(
            DocumentRecord candidate,
            boolean force,
            List<PreparedEmbeddingChunk> chunks,
            EffectiveEmbeddingContext embeddingContext
    ) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(embeddingContext, "embeddingContext");
        if (maintenanceGuard != null) {
            maintenanceGuard.requireWritesAllowed();
        }
        if (candidate.id() != null) {
            DocumentRecord locked = documentRepository.lockActiveForReindex(candidate.id())
                    .filter(document -> document.sourceId().equals(candidate.sourceId()))
                    .filter(document -> Objects.equals(
                            document.internalLocator(), candidate.internalLocator()))
                    .filter(document -> document.sourceUrl().equals(candidate.sourceUrl()))
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.CONFLICT,
                            "Document or source entered deletion before publication"));
            if (!locked.referenceType().equals(candidate.referenceType())) {
                throw new BusinessException(
                        ErrorCode.CONFLICT, "Document identity changed before publication");
            }
        }
        DocumentWriteResult result = documentRepository.write(candidate, force);
        if (result.status() == DocumentWriteStatus.ADDED
                || result.status() == DocumentWriteStatus.UPDATED) {
            chunkRepository.replaceForDocument(
                    result.document().id(),
                    embeddingContext.configurationId(),
                    chunks.stream()
                            .map(chunk -> chunk.toRecord(
                                    result.document().id(), embeddingContext.configurationId()))
                            .toList());
        }
        return result;
    }
}
