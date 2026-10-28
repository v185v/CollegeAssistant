package xyz.v185v.collegeassistant.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

class LangChain4jEmbeddingGatewayTest {

    private static final int EMBEDDING_DIMENSION = 1536;

    @Test
    void embedsOneTextAndReturnsAnImmutableVector() {
        FakeEmbeddingModel model = modelReturning(validEmbedding(1.0f));
        LangChain4jEmbeddingGateway gateway = new LangChain4jEmbeddingGateway(model);

        List<Double> result = gateway.embed("校历查询");

        assertThat(model.requestedTexts()).containsExactly("校历查询");
        assertThat(result).hasSize(EMBEDDING_DIMENSION);
        assertThat(result.getFirst()).isEqualTo(1.0d);
        assertThat(result.getLast()).isEqualTo(-1.0d);
        assertThatThrownBy(() -> result.set(0, 9.0d))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void reportsTheShared1536DimensionContract() {
        LangChain4jEmbeddingGateway gateway = new LangChain4jEmbeddingGateway(
                modelReturning(validEmbedding(1.0f)));

        assertThat(gateway.dimension()).isEqualTo(EMBEDDING_DIMENSION);
    }

    @Test
    void embedsABatchInInputOrderAndReturnsDeeplyImmutableLists() {
        FakeEmbeddingModel model = modelReturning(
                validEmbedding(1.0f),
                validEmbedding(2.0f),
                validEmbedding(3.0f));
        LangChain4jEmbeddingGateway gateway = new LangChain4jEmbeddingGateway(model);

        List<List<Double>> result = gateway.embedAll(List.of("first", "second", "third"));

        assertThat(model.requestedTexts()).containsExactly("first", "second", "third");
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getFirst()).isEqualTo(1.0d);
        assertThat(result.get(1).getFirst()).isEqualTo(2.0d);
        assertThat(result.get(2).getFirst()).isEqualTo(3.0d);
        assertThatThrownBy(() -> result.add(List.of(4.0d)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.getFirst().set(0, 4.0d))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullBlankOrEmptyInputBeforeCallingTheModel() {
        FakeEmbeddingModel model = modelReturning(validEmbedding(1.0f));
        LangChain4jEmbeddingGateway gateway = new LangChain4jEmbeddingGateway(model);

        assertEmbeddingFailure(() -> gateway.embed(null));
        assertEmbeddingFailure(() -> gateway.embed(" \t\r\n"));
        assertEmbeddingFailure(() -> gateway.embedAll(null));
        assertEmbeddingFailure(() -> gateway.embedAll(List.of()));
        assertEmbeddingFailure(() -> gateway.embedAll(Arrays.asList("valid", null)));
        assertEmbeddingFailure(() -> gateway.embedAll(List.of("valid", "  ")));

        assertThat(model.invocationCount()).isZero();
    }

    @Test
    void wrapsModelExceptionsAsEmbeddingFailures() {
        FakeEmbeddingModel model = new FakeEmbeddingModel(segments -> {
            throw new IllegalStateException("simulated provider failure");
        });
        LangChain4jEmbeddingGateway gateway = new LangChain4jEmbeddingGateway(model);

        assertEmbeddingFailure(() -> gateway.embed("valid text"));
    }

    @Test
    void rejectsANullModelResponse() {
        FakeEmbeddingModel model = new FakeEmbeddingModel(segments -> null);
        LangChain4jEmbeddingGateway gateway = new LangChain4jEmbeddingGateway(model);

        assertEmbeddingFailure(() -> gateway.embed("valid text"));
    }

    @Test
    void rejectsAResponseWithNullContent() {
        Response<List<Embedding>> nullContent = new Response<>(List.of(validEmbedding(1.0f))) {
            @Override
            public List<Embedding> content() {
                return null;
            }
        };
        FakeEmbeddingModel model = new FakeEmbeddingModel(segments -> nullContent);

        assertEmbeddingFailure(() ->
                new LangChain4jEmbeddingGateway(model).embed("valid text"));
    }

    @Test
    void rejectsNullEmbeddingsAndResponseCountMismatches() {
        FakeEmbeddingModel nullEmbeddingModel = new FakeEmbeddingModel(segments ->
                Response.from(Collections.<Embedding>singletonList(null)));
        assertEmbeddingFailure(() ->
                new LangChain4jEmbeddingGateway(nullEmbeddingModel).embed("valid text"));

        FakeEmbeddingModel tooFewEmbeddings = modelReturning(validEmbedding(1.0f));
        assertEmbeddingFailure(() -> new LangChain4jEmbeddingGateway(tooFewEmbeddings)
                .embedAll(List.of("first", "second")));

        FakeEmbeddingModel tooManyEmbeddings = modelReturning(
                validEmbedding(1.0f),
                validEmbedding(2.0f));
        assertEmbeddingFailure(() -> new LangChain4jEmbeddingGateway(tooManyEmbeddings)
                .embedAll(List.of("only")));
    }

    @Test
    void rejectsVectorsWithTheWrongDimension() {
        for (int dimension : List.of(0, EMBEDDING_DIMENSION - 1, EMBEDDING_DIMENSION + 1)) {
            Embedding invalid = Embedding.from(nonZeroVector(dimension));
            assertEmbeddingFailure(() ->
                    new LangChain4jEmbeddingGateway(modelReturning(invalid)).embed("valid text"));
        }
    }

    @Test
    void rejectsNanAndInfiniteVectorValues() {
        for (float invalidValue : List.of(
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY)) {
            float[] vector = validVector(1.0f);
            vector[100] = invalidValue;

            assertEmbeddingFailure(() -> new LangChain4jEmbeddingGateway(
                    modelReturning(Embedding.from(vector))).embed("valid text"));
        }
    }

    @Test
    void rejectsAllZeroVectors() {
        Embedding allZero = Embedding.from(new float[EMBEDDING_DIMENSION]);

        assertEmbeddingFailure(() ->
                new LangChain4jEmbeddingGateway(modelReturning(allZero)).embed("valid text"));
    }

    @Test
    void returnedVectorsAreDetachedFromProviderOwnedFloatArrays() {
        float[] providerVector = validVector(1.0f);
        LangChain4jEmbeddingGateway gateway = new LangChain4jEmbeddingGateway(
                modelReturning(Embedding.from(providerVector)));

        List<Double> result = gateway.embed("valid text");
        providerVector[0] = 99.0f;

        assertThat(result.getFirst()).isEqualTo(1.0d);
    }

    @Test
    void failuresDoNotLeakInputText() {
        String sensitiveInput = "student-secret-id-2026";
        FakeEmbeddingModel model = new FakeEmbeddingModel(segments -> {
            throw new IllegalStateException("provider rejected " + segments.getFirst().text());
        });

        assertThatThrownBy(() ->
                new LangChain4jEmbeddingGateway(model).embed(sensitiveInput))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.EMBEDDING_FAILED);
                    assertThat(exception.getCause()).isInstanceOf(IllegalStateException.class);
                    assertThat(exception.getMessage()).doesNotContain(sensitiveInput);
                });
    }

    private void assertEmbeddingFailure(ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.EMBEDDING_FAILED));
    }

    private FakeEmbeddingModel modelReturning(Embedding... embeddings) {
        return new FakeEmbeddingModel(segments -> Response.from(List.of(embeddings)));
    }

    private Embedding validEmbedding(float marker) {
        return Embedding.from(validVector(marker));
    }

    private float[] validVector(float marker) {
        float[] vector = new float[EMBEDDING_DIMENSION];
        vector[0] = marker;
        vector[EMBEDDING_DIMENSION - 1] = -marker;
        return vector;
    }

    private float[] nonZeroVector(int dimension) {
        float[] vector = new float[dimension];
        if (dimension > 0) {
            vector[0] = 1.0f;
        }
        return vector;
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {

        private final Function<List<TextSegment>, Response<List<Embedding>>> behavior;
        private List<TextSegment> lastRequest = List.of();
        private int invocationCount;

        private FakeEmbeddingModel(
                Function<List<TextSegment>, Response<List<Embedding>>> behavior
        ) {
            this.behavior = behavior;
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            invocationCount++;
            lastRequest = List.copyOf(segments);
            return behavior.apply(segments);
        }

        @Override
        public int dimension() {
            return EMBEDDING_DIMENSION;
        }

        private List<String> requestedTexts() {
            return lastRequest.stream().map(TextSegment::text).toList();
        }

        private int invocationCount() {
            return invocationCount;
        }
    }
}
