package xyz.v185v.collegeassistant.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DocumentRepositoryValidationTest {

    private static final int EMBEDDING_DIMENSIONS = 1536;

    @Test
    void chunkEmbeddingMustHaveExactly1536Dimensions() {
        assertThatThrownBy(() -> chunk(vector(EMBEDDING_DIMENSIONS - 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1536");

        assertThatThrownBy(() -> chunk(vector(EMBEDDING_DIMENSIONS + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1536");
    }

    @Test
    void chunkEmbeddingRejectsNullAndNonFiniteElements() {
        List<Double> withNull = vector(EMBEDDING_DIMENSIONS);
        withNull.set(17, null);
        List<Double> withNaN = vector(EMBEDDING_DIMENSIONS);
        withNaN.set(18, Double.NaN);
        List<Double> withInfinity = vector(EMBEDDING_DIMENSIONS);
        withInfinity.set(19, Double.POSITIVE_INFINITY);

        assertThatThrownBy(() -> chunk(withNull))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunk(withNaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunk(withInfinity))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chunkEmbeddingRejectsAnAllZeroVector() {
        assertThatThrownBy(() -> chunk(vector(EMBEDDING_DIMENSIONS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero");
    }

    @Test
    void searchCriteriaValidatesQueryEmbeddingAndTopK() {
        assertThatThrownBy(() -> criteria(vector(EMBEDDING_DIMENSIONS - 1), 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1536");

        List<Double> withNull = vector(EMBEDDING_DIMENSIONS);
        withNull.set(20, null);
        assertThatThrownBy(() -> criteria(withNull, 5))
                .isInstanceOf(IllegalArgumentException.class);

        List<Double> withNegativeInfinity = vector(EMBEDDING_DIMENSIONS);
        withNegativeInfinity.set(21, Double.NEGATIVE_INFINITY);
        assertThatThrownBy(() -> criteria(withNegativeInfinity, 5))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> criteria(vector(EMBEDDING_DIMENSIONS), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");
        assertThatThrownBy(() -> criteria(vector(EMBEDDING_DIMENSIONS), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");
    }

    @Test
    void searchCriteriaRejectsAnAllZeroQueryEmbedding() {
        assertThatThrownBy(() -> criteria(vector(EMBEDDING_DIMENSIONS), 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero");
    }

    @Test
    void chunkAndCriteriaDefensivelyCopyMutableInputs() {
        List<Double> chunkEmbedding = vector(EMBEDDING_DIMENSIONS);
        chunkEmbedding.set(0, 1.0);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("scope", "original");
        EmbeddingChunkRecord chunk = chunk(chunkEmbedding, metadata);
        List<Double> queryEmbedding = vector(EMBEDDING_DIMENSIONS);
        queryEmbedding.set(0, 1.0);
        EmbeddingChunkSearchCriteria criteria = criteria(queryEmbedding, 5);

        chunkEmbedding.set(0, 2.0);
        metadata.put("scope", "mutated");
        queryEmbedding.set(0, 2.0);

        assertThat(chunk.embedding().getFirst()).isEqualTo(1.0);
        assertThat(chunk.meta()).containsEntry("scope", "original");
        assertThat(criteria.queryEmbedding().getFirst()).isEqualTo(1.0);
        assertThatThrownBy(() -> chunk.meta().put("extra", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void chunkMetadataNormalizesArraysSetsAndCollectionsToImmutableLists() {
        int[] years = {1, 2};
        LinkedHashSet<String> campuses = new LinkedHashSet<>(List.of("North", "South"));
        ArrayDeque<String> stages = new ArrayDeque<>(List.of("ENROLLED", "GRADUATING"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("years", years);
        metadata.put("campuses", campuses);
        metadata.put("stages", stages);

        EmbeddingChunkRecord chunk = chunk(unitVector(0), metadata);

        years[0] = 99;
        campuses.add("West");
        stages.add("ALUMNI");

        assertThat(chunk.meta().get("years")).isEqualTo(List.of(1, 2));
        assertThat(chunk.meta().get("campuses")).isEqualTo(List.of("North", "South"));
        assertThat(chunk.meta().get("stages")).isEqualTo(List.of("ENROLLED", "GRADUATING"));
        assertThatThrownBy(() -> ((List<?>) chunk.meta().get("campuses")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void chunkMetadataRejectsUnsupportedMutablePojoValues() {
        assertThatThrownBy(() -> chunk(
                unitVector(0),
                Map.of("mutable", new StringBuilder("value"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported metadata value type");
    }

    @Test
    void validChunkAndSearchCriteriaAreAccepted() {
        List<Double> queryEmbedding = vector(EMBEDDING_DIMENSIONS);
        queryEmbedding.set(0, 1.0);

        assertThatCode(() -> chunk(unitVector(0)))
                .doesNotThrowAnyException();
        assertThatCode(() -> criteria(queryEmbedding, 5))
                .doesNotThrowAnyException();
    }

    private EmbeddingChunkRecord chunk(List<Double> embedding) {
        return chunk(embedding, Map.of("nested", Map.of("value", 1)));
    }

    private EmbeddingChunkRecord chunk(List<Double> embedding, Map<String, Object> meta) {
        return new EmbeddingChunkRecord(
                null,
                1L,
                "DOCUMENT",
                1L,
                0,
                "Test chunk",
                embedding,
                "OFFICIAL",
                "https://example.edu/doc",
                "Test document",
                "Example University",
                null,
                "GENERAL",
                "GENERAL",
                "GENERAL",
                "GENERAL",
                meta,
                null);
    }

    private EmbeddingChunkSearchCriteria criteria(List<Double> queryEmbedding, int topK) {
        return new EmbeddingChunkSearchCriteria(
                1L,
                queryEmbedding,
                topK,
                "OFFICIAL",
                null,
                null,
                null,
                null);
    }

    private List<Double> vector(int dimensions) {
        return new ArrayList<>(Collections.nCopies(dimensions, 0.0));
    }

    private List<Double> unitVector(int index) {
        List<Double> vector = vector(EMBEDDING_DIMENSIONS);
        vector.set(index, 1.0);
        return vector;
    }
}
