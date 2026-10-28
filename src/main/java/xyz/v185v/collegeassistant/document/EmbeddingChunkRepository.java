package xyz.v185v.collegeassistant.document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import xyz.v185v.collegeassistant.document.persistence.EmbeddingChunkDO;
import xyz.v185v.collegeassistant.document.persistence.EmbeddingChunkMapper;

/** 不可变向量分块与过滤向量搜索的持久化边界。 */
@Repository
public class EmbeddingChunkRepository {

    private static final String DOCUMENT_REF_TYPE = "DOCUMENT";
    private static final int INSERT_BATCH_SIZE = 100;
    private static final TypeReference<Map<String, Object>> META_TYPE = new TypeReference<>() {
    };

    private final EmbeddingChunkMapper mapper;
    private final ObjectMapper objectMapper;

    public EmbeddingChunkRepository(EmbeddingChunkMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

/** 原子替换一个官方文档和向量空间的全部分块。 */
    @Transactional
    public List<EmbeddingChunkRecord> replaceForDocument(
            Long documentId,
            long embeddingConfigId,
            List<EmbeddingChunkRecord> chunks
    ) {
        return replaceInternal(DOCUMENT_REF_TYPE, documentId, embeddingConfigId, chunks);
    }

/** 原子替换一个领域引用和向量空间的全部分块。 */
    @Transactional
    public List<EmbeddingChunkRecord> replace(
            String docRefType,
            Long docRefId,
            long embeddingConfigId,
            List<EmbeddingChunkRecord> chunks
    ) {
        return replaceInternal(docRefType, docRefId, embeddingConfigId, chunks);
    }

/** 删除一个不可变领域引用的所有向量版本。 */
    public int deleteAllForReference(String docRefType, Long docRefId) {
        requireReference(docRefType, docRefId);
        return mapper.delete(Wrappers.<EmbeddingChunkDO>lambdaQuery()
                .eq(EmbeddingChunkDO::getDocRefType, docRefType)
                .eq(EmbeddingChunkDO::getDocRefId, docRefId));
    }

/** 列出一个不可变向量空间中已物化的不同引用。 */
    public List<EmbeddingChunkTarget> findTargetsByConfiguration(long embeddingConfigId) {
        requireEmbeddingConfigId(embeddingConfigId);
        return mapper.selectList(Wrappers.<EmbeddingChunkDO>lambdaQuery()
                        .select(EmbeddingChunkDO::getDocRefType, EmbeddingChunkDO::getDocRefId)
                        .eq(EmbeddingChunkDO::getEmbeddingConfigId, embeddingConfigId)
                        .groupBy(EmbeddingChunkDO::getDocRefType, EmbeddingChunkDO::getDocRefId)
                        .orderByAsc(EmbeddingChunkDO::getDocRefType, EmbeddingChunkDO::getDocRefId)).stream()
                .map(row -> new EmbeddingChunkTarget(row.getDocRefType(), row.getDocRefId()))
                .toList();
    }

/** 按确定性分块顺序读取候选替换项以校验激活条件。 */
    public List<EmbeddingChunkRecord> findForReference(
            String docRefType,
            Long docRefId,
            long embeddingConfigId
    ) {
        requireReference(docRefType, docRefId);
        requireEmbeddingConfigId(embeddingConfigId);
        return mapper.findForReference(embeddingConfigId, docRefType, docRefId).stream()
                .map(this::toRecord)
                .toList();
    }

/** 使用数据源和受众硬过滤条件执行向量相似度搜索。 */
    @Transactional(readOnly = true)
    public List<EmbeddingChunkSearchResult> search(EmbeddingChunkSearchCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria");
        mapper.setStrictHnswOrder();
        return mapper.search(
                        criteria.embeddingConfigId(),
                        EmbeddingVectorSupport.serialize(criteria.queryEmbedding()),
                        criteria.topK(),
                        criteria.sourceType(),
                        criteria.degree(),
                        criteria.college(),
                        criteria.campus(),
                        criteria.gradeRange()).stream()
                .map(this::toSearchResult)
                .toList();
    }

    private List<EmbeddingChunkRecord> replaceInternal(
            String docRefType,
            Long docRefId,
            long embeddingConfigId,
            List<EmbeddingChunkRecord> chunks
    ) {
        requireReference(docRefType, docRefId);
        requireEmbeddingConfigId(embeddingConfigId);
        List<EmbeddingChunkRecord> validatedChunks = validateChunks(
                docRefType, docRefId, embeddingConfigId, chunks);

        mapper.lockReplacement(
                embeddingConfigId, docRefType.length(), docRefType, docRefId);
        mapper.delete(Wrappers.<EmbeddingChunkDO>lambdaQuery()
                .eq(EmbeddingChunkDO::getEmbeddingConfigId, embeddingConfigId)
                .eq(EmbeddingChunkDO::getDocRefType, docRefType)
                .eq(EmbeddingChunkDO::getDocRefId, docRefId));

        Map<Integer, EmbeddingChunkRecord> insertedByIndex = new HashMap<>();
        for (int fromIndex = 0; fromIndex < validatedChunks.size();
                fromIndex += INSERT_BATCH_SIZE) {
            int toIndex = Math.min(fromIndex + INSERT_BATCH_SIZE, validatedChunks.size());
            List<EmbeddingChunkDO> batch = validatedChunks.subList(fromIndex, toIndex).stream()
                    .map(this::toDataObject)
                    .toList();
            List<EmbeddingChunkDO> insertedRows = mapper.insertBatchReturning(batch);
            if (insertedRows.size() != batch.size()) {
                throw new IllegalStateException("Database returned an incomplete chunk batch");
            }
            for (EmbeddingChunkDO row : insertedRows) {
                EmbeddingChunkRecord inserted = toRecord(row);
                if (insertedByIndex.put(inserted.chunkIndex(), inserted) != null) {
                    throw new IllegalStateException("Database returned duplicate chunk indexes");
                }
            }
        }

        List<EmbeddingChunkRecord> ordered = new ArrayList<>(validatedChunks.size());
        for (EmbeddingChunkRecord chunk : validatedChunks) {
            EmbeddingChunkRecord inserted = insertedByIndex.get(chunk.chunkIndex());
            if (inserted == null) {
                throw new IllegalStateException("Database did not return every inserted chunk");
            }
            ordered.add(inserted);
        }
        return List.copyOf(ordered);
    }

    static String replacementLockIdentity(
            String docRefType,
            Long docRefId,
            long embeddingConfigId
    ) {
        return "embedding_chunk:" + embeddingConfigId + ':'
                + docRefType.length() + ':' + docRefType + ':' + docRefId;
    }

    private List<EmbeddingChunkRecord> validateChunks(
            String docRefType,
            Long docRefId,
            long embeddingConfigId,
            List<EmbeddingChunkRecord> chunks
    ) {
        if (chunks == null) {
            throw new IllegalArgumentException("Chunks must not be null");
        }
        List<EmbeddingChunkRecord> copy = new ArrayList<>(chunks.size());
        Set<Integer> chunkIndexes = new HashSet<>();
        for (EmbeddingChunkRecord chunk : chunks) {
            if (chunk == null) {
                throw new IllegalArgumentException("Chunks must not contain null elements");
            }
            if (!docRefType.equals(chunk.docRefType()) || !docRefId.equals(chunk.docRefId())) {
                throw new IllegalArgumentException("Chunk reference must match replace target");
            }
            if (chunk.embeddingConfigId() != embeddingConfigId) {
                throw new IllegalArgumentException(
                        "Chunk embedding configuration must match replace target");
            }
            if (!chunkIndexes.add(chunk.chunkIndex())) {
                throw new IllegalArgumentException("Chunk indexes must be unique per replacement");
            }
            copy.add(chunk);
        }
        return List.copyOf(copy);
    }

    private EmbeddingChunkDO toDataObject(EmbeddingChunkRecord chunk) {
        EmbeddingChunkDO row = new EmbeddingChunkDO();
        row.setId(chunk.id());
        row.setEmbeddingConfigId(chunk.embeddingConfigId());
        row.setDocRefType(chunk.docRefType());
        row.setDocRefId(chunk.docRefId());
        row.setChunkIndex(chunk.chunkIndex());
        row.setChunkText(chunk.chunkText());
        row.setEmbeddingText(EmbeddingVectorSupport.serialize(chunk.embedding()));
        row.setSourceType(chunk.sourceType());
        row.setSourceUrl(chunk.sourceUrl());
        row.setTitle(chunk.title());
        row.setSourceOrg(chunk.sourceOrg());
        row.setPublishedAt(chunk.publishedAt());
        row.setDegree(chunk.degree());
        row.setCollege(chunk.college());
        row.setCampus(chunk.campus());
        row.setGradeRange(chunk.gradeRange());
        row.setMetaJson(serializeMeta(chunk.meta()));
        row.setCreatedAt(chunk.createdAt());
        return row;
    }

    private EmbeddingChunkRecord toRecord(EmbeddingChunkDO row) {
        return new EmbeddingChunkRecord(
                row.getId(),
                row.getEmbeddingConfigId(),
                row.getDocRefType(),
                row.getDocRefId(),
                row.getChunkIndex(),
                row.getChunkText(),
                EmbeddingVectorSupport.parse(row.getEmbeddingText()),
                row.getSourceType(),
                row.getSourceUrl(),
                row.getTitle(),
                row.getSourceOrg(),
                row.getPublishedAt(),
                row.getDegree(),
                row.getCollege(),
                row.getCampus(),
                row.getGradeRange(),
                deserializeMeta(row.getMetaJson()),
                row.getCreatedAt());
    }

    private EmbeddingChunkSearchResult toSearchResult(EmbeddingChunkDO row) {
        String documentReferenceType = row.getDocumentReferenceType();
        return new EmbeddingChunkSearchResult(
                toRecord(row),
                row.getScore(),
                documentReferenceType == null
                        ? null
                        : DocumentReferenceType.valueOf(documentReferenceType),
                row.getDocumentId(),
                row.getExternalSourceUrl());
    }

    private String serializeMeta(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize chunk metadata", ex);
        }
    }

    private Map<String, Object> deserializeMeta(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(json, META_TYPE);
            return JsonMetadataSupport.immutableCopy(metadata);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to deserialize chunk metadata", ex);
        }
    }

    private void requireReference(String docRefType, Long docRefId) {
        if (docRefType == null || docRefType.isBlank()) {
            throw new IllegalArgumentException("docRefType must not be blank");
        }
        if (docRefId == null || docRefId <= 0) {
            throw new IllegalArgumentException("docRefId must be positive");
        }
    }

    private void requireEmbeddingConfigId(long embeddingConfigId) {
        if (embeddingConfigId <= 0) {
            throw new IllegalArgumentException("embeddingConfigId must be positive");
        }
    }
}
