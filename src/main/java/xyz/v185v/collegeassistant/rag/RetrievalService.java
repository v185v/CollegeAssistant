package xyz.v185v.collegeassistant.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import xyz.v185v.collegeassistant.ai.EffectiveEmbeddingContext;
import xyz.v185v.collegeassistant.ai.EmbeddingGateway;
import xyz.v185v.collegeassistant.ai.ModelRegistry;
import xyz.v185v.collegeassistant.chat.CitationReferenceType;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.document.DocumentReferenceType;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRecord;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;
import xyz.v185v.collegeassistant.document.EmbeddingChunkSearchCriteria;
import xyz.v185v.collegeassistant.document.EmbeddingChunkSearchResult;
import xyz.v185v.collegeassistant.profile.UserProfileDto;

/**
 * RAG 双池检索服务。
 *
 * <p>先生成问题向量，再分别检索官方与 UGC 池；画像字段用于数据库硬过滤，
 * 意图权重和 UGC 点赞/举报信号用于软调权，最后合并排序并截取 topK。</p>
 */
@Service
public final class RetrievalService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RetrievalService.class);
    private static final String OFFICIAL = "OFFICIAL";
    private static final String UGC = "UGC";
    private static final int CANDIDATE_MULTIPLIER = 2;
    private static final int MAX_CANDIDATES_PER_POOL = 100;
    private static final double DEFAULT_UGC_TRUST = 0.95;
    private static final double MAX_LIKE_BOOST = 0.05;
    private static final double MAX_REPORT_PENALTY = 0.10;
    private static final double MIN_UGC_TRUST = 0.85;
    private static final Map<IntentType, PoolWeights> WEIGHTS = Map.of(
            IntentType.OFFICIAL, new PoolWeights(1.0, 0.45),
            IntentType.EXPERIENTIAL, new PoolWeights(0.45, 1.0),
            IntentType.MIXED, new PoolWeights(0.85, 0.85),
            IntentType.UNKNOWN, new PoolWeights(0.70, 0.70));
    private static final Comparator<RetrievedChunk> RANKING =
            Comparator.comparingDouble(RetrievedChunk::score)
                    .reversed()
                    .thenComparingInt(chunk -> OFFICIAL.equals(chunk.sourceType()) ? 0 : 1)
                    .thenComparing(RetrievedChunk::chunkId)
                    .thenComparing(RetrievedChunk::chunkText);

    private final Supplier<EffectiveEmbeddingContext> embeddingContextSupplier;
    private final EmbeddingChunkRepository chunkRepository;
    private final double minSimilarity;

    @Autowired
    public RetrievalService(
            ModelRegistry modelRegistry,
            EmbeddingChunkRepository chunkRepository,
            RagProperties ragProperties
    ) {
        this(
                Objects.requireNonNull(modelRegistry, "modelRegistry")::requireEmbedding,
                chunkRepository,
                ragProperties);
    }

/** 为使用固定向量空间的针对性单元测试保留的兼容构造函数。 */
    public RetrievalService(
            EmbeddingGateway embeddingGateway,
            EmbeddingChunkRepository chunkRepository,
            RagProperties ragProperties
    ) {
        this(
                () -> new EffectiveEmbeddingContext(
                        1L, Objects.requireNonNull(embeddingGateway, "embeddingGateway")),
                chunkRepository,
                ragProperties);
    }

    private RetrievalService(
            Supplier<EffectiveEmbeddingContext> embeddingContextSupplier,
            EmbeddingChunkRepository chunkRepository,
            RagProperties ragProperties
    ) {
        this.embeddingContextSupplier = Objects.requireNonNull(
                embeddingContextSupplier, "embeddingContextSupplier");
        this.chunkRepository = Objects.requireNonNull(chunkRepository, "chunkRepository");
        this.minSimilarity = Objects.requireNonNull(ragProperties, "ragProperties").minSimilarity();
    }

    /**
     * 执行一次带画像过滤和意图调权的知识检索。
     *
     * @param request 检索请求
     * @return 相关性排序后的不可变分块列表
     */
    public List<RetrievedChunk> retrieve(RetrievalRequest request) {
        Objects.requireNonNull(request, "request");
        long startedNanos = System.nanoTime();
        LOGGER.info(
                "event=rag.retrieval.start intent={} topK={}",
                request.intent().type(),
                request.topK());
        EffectiveEmbeddingContext embeddingContext = embeddingContextSupplier.get();
        List<Double> queryEmbedding = embed(
                request.question(), embeddingContext.gateway(), startedNanos);
        PoolWeights weights = WEIGHTS.get(request.intent().type());
        int candidateTopK = Math.min(
                request.topK() * CANDIDATE_MULTIPLIER,
                MAX_CANDIDATES_PER_POOL);
        UserProfileDto profile = request.profile();
        String degree = profile == null || profile.degree() == null
                ? null : profile.degree().name();
        String college = profile == null ? null : normalizeOptional(profile.college());
        String campus = profile == null ? null : normalizeOptional(profile.campus());
        String gradeRange = profile == null || profile.grade() == null
                ? null : profile.grade().toString();

        try {
            List<EmbeddingChunkSearchResult> official = chunkRepository.search(criteria(
                    embeddingContext.configurationId(), queryEmbedding, candidateTopK, OFFICIAL,
                    degree, college, campus, gradeRange));
            List<EmbeddingChunkSearchResult> ugc = chunkRepository.search(criteria(
                    embeddingContext.configurationId(), queryEmbedding, candidateTopK, UGC,
                    degree, college, campus, gradeRange));
            List<RetrievedChunk> result = rankAndLimit(
                    official, ugc, weights, request.topK());
            LOGGER.info(
                    "event=rag.retrieval.success officialCandidates={} "
                            + "ugcCandidates={} resultCount={} elapsedMs={}",
                    official.size(),
                    ugc.size(),
                    result.size(),
                    elapsedMs(startedNanos));
            return result;
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "event=rag.retrieval.failure stage=search "
                            + "causeType={} elapsedMs={}",
                    exception.getClass().getName(),
                    elapsedMs(startedNanos));
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "Knowledge retrieval failed",
                    exception);
        }
    }

    private List<Double> embed(
            String question,
            EmbeddingGateway embeddingGateway,
            long startedNanos
    ) {
        try {
            return embeddingGateway.embed(question);
        } catch (BusinessException exception) {
            if (exception.getCode() == ErrorCode.MODEL_CONFIG_UNAVAILABLE) {
                throw exception;
            }
            throw new BusinessException(
                    ErrorCode.EMBEDDING_FAILED,
                    "Retrieval embedding failed",
                    exception);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "event=rag.retrieval.failure stage=embedding "
                            + "causeType={} elapsedMs={}",
                    exception.getClass().getName(),
                    elapsedMs(startedNanos));
            throw new BusinessException(
                    ErrorCode.EMBEDDING_FAILED,
                    "Retrieval embedding failed",
                    exception);
        }
    }

    private long elapsedMs(long startedNanos) {
        return Math.max(
                0L,
                TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - startedNanos));
    }

    private EmbeddingChunkSearchCriteria criteria(
            long embeddingConfigId,
            List<Double> queryEmbedding,
            int topK,
            String sourceType,
            String degree,
            String college,
            String campus,
            String gradeRange
    ) {
        return new EmbeddingChunkSearchCriteria(
                embeddingConfigId, queryEmbedding, topK, sourceType, degree, college, campus, gradeRange);
    }

    private List<RetrievedChunk> rankAndLimit(
            List<EmbeddingChunkSearchResult> official,
            List<EmbeddingChunkSearchResult> ugc,
            PoolWeights weights,
            int topK
    ) {
        List<RetrievedChunk> ranked = new ArrayList<>(official.size() + ugc.size());
        official.stream()
                .filter(this::meetsRawSimilarityThreshold)
                .forEach(result -> ranked.add(toRetrieved(result, weights.official(), false)));
        ugc.stream()
                .filter(this::meetsRawSimilarityThreshold)
                .forEach(result -> ranked.add(toRetrieved(result, weights.ugc(), true)));
        ranked.sort(RANKING);
        return List.copyOf(ranked.subList(0, Math.min(topK, ranked.size())));
    }

    private boolean meetsRawSimilarityThreshold(EmbeddingChunkSearchResult result) {
        double similarity = result.score();
        return Double.isFinite(similarity)
                && similarity > 0.0
                && similarity >= minSimilarity;
    }

    private RetrievedChunk toRetrieved(
            EmbeddingChunkSearchResult result,
            double poolWeight,
            boolean ugc
    ) {
        EmbeddingChunkRecord chunk = result.chunk();
        double trust = ugc ? ugcTrust(chunk.meta()) : 1.0;
        double finalScore = clampSimilarity(result.score()) * poolWeight * trust;
        CitationReferenceType referenceType = citationReferenceType(result, chunk);
        return new RetrievedChunk(
                chunk.id(),
                chunk.chunkText(),
                chunk.sourceType(),
                clamp(finalScore, 0.0, 1.0),
                referenceType == CitationReferenceType.INTERNAL ? null : chunk.sourceUrl(),
                chunk.title(),
                chunk.sourceOrg(),
                chunk.publishedAt(),
                chunk.meta(),
                referenceType,
                referenceType == CitationReferenceType.INTERNAL
                        ? result.documentId()
                        : null,
                referenceType == CitationReferenceType.INTERNAL
                        ? result.externalSourceUrl()
                        : null);
    }

    private CitationReferenceType citationReferenceType(
            EmbeddingChunkSearchResult result,
            EmbeddingChunkRecord chunk
    ) {
        if (UGC.equals(chunk.sourceType())) {
            return CitationReferenceType.UGC;
        }
        if (!OFFICIAL.equals(chunk.sourceType())) {
            throw new IllegalStateException("Retrieved chunk has an unsupported source type");
        }
        DocumentReferenceType documentReferenceType = result.documentReferenceType();
        if (documentReferenceType == null || result.documentId() == null) {
            throw new IllegalStateException(
                    "Official retrieved chunks require document citation metadata");
        }
        return documentReferenceType == DocumentReferenceType.INTERNAL
                ? CitationReferenceType.INTERNAL
                : CitationReferenceType.EXTERNAL;
    }

    private double ugcTrust(Map<String, Object> metadata) {
        double likes = nonNegativeFiniteNumber(metadata.get("likes"));
        double reports = nonNegativeFiniteNumber(metadata.get("reports"));
        double likeBoost = Math.min(MAX_LIKE_BOOST, likes * 0.0005);
        double reportPenalty = Math.min(MAX_REPORT_PENALTY, reports * 0.01);
        return clamp(
                DEFAULT_UGC_TRUST + likeBoost - reportPenalty,
                MIN_UGC_TRUST,
                1.0);
    }

    private double nonNegativeFiniteNumber(Object value) {
        if (!(value instanceof Number number)) {
            return 0.0;
        }
        double converted = number.doubleValue();
        return Double.isFinite(converted) && converted > 0.0 ? converted : 0.0;
    }

    private double clampSimilarity(double score) {
        return Double.isFinite(score) ? clamp(score, 0.0, 1.0) : 0.0;
    }

    private double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private record PoolWeights(double official, double ugc) {
    }
}
