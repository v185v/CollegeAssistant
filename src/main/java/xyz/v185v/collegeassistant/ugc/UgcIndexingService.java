package xyz.v185v.collegeassistant.ugc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import xyz.v185v.collegeassistant.ai.EffectiveEmbeddingContext;
import xyz.v185v.collegeassistant.ai.EmbeddingGateway;
import xyz.v185v.collegeassistant.ai.ModelRegistry;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRecord;
import xyz.v185v.collegeassistant.ingestion.parsing.TextChunk;
import xyz.v185v.collegeassistant.ingestion.parsing.TextChunker;

/**
 * UGC 向量索引服务，编排状态读取、正文分块、向量化、元数据组装和原子替换。
 */
@Service
public class UgcIndexingService {

    public static final String UGC_REFERENCE_TYPE = "UGC";
    public static final String UGC_SOURCE_TYPE = "UGC";
    private static final String GENERAL = "GENERAL";

    private final UgcRepository ugcRepository;
    private final TextChunker chunker;
    private final Supplier<EffectiveEmbeddingContext> embeddingContextSupplier;
    private final UgcChunkIndexWriter indexWriter;

    @Autowired
    public UgcIndexingService(
            UgcRepository ugcRepository,
            TextChunker chunker,
            ModelRegistry modelRegistry,
            UgcChunkIndexWriter indexWriter
    ) {
        this(
                ugcRepository,
                chunker,
                java.util.Objects.requireNonNull(modelRegistry, "modelRegistry")::requireEmbedding,
                indexWriter);
    }

/** 为使用固定向量空间的针对性单元测试保留的兼容构造函数。 */
    public UgcIndexingService(
            UgcRepository ugcRepository,
            TextChunker chunker,
            EmbeddingGateway embeddingGateway,
            UgcChunkIndexWriter indexWriter
    ) {
        this(
                ugcRepository,
                chunker,
                () -> new EffectiveEmbeddingContext(
                        1L, java.util.Objects.requireNonNull(embeddingGateway, "embeddingGateway")),
                indexWriter);
    }

    private UgcIndexingService(
            UgcRepository ugcRepository,
            TextChunker chunker,
            Supplier<EffectiveEmbeddingContext> embeddingContextSupplier,
            UgcChunkIndexWriter indexWriter
    ) {
        this.ugcRepository = ugcRepository;
        this.chunker = chunker;
        this.embeddingContextSupplier = java.util.Objects.requireNonNull(
                embeddingContextSupplier, "embeddingContextSupplier");
        this.indexWriter = indexWriter;
    }

    /**
     * 为审核通过的帖子重建索引；帖子不存在或已不再通过时安全跳过。
     *
     * @param postId 帖子编号
     */
    public void indexApproved(Long postId) {
        UgcPostDto post;
        try {
            post = ugcRepository.findById(postId).orElse(null);
        } catch (RuntimeException exception) {
            throw indexingFailure(postId, UgcIndexingStage.LOOKUP, exception);
        }
        if (post == null || post.status() != UgcStatus.APPROVED) {
            return;
        }

        EffectiveEmbeddingContext embeddingContext = embeddingContextSupplier.get();

        List<TextChunk> chunks;
        try {
            chunks = chunker.chunk(post.content());
        } catch (RuntimeException exception) {
            throw indexingFailure(postId, UgcIndexingStage.CHUNKING, exception);
        }

        List<List<Double>> vectors;
        try {
            vectors = embeddingContext.gateway().embedAll(
                    chunks.stream().map(TextChunk::text).toList());
            if (vectors == null || vectors.size() != chunks.size()) {
                throw new IllegalStateException("Embedding count does not match chunk count");
            }
        } catch (RuntimeException exception) {
            throw indexingFailure(postId, UgcIndexingStage.EMBEDDING, exception);
        }

        List<EmbeddingChunkRecord> records;
        try {
            records = prepareRecords(post, embeddingContext.configurationId(), chunks, vectors);
        } catch (RuntimeException exception) {
            throw indexingFailure(postId, UgcIndexingStage.PREPARATION, exception);
        }

        try {
            indexWriter.replaceIfStillApproved(
                    postId, embeddingContext.configurationId(), records);
        } catch (RuntimeException exception) {
            throw indexingFailure(postId, UgcIndexingStage.INDEX, exception);
        }
    }

    private List<EmbeddingChunkRecord> prepareRecords(
            UgcPostDto post,
            long embeddingConfigId,
            List<TextChunk> chunks,
            List<List<Double>> vectors
    ) {
        Map<String, Object> metadata = metadata(post);
        List<EmbeddingChunkRecord> records = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            TextChunk chunk = chunks.get(index);
            records.add(new EmbeddingChunkRecord(
                    null,
                    embeddingConfigId,
                    UGC_REFERENCE_TYPE,
                    post.id(),
                    chunk.index(),
                    chunk.text(),
                    vectors.get(index),
                    UGC_SOURCE_TYPE,
                    null,
                    post.title(),
                    post.authorName(),
                    post.createdAt(),
                    GENERAL,
                    GENERAL,
                    GENERAL,
                    GENERAL,
                    metadata,
                    null));
        }
        return List.copyOf(records);
    }

    private Map<String, Object> metadata(UgcPostDto post) {
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
        return metadata;
    }

    private UgcIndexingException indexingFailure(
            Long postId,
            UgcIndexingStage stage,
            RuntimeException cause
    ) {
        return new UgcIndexingException(postId, stage, cause);
    }
}
