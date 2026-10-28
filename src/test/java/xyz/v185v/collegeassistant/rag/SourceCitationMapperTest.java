package xyz.v185v.collegeassistant.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import xyz.v185v.collegeassistant.chat.CitationReferenceType;
import xyz.v185v.collegeassistant.chat.SourceType;

class SourceCitationMapperTest {

    private final SourceCitationMapper mapper = new SourceCitationMapper();

    @Test
    void ordersOfficialBeforeUgcStablyAndCapsTheCombinedResult() {
        RetrievedChunk ugcA = chunk(
                21L, "UGC", "ugc-a", null, "UGC A", Map.of());
        RetrievedChunk officialA = chunk(
                11L, "OFFICIAL", "official-a",
                "https://example.edu/a", "Official A", Map.of());
        RetrievedChunk ugcB = chunk(
                22L, "UGC", "ugc-b", null, "UGC B", Map.of());
        RetrievedChunk officialB = chunk(
                12L, "OFFICIAL", "official-b",
                "https://example.edu/b", "Official B", Map.of());

        EvidenceSet evidence = mapper.map(
                List.of(ugcA, officialA, ugcB, officialB), 3);

        assertThat(evidence.officialChunks())
                .containsExactly(officialA, officialB);
        assertThat(evidence.ugcChunks()).containsExactly(ugcA);
        assertThat(evidence.citations())
                .extracting(citation -> citation.chunkId())
                .containsExactly(11L, 12L, 21L);
        assertThat(evidence.isEmpty()).isFalse();
        assertThatThrownBy(() -> evidence.officialChunks().add(officialA))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> evidence.ugcChunks().add(ugcA))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> evidence.citations().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void filtersInvalidOfficialMetadataAndKeepsUgcWithoutInventingAUrl() {
        RetrievedChunk missingUrl = chunk(
                31L, "OFFICIAL", "missing-url", null,
                "Missing URL", Map.of());
        RetrievedChunk unsafeUrl = chunk(
                32L, "OFFICIAL", "unsafe-url", "file:///private/rule",
                "Unsafe URL", Map.of());
        RetrievedChunk overlongTitle = chunk(
                33L, "OFFICIAL", "overlong-title",
                "https://example.edu/long", "t".repeat(513), Map.of());
        RetrievedChunk nullTitle = chunk(
                34L, "OFFICIAL", "null-title",
                "https://example.edu/null-title", null, Map.of());
        RetrievedChunk blankTitle = chunk(
                35L, "OFFICIAL", "blank-title",
                "https://example.edu/blank-title", "  ", Map.of());
        RetrievedChunk ugc = chunk(
                41L,
                "UGC",
                "student experience",
                null,
                "  ",
                Map.of("fileObjectKey", "private/ugc-41.txt"));

        EvidenceSet evidence = mapper.map(
                List.of(
                        missingUrl,
                        unsafeUrl,
                        overlongTitle,
                        nullTitle,
                        blankTitle,
                        ugc),
                10);

        assertThat(evidence.officialChunks()).isEmpty();
        assertThat(evidence.ugcChunks()).containsExactly(ugc);
        assertThat(evidence.citations()).hasSize(1);
        assertThat(evidence.citations().getFirst().sourceType())
                .isEqualTo(SourceType.UGC);
        assertThat(evidence.citations().getFirst().title()).isEqualTo("  ");
        assertThat(evidence.citations().getFirst().sourceUrl()).isNull();
        assertThat(evidence.citations().getFirst().sourceUrl())
                .isNotEqualTo("private/ugc-41.txt");
    }

    @Test
    void truncatesQuotesAtFiveHundredUnicodeCodePoints() {
        String emoji = "\uD83D\uDE03";
        RetrievedChunk exact = chunk(
                51L, "UGC", emoji.repeat(500), null,
                "Exact", Map.of());
        RetrievedChunk over = chunk(
                52L, "UGC", emoji.repeat(501), null,
                "Over", Map.of());

        EvidenceSet evidence = mapper.map(List.of(exact, over), 2);

        assertThat(evidence.citations().get(0).quote())
                .isEqualTo(emoji.repeat(500));
        assertThat(evidence.citations().get(1).quote())
                .isEqualTo(emoji.repeat(500));
        assertThat(evidence.citations().get(1).quote()
                .codePointCount(
                        0, evidence.citations().get(1).quote().length()))
                .isEqualTo(500);
    }

    @Test
    void mapsExternalDocumentsWithoutAnInternalDownloadReference() {
        RetrievedChunk official = chunk(
                71L,
                "OFFICIAL",
                "registration rule",
                "https://example.edu/registration",
                "Registration rule",
                Map.of());

        EvidenceSet evidence = mapper.map(List.of(official), 1);

        assertThat(evidence.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.referenceType()).isEqualTo(CitationReferenceType.EXTERNAL);
            assertThat(citation.documentId()).isNull();
            assertThat(citation.externalSourceUrl()).isNull();
            assertThat(citation.sourceUrl()).isEqualTo("https://example.edu/registration");
        });
    }

    @Test
    void mapsInternalDocumentsWithoutExposingTheirStorageLocator() {
        RetrievedChunk internal = new RetrievedChunk(
                72L,
                "internal handbook",
                "OFFICIAL",
                0.9,
                null,
                "Internal handbook",
                "Registrar",
                OffsetDateTime.parse("2026-07-01T09:00:00Z"),
                Map.of(),
                CitationReferenceType.INTERNAL,
                802L,
                "https://example.edu/handbook");

        EvidenceSet evidence = mapper.map(List.of(internal), 1);

        assertThat(evidence.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.referenceType()).isEqualTo(CitationReferenceType.INTERNAL);
            assertThat(citation.documentId()).isEqualTo(802L);
            assertThat(citation.sourceUrl()).isNull();
            assertThat(citation.externalSourceUrl()).isEqualTo("https://example.edu/handbook");
        });
    }

    @Test
    void isEmptyWhenEveryCitationIsInvalid() {
        EvidenceSet evidence = mapper.map(
                List.of(chunk(
                        61L, "OFFICIAL", "missing-url", null,
                        "Missing URL", Map.of())),
                8);

        assertThat(evidence.isEmpty()).isTrue();
        assertThat(evidence.officialChunks()).isEmpty();
        assertThat(evidence.ugcChunks()).isEmpty();
        assertThat(evidence.citations()).isEmpty();
    }

    private RetrievedChunk chunk(
            Long id,
            String sourceType,
            String text,
            String sourceUrl,
            String title,
            Map<String, Object> metadata
    ) {
        return new RetrievedChunk(
                id,
                text,
                sourceType,
                0.9,
                sourceUrl,
                title,
                "Registrar",
                OffsetDateTime.parse("2026-07-01T09:00:00Z"),
                metadata);
    }
}
