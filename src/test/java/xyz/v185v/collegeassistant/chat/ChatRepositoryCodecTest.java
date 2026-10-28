package xyz.v185v.collegeassistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;

class ChatRepositoryCodecTest {

    private ChatRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ChatRepository(null, JsonMapper.builder().findAndAddModules().build());
    }

    @Test
    void sourcesRoundTripWithOptionalNullsAndReturnedListIsImmutable() {
        List<SourceCitationDto> mutable = new ArrayList<>();
        mutable.add(new SourceCitationDto(
                9L,
                SourceType.OFFICIAL,
                "Rules",
                "https://example.edu/rules",
                "Registrar",
                OffsetDateTime.parse("2026-07-01T09:00:00Z"),
                0.91,
                "Relevant quote"));
        mutable.add(new SourceCitationDto(
                10L, SourceType.UGC, null, null, null, null, null, null));

        String json = repository.serializeSources(mutable);
        mutable.clear();
        List<SourceCitationDto> decoded = repository.deserializeSources(json);

        assertThat(decoded).hasSize(2);
        assertThat(decoded.get(0).publishedAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-01T09:00:00Z"));
        assertThat(decoded.get(1).sourceUrl()).isNull();
        assertThatThrownBy(() -> decoded.clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullSourcesUseAnEmptyJsonArrayAndDecodeAsEmpty() {
        assertThat(repository.serializeSources(null)).isEqualTo("[]");
        assertThat(repository.deserializeSources(null)).isEmpty();
        assertThat(repository.deserializeSources("null")).isEmpty();
        assertThat(repository.deserializeSources("   ")).isEmpty();
    }

    @Test
    void legacyOfficialCitationsDecodeAsExternalWithoutARewrittenDocumentReference() {
        String legacy = "[{\"chunkId\":9,\"sourceType\":\"OFFICIAL\","
                + "\"title\":\"Rules\",\"sourceUrl\":\"https://example.edu/rules\"}]";

        SourceCitationDto decoded = repository.deserializeSources(legacy).getFirst();

        assertThat(decoded.referenceType()).isEqualTo(CitationReferenceType.EXTERNAL);
        assertThat(decoded.documentId()).isNull();
        assertThat(decoded.externalSourceUrl()).isNull();
    }

    @Test
    void previousStableReferenceValuesDecodeIntoTheNewCitationContract() {
        String previous = "[{\"chunkId\":9,\"sourceType\":\"OFFICIAL\","
                + "\"title\":\"Rules\",\"sourceUrl\":\"https://example.edu/rules\","
                + "\"referenceType\":\"DOCUMENT\",\"referenceId\":801}]";

        SourceCitationDto decoded = repository.deserializeSources(previous).getFirst();

        assertThat(decoded.referenceType()).isEqualTo(CitationReferenceType.EXTERNAL);
        assertThat(decoded.documentId()).isNull();
        assertThat(decoded.externalSourceUrl()).isNull();
    }

    @Test
    void previousUgcStableReferenceValuesDecodeAsUgc() {
        String previous = "[{\"chunkId\":10,\"sourceType\":\"UGC\","
                + "\"title\":\"Peer note\",\"sourceUrl\":null,"
                + "\"referenceType\":\"UGC_POST\",\"referenceId\":802}]";

        SourceCitationDto decoded = repository.deserializeSources(previous).getFirst();

        assertThat(decoded.referenceType()).isEqualTo(CitationReferenceType.UGC);
        assertThat(decoded.documentId()).isNull();
        assertThat(decoded.externalSourceUrl()).isNull();
    }

    @Test
    void malformedNonArrayNullElementAndTooManyPersistedSourcesFailSafely() {
        SourceCitationDto citation = new SourceCitationDto(
                9L, SourceType.UGC, "Title", null, null, null, 0.5, null);
        String tooMany = JsonMapper.builder().build()
                .writeValueAsString(Collections.nCopies(21, citation));

        for (String invalid : List.of(
                "{\"quote\":\"SECRET-CONTENT\"}",
                "[null]",
                tooMany)) {
            assertThatThrownBy(() -> repository.deserializeSources(invalid))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Failed to decode chat sources")
                    .message()
                    .doesNotContain("SECRET-CONTENT", invalid);
        }
    }

    @Test
    void serializationRejectsNullElementsAndMoreThanTwentySources() {
        SourceCitationDto citation = new SourceCitationDto(
                9L, SourceType.UGC, "Title", null, null, null, 0.5, null);
        List<SourceCitationDto> withNull = new ArrayList<>();
        withNull.add(null);

        assertThatThrownBy(() -> repository.serializeSources(withNull))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Chat sources must not contain null elements");
        assertThatThrownBy(() -> repository.serializeSources(
                Collections.nCopies(21, citation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20");
    }

    @Test
    void oversizedDecodeIsRejectedBeforeObjectMapperWithoutEchoingContent() {
        ObjectMapper mapper = mock(ObjectMapper.class);
        ChatRepository guarded = new ChatRepository(null, mapper);
        String secret = "DO-NOT-ECHO-SECRET";
        String oversized = secret + "x".repeat(
                ChatConstraints.MAX_SOURCES_JSON_STORED_BYTES);

        assertThatThrownBy(() -> guarded.deserializeSources(oversized))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to decode chat sources")
                .message()
                .doesNotContain(secret);
        verifyNoInteractions(mapper);
    }

    @Test
    void oversizedEncodeIsRejectedBeforeObjectMapper() {
        ObjectMapper mapper = mock(ObjectMapper.class);
        ChatRepository guarded = new ChatRepository(null, mapper);
        String prefix = "https://campus.local/";
        String urlAtLimit = prefix + "a".repeat(
                ChatConstraints.MAX_SOURCE_URL_CODE_POINTS
                        - prefix.codePointCount(0, prefix.length()));
        SourceCitationDto citation = new SourceCitationDto(
                9L,
                SourceType.UGC,
                "\u4E2D".repeat(ChatConstraints.MAX_SOURCE_TITLE_CODE_POINTS),
                urlAtLimit,
                "\u4E2D".repeat(ChatConstraints.MAX_SOURCE_ORG_CODE_POINTS),
                null,
                null,
                "\u4E2D".repeat(ChatConstraints.MAX_QUOTE_CODE_POINTS));
        List<SourceCitationDto> sources = Collections.nCopies(20, citation);

        assertThatThrownBy(() -> guarded.serializeSources(sources))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Chat sources JSON exceeds the size limit");
        verifyNoInteractions(mapper);
    }

    @Test
    void persistedSourcesStillRespectTheEncodingBudgetAfterDecode() {
        String prefix = "https://campus.local/";
        String urlAtLimit = prefix + "a".repeat(
                ChatConstraints.MAX_SOURCE_URL_CODE_POINTS
                        - prefix.codePointCount(0, prefix.length()));
        SourceCitationDto citation = new SourceCitationDto(
                9L,
                SourceType.UGC,
                "\u4E2D".repeat(ChatConstraints.MAX_SOURCE_TITLE_CODE_POINTS),
                urlAtLimit,
                "\u4E2D".repeat(ChatConstraints.MAX_SOURCE_ORG_CODE_POINTS),
                null,
                null,
                "\u4E2D".repeat(ChatConstraints.MAX_QUOTE_CODE_POINTS));
        String persistedJson = JsonMapper.builder().findAndAddModules().build()
                .writeValueAsString(Collections.nCopies(20, citation));

        assertThat(persistedJson.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(ChatConstraints.MAX_SOURCES_JSON_STORED_BYTES);
        assertThatThrownBy(() -> repository.deserializeSources(persistedJson))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to decode chat sources");
    }

    @Test
    void escapedSerializationExpansionIsCheckedAfterEncoding() {
        ObjectMapper mapper = mock(ObjectMapper.class);
        ChatRepository guarded = new ChatRepository(null, mapper);
        SourceCitationDto citation = new SourceCitationDto(
                9L, SourceType.UGC, null, null, null, null, null, null);
        when(mapper.writeValueAsString(any()))
                .thenReturn("\u4E2D".repeat(
                        ChatConstraints.MAX_SOURCES_JSON_BYTES / 3 + 1));

        assertThatThrownBy(() -> guarded.serializeSources(List.of(citation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Chat sources JSON exceeds the size limit");
    }

    @Test
    void multibyteDecodeOverStoredByteLimitIsRejectedBeforeObjectMapper() {
        ObjectMapper mapper = mock(ObjectMapper.class);
        ChatRepository guarded = new ChatRepository(null, mapper);
        String oversizedUtf8 = "\u4E2D".repeat(
                ChatConstraints.MAX_SOURCES_JSON_STORED_BYTES / 3 + 1);

        assertThat(oversizedUtf8.length())
                .isLessThan(ChatConstraints.MAX_SOURCES_JSON_STORED_BYTES);
        assertThatThrownBy(() -> guarded.deserializeSources(oversizedUtf8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to decode chat sources");
        verifyNoInteractions(mapper);
    }

    @Test
    void rawJsonBeyondBoundedFormattingAllowanceIsRejectedBeforeObjectMapper() {
        ObjectMapper mapper = mock(ObjectMapper.class);
        ChatRepository guarded = new ChatRepository(null, mapper);
        int storedLimit = ChatConstraints.MAX_SOURCES_JSON_STORED_BYTES;
        String oversized = "x".repeat(storedLimit + 1);

        assertThatThrownBy(() -> guarded.deserializeSources(oversized))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to decode chat sources");
        verifyNoInteractions(mapper);
    }

    @Test
    void jsonbWhitespaceAndNumericCanonicalizationDoNotBreakSizeRoundTrip() {
        String prefix = "https://campus.local/";
        String url = prefix + "a".repeat(
                ChatConstraints.MAX_SOURCE_URL_CODE_POINTS
                        - prefix.codePointCount(0, prefix.length()));
        SourceCitationDto base = new SourceCitationDto(
                9L,
                SourceType.UGC,
                "t".repeat(ChatConstraints.MAX_SOURCE_TITLE_CODE_POINTS),
                url,
                "o".repeat(ChatConstraints.MAX_SOURCE_ORG_CODE_POINTS),
                null,
                Double.MIN_VALUE,
                "");
        String quoteAtLimit = "\u4E2D".repeat(ChatConstraints.MAX_QUOTE_CODE_POINTS);
        SourceCitationDto quoteHeavy = new SourceCitationDto(
                9L,
                SourceType.UGC,
                base.title(),
                base.sourceUrl(),
                base.sourceOrg(),
                null,
                Double.MIN_VALUE,
                quoteAtLimit);
        String quoteHeavyJson = repository.serializeSources(
                Collections.nCopies(20, quoteHeavy));
        int bytesNeeded = ChatConstraints.MAX_SOURCES_JSON_BYTES + 1
                - utf8(jsonbFormatted(quoteHeavyJson));
        int multibyteTitleCodePoints = Math.max(
                0, (bytesNeeded + (2 * 20) - 1) / (2 * 20));
        assertThat(multibyteTitleCodePoints)
                .isLessThanOrEqualTo(ChatConstraints.MAX_SOURCE_TITLE_CODE_POINTS);
        String nearLimitTitle = "\u4E2D".repeat(multibyteTitleCodePoints)
                + "t".repeat(ChatConstraints.MAX_SOURCE_TITLE_CODE_POINTS
                - multibyteTitleCodePoints);
        SourceCitationDto nearLimit = new SourceCitationDto(
                9L,
                SourceType.UGC,
                nearLimitTitle,
                base.sourceUrl(),
                base.sourceOrg(),
                null,
                Double.MIN_VALUE,
                quoteAtLimit);
        List<SourceCitationDto> sources = Collections.nCopies(20, nearLimit);
        String compact = repository.serializeSources(sources);
        String storedJsonb = jsonbFormatted(compact);

        assertThat(utf8(compact)).isLessThanOrEqualTo(
                ChatConstraints.MAX_SOURCES_JSON_BYTES);
        assertThat(utf8(storedJsonb)).isGreaterThan(
                ChatConstraints.MAX_SOURCES_JSON_BYTES);
        assertThat(utf8(storedJsonb)).isLessThanOrEqualTo(
                ChatConstraints.MAX_SOURCES_JSON_STORED_BYTES);
        assertThat(repository.deserializeSources(storedJsonb)).isEqualTo(sources);
    }

    private int utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private String jsonbFormatted(String compact) {
        return compact
                .replace(
                        Double.toString(Double.MIN_VALUE),
                        BigDecimal.valueOf(Double.MIN_VALUE).toPlainString())
                .replace("\":", "\": ")
                .replace(",\"", ", \"")
                .replace("},{", "}, {");
    }
}
