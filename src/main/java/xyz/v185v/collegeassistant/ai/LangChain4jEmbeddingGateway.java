package xyz.v185v.collegeassistant.ai;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

/** 在应用边界校验并转换 LangChain4j 的向量响应。 */
public final class LangChain4jEmbeddingGateway implements EmbeddingGateway {

    private static final String FAILURE_MESSAGE = "Embedding generation failed";

    private final EmbeddingModel model;

    public LangChain4jEmbeddingGateway(EmbeddingModel model) {
        if (model == null) {
            throw failure();
        }
        this.model = model;
    }

    /** 为单段文本生成经过维度与数值校验的向量。 */
    @Override
    public List<Double> embed(String text) {
        requireText(text);
        return embedAll(List.of(text)).getFirst();
    }

    /** 批量生成向量，并验证模型响应数量、维度和有限数值。 */
    @Override
    public List<List<Double>> embedAll(List<String> texts) {
        List<TextSegment> segments = validatedSegments(texts);
        Response<List<Embedding>> response;
        try {
            response = model.embedAll(segments);
        } catch (RuntimeException exception) {
            throw failure(exception);
        }

        try {
            return validatedVectors(response, segments.size());
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(exception);
        }
    }

/** 继承父类型的文档说明。 */
    @Override
    public int dimension() {
        return EmbeddingDimensions.DIMENSIONS;
    }

    private List<TextSegment> validatedSegments(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw failure();
        }
        List<TextSegment> segments = new ArrayList<>(texts.size());
        for (String text : texts) {
            requireText(text);
            segments.add(TextSegment.from(text));
        }
        return List.copyOf(segments);
    }

    private void requireText(String text) {
        if (text == null || text.isBlank()) {
            throw failure();
        }
    }

    private List<List<Double>> validatedVectors(
            Response<List<Embedding>> response,
            int expectedCount
    ) {
        if (response == null) {
            throw failure();
        }
        List<Embedding> embeddings = response.content();
        if (embeddings == null || embeddings.size() != expectedCount) {
            throw failure();
        }

        List<List<Double>> vectors = new ArrayList<>(embeddings.size());
        for (Embedding embedding : embeddings) {
            vectors.add(validatedVector(embedding));
        }
        return List.copyOf(vectors);
    }

    private List<Double> validatedVector(Embedding embedding) {
        if (embedding == null) {
            throw failure();
        }
        float[] providerVector = embedding.vector();
        if (providerVector == null || providerVector.length != EmbeddingDimensions.DIMENSIONS) {
            throw failure();
        }

        boolean hasNonZeroValue = false;
        List<Double> copy = new ArrayList<>(providerVector.length);
        for (float value : providerVector) {
            if (!Float.isFinite(value)) {
                throw failure();
            }
            if (value != 0.0f) {
                hasNonZeroValue = true;
            }
            copy.add((double) value);
        }
        if (!hasNonZeroValue) {
            throw failure();
        }
        return List.copyOf(copy);
    }

    private BusinessException failure() {
        return new BusinessException(ErrorCode.EMBEDDING_FAILED, FAILURE_MESSAGE);
    }

    private BusinessException failure(Throwable cause) {
        return new BusinessException(ErrorCode.EMBEDDING_FAILED, FAILURE_MESSAGE, cause);
    }
}
