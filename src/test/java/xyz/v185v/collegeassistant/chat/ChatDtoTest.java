package xyz.v185v.collegeassistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ChatDtoTest {

    @Test
    void sourceCitationMatchesContractAndAllowsLegalUgcNullOrBlankTitle() {
        SourceCitationDto citation = new SourceCitationDto(
                7L, SourceType.UGC, null, null, null, null, null, null);
        SourceCitationDto blankTitleCitation = new SourceCitationDto(
                8L, SourceType.UGC, "  ", null, null, null, null, null);

        assertThat(citation.chunkId()).isEqualTo(7L);
        assertThat(citation.sourceType()).isEqualTo(SourceType.UGC);
        assertThat(citation.sourceUrl()).isNull();
        assertThat(citation.quote()).isNull();
        assertThat(blankTitleCitation.title()).isEqualTo("  ");
        assertThat(AnswerStatus.values()).containsExactly(
                AnswerStatus.ANSWERED,
                AnswerStatus.NO_EVIDENCE,
                AnswerStatus.NEED_PROFILE,
                AnswerStatus.FAILED);
    }

    @Test
    void officialCitationRequiresNonBlankTitleWithFixedSafeMessage() {
        for (String title : new String[]{null, "  "}) {
            assertThatThrownBy(() -> new SourceCitationDto(
                    7L,
                    SourceType.OFFICIAL,
                    title,
                    "https://example.edu/rule",
                    "Registrar",
                    OffsetDateTime.parse("2026-07-01T09:00:00Z"),
                    0.8,
                    null))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Official citation title must not be blank");
        }
    }

    @Test
    void officialCitationRequiresUrlAndScoreMustBeFiniteWithinUnitInterval() {
        assertThatThrownBy(() -> citation(SourceType.OFFICIAL, null, 0.8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL");
        for (double score : List.of(-0.01, 1.01, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThatThrownBy(() -> citation(SourceType.UGC, null, score))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("score");
        }
        assertThat(citation(SourceType.OFFICIAL, "https://example.edu/rule", 0.0)
                .relevanceScore()).isZero();
        assertThat(citation(SourceType.OFFICIAL, "https://example.edu/rule", 1.0)
                .relevanceScore()).isEqualTo(1.0);
    }

    @Test
    void anyPresentCitationUrlMustBeSafeAbsoluteHttpAndQuoteIsShort() {
        for (String unsafe : List.of(
                "javascript:alert(1)",
                "data:text/html,unsafe",
                "/relative/path",
                "https://user:password@example.edu/rule",
                "https:relative-with-scheme",
                "ftp://example.edu/file")) {
            assertThatThrownBy(() -> citation(SourceType.UGC, unsafe, 0.5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("URL");
        }
        assertThat(citation(SourceType.UGC, "http://example.edu/post", 0.5).sourceUrl())
                .isEqualTo("http://example.edu/post");
        assertThat(citation(
                SourceType.UGC,
                "http://10.0.0.8:8080/rules?q=gpa%20policy&lang=zh#section-1",
                0.5).sourceUrl()).contains("10.0.0.8");
        assertThat(citation(
                SourceType.UGC,
                "http://localhost:8080/rules?q=gpa#section-1",
                0.5).sourceUrl()).contains("localhost");

        assertThatThrownBy(() -> new SourceCitationDto(
                7L,
                SourceType.UGC,
                "Title",
                null,
                null,
                null,
                0.5,
                "😀".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quote");
    }

    @Test
    void citationTextFieldsUseSchemaAlignedUnicodeCodePointLimits() {
        String emoji = "\uD83D\uDE03";
        String titleAtLimit = emoji.repeat(ChatConstraints.MAX_SOURCE_TITLE_CODE_POINTS);
        String orgAtLimit = emoji.repeat(ChatConstraints.MAX_SOURCE_ORG_CODE_POINTS);
        String urlAtLimit = urlWithCodePoints(ChatConstraints.MAX_SOURCE_URL_CODE_POINTS);

        SourceCitationDto citation = new SourceCitationDto(
                7L,
                SourceType.OFFICIAL,
                titleAtLimit,
                urlAtLimit,
                orgAtLimit,
                null,
                0.5,
                null);

        assertThat(citation.title()).isEqualTo(titleAtLimit);
        assertThat(citation.sourceOrg()).isEqualTo(orgAtLimit);
        assertThat(citation.sourceUrl()).isEqualTo(urlAtLimit);
        assertThatThrownBy(() -> new SourceCitationDto(
                7L, SourceType.UGC, titleAtLimit + "x", null, null,
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
        assertThatThrownBy(() -> new SourceCitationDto(
                7L, SourceType.UGC, null, null, orgAtLimit + "x",
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organization");
        assertThatThrownBy(() -> new SourceCitationDto(
                7L, SourceType.UGC, null,
                urlWithCodePoints(ChatConstraints.MAX_SOURCE_URL_CODE_POINTS + 1),
                null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL");
    }

    @Test
    void messageAndDetailDefensivelyCopyNestedCollections() {
        List<SourceCitationDto> mutableSources = new ArrayList<>();
        mutableSources.add(citation(SourceType.UGC, null, 0.7));
        ChatMessageDto message = new ChatMessageDto(
                11L,
                "ASSISTANT",
                "Answer",
                mutableSources,
                OffsetDateTime.parse("2026-07-14T12:00:00Z"));
        List<ChatMessageDto> mutableMessages = new ArrayList<>();
        mutableMessages.add(message);
        ChatSessionDetailDto detail = new ChatSessionDetailDto(
                3L,
                "Question",
                mutableMessages,
                false,
                OffsetDateTime.parse("2026-07-14T11:00:00Z"),
                OffsetDateTime.parse("2026-07-14T12:00:00Z"));

        mutableSources.clear();
        mutableMessages.clear();

        assertThat(message.sources()).hasSize(1);
        assertThat(detail.messages()).containsExactly(message);
        assertThatThrownBy(() -> message.sources().add(citation(SourceType.UGC, null, 0.5)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> detail.messages().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void messageRejectsUnknownRoleBlankContentNullElementsAndTooManySources() {
        SourceCitationDto citation = citation(SourceType.UGC, null, 0.5);
        assertThatThrownBy(() -> new ChatMessageDto(1L, "SYSTEM", "content", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");
        assertThatThrownBy(() -> new ChatMessageDto(1L, "USER", "  ", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
        List<SourceCitationDto> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> new ChatMessageDto(1L, "ASSISTANT", "content", withNull, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
        assertThatThrownBy(() -> new ChatMessageDto(
                1L, "ASSISTANT", "content", java.util.Collections.nCopies(21, citation), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20");
    }

    private SourceCitationDto citation(SourceType type, String url, Double score) {
        return new SourceCitationDto(
                7L,
                type,
                "Title",
                url,
                "Registrar",
                OffsetDateTime.parse("2026-07-01T09:00:00Z"),
                score,
                null);
    }

    private String urlWithCodePoints(int codePoints) {
        String prefix = "https://campus.local/";
        int remaining = codePoints - prefix.codePointCount(0, prefix.length());
        return prefix + "a".repeat(remaining);
    }
}
