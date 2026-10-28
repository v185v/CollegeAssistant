package xyz.v185v.collegeassistant.ugc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class UgcRepositoryCodecTest {

    private final UgcRepository ugcRepository = new UgcRepository(null, JsonMapper.builder().build());

    @Test
    void calculateOffsetUsesLongArithmeticForLargePageNumbers() {
        assertThat(UgcRepository.calculateOffset(Integer.MAX_VALUE, 100))
                .isEqualTo(214_748_364_600L);
    }

    @Test
    void deserializeReturnsEmptyListForNullBlankAndEmptyArray() {
        assertThat(ugcRepository.deserializeTags(null)).isEmpty();
        assertThat(ugcRepository.deserializeTags(" ")).isEmpty();
        assertThat(ugcRepository.deserializeTags("[]")).isEmpty();
    }

    @Test
    void deserializeReturnsImmutableTags() {
        assertThat(ugcRepository.deserializeTags("[\"course\",\"campus\"]"))
                .containsExactly("course", "campus");
        assertThatThrownBy(() -> ugcRepository.deserializeTags("[\"course\"]").add("other"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void deserializeRejectsMalformedJson() {
        assertThatThrownBy(() -> ugcRepository.deserializeTags("[not-json]"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to deserialize UGC tags");
    }

    @Test
    void deserializeRejectsNullElements() {
        assertThatThrownBy(() -> ugcRepository.deserializeTags("[\"course\",null]"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("UGC tags must not contain null elements");
    }
}
