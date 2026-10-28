package xyz.v185v.collegeassistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;

class ChatStreamDtoTest {

    @Test
    void requestAppliesDefaultsAndNormalizesQuestion() {
        ChatStreamRequest request = new ChatStreamRequest(
                null, "  How is GPA calculated?  ", null, null).validated();

        assertThat(request.sessionId()).isNull();
        assertThat(request.question()).isEqualTo("How is GPA calculated?");
        assertThat(request.useMemory()).isTrue();
        assertThat(request.maxSources()).isEqualTo(8);
    }

    @Test
    void requestUsesUnicodeCodePointsAndValidatesAllBounds() {
        String emoji = "\uD83D\uDE03";
        ChatStreamRequest legal = new ChatStreamRequest(
                7L, emoji.repeat(1000), false, 20).validated();

        assertThat(legal.question().codePointCount(0, legal.question().length()))
                .isEqualTo(1000);
        assertValidation(() -> new ChatStreamRequest(null, "  ", true, 8).validated());
        assertValidation(() -> new ChatStreamRequest(
                null, emoji.repeat(1001), true, 8).validated());
        assertValidation(() -> new ChatStreamRequest(null, "Question", true, 0).validated());
        assertValidation(() -> new ChatStreamRequest(null, "Question", true, 21).validated());
        assertValidation(() -> new ChatStreamRequest(0L, "Question", true, 8).validated());
    }

    @Test
    void questionLimitPredicateUsesTheSameNormalizedQuestionAsValidation() {
        String emoji = "\uD83D\uDE03";
        ChatStreamRequest request = new ChatStreamRequest(
                null, " " + emoji.repeat(1000) + " ", true, 8);

        assertThat(request.isQuestionWithinCodePointLimit()).isTrue();
        assertThat(request.validated().question()).isEqualTo(emoji.repeat(1000));
    }

    @Test
    void doneDtoDefensivelyCopiesSources() {
        List<SourceCitationDto> mutable = new ArrayList<>();
        mutable.add(new SourceCitationDto(
                1L, SourceType.UGC, "Experience", null, null,
                null, 0.8, "Quote"));

        ChatDoneDto done = new ChatDoneDto(
                3L, 4L, AnswerStatus.ANSWERED, "Answer", mutable);
        mutable.clear();

        assertThat(done.sources()).hasSize(1);
        assertThatThrownBy(() -> done.sources().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private void assertValidation(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable
    ) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
