package xyz.v185v.collegeassistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class ChatRepositoryValidationTest {

    private ChatRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ChatRepository(null, JsonMapper.builder().findAndAddModules().build());
    }

    @Test
    void normalMessageWriteRejectsUnsupportedRoleAndBlankContentBeforeJdbc() {
        assertThatThrownBy(() -> repository.appendOwnedMessage(
                7L, 31L, "SYSTEM", "hidden instruction", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");
        assertThatThrownBy(() -> repository.appendOwnedMessage(
                7L, 31L, "USER", "  ", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
        assertThatThrownBy(() -> repository.finalizeAssistantPlaceholder(
                7L, 31L, 8L, "", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
    }

    @Test
    void previewTruncationCountsUnicodeCodePointsAndPreservesNull() {
        String preview = ChatRepository.truncateCodePoints(
                "😀".repeat(121), ChatConstraints.MAX_PREVIEW_CODE_POINTS);

        assertThat(preview).isEqualTo("😀".repeat(120));
        assertThat(preview.codePointCount(0, preview.length())).isEqualTo(120);
        assertThat(Character.isHighSurrogate(preview.charAt(preview.length() - 1))).isFalse();
        assertThat(ChatRepository.truncateCodePoints(null, 120)).isNull();
    }

    @Test
    void previewTruncationRejectsNegativeLimit() {
        assertThatThrownBy(() -> ChatRepository.truncateCodePoints("preview", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Maximum code points must not be negative");
    }

    @Test
    void paginationIsOneBasedBoundedAndUsesLongOffset() {
        assertThat(ChatRepository.calculateOffset(1, 100)).isZero();
        assertThat(ChatRepository.calculateOffset(Integer.MAX_VALUE, 100))
                .isEqualTo(214_748_364_600L);
        assertThatThrownBy(() -> ChatRepository.calculateOffset(0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChatRepository.calculateOffset(1, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
