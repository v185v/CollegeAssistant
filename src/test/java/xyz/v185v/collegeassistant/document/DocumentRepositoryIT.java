package xyz.v185v.collegeassistant.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class DocumentRepositoryIT extends DatabaseTestSupport {

    private static final int EMBEDDING_DIMENSIONS = 1536;
    private static final long EMBEDDING_CONFIG_ID = 2L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private EmbeddingChunkRepository embeddingChunkRepository;

    @Test
    void sourceCanBeCreatedLoadedAndListedOnlyWhenEnabled() {
        SourceRecord enabled = sourceRepository.create(source("Registrar", "https://registrar.example.edu", true));
        SourceRecord disabled = sourceRepository.create(source("Archive", "https://archive.example.edu", false));

        assertThat(enabled.id()).isNotNull();
        assertThat(enabled.createdAt()).isNotNull();
        assertThat(enabled.updatedAt()).isNotNull();
        assertThat(enabled.enabled()).isTrue();
        assertThat(sourceRepository.findById(enabled.id())).contains(enabled);
        assertThat(sourceRepository.findById(disabled.id())).contains(disabled);
        assertThat(sourceRepository.findEnabled())
                .extracting(SourceRecord::id)
                .containsExactly(enabled.id());
    }

    @Test
    void documentWriteReportsAddedThenUnchangedWithoutTouchingIdentityOrIngestedAt() {
        SourceRecord source = createSource("Document status source");
        DocumentRecord candidate = document(source.id(), "https://example.edu/rules", "hash-v1", "Rules v1");

        DocumentWriteResult added = documentRepository.write(candidate, false);

        assertThat(added.status()).isEqualTo(DocumentWriteStatus.ADDED);
        assertThat(added.document().id()).isNotNull();
        assertThat(added.document().ingestedAt()).isNotNull();
        assertThat(documentCount(source.id())).isEqualTo(1);

        OffsetDateTime originalIngestedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        setIngestedAt(added.document().id(), originalIngestedAt);

        DocumentWriteResult unchanged = documentRepository.write(candidate, false);

        assertThat(unchanged.status()).isEqualTo(DocumentWriteStatus.UNCHANGED);
        assertThat(unchanged.document().id()).isEqualTo(added.document().id());
        assertThat(unchanged.document().ingestedAt()).isEqualTo(originalIngestedAt);
        assertThat(documentCount(source.id())).isEqualTo(1);
    }

    @Test
    void changedHashAndForceBothReportUpdatedAndKeepOneDocumentId() {
        SourceRecord source = createSource("Document update source");
        String url = "https://example.edu/calendar";
        DocumentWriteResult added = documentRepository.write(
                document(source.id(), url, "hash-v1", "Calendar v1"), false);
        OffsetDateTime oldIngestedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        setIngestedAt(added.document().id(), oldIngestedAt);

        DocumentWriteResult changed = documentRepository.write(
                document(source.id(), url, "hash-v2", "Calendar v2"), false);

        assertThat(changed.status()).isEqualTo(DocumentWriteStatus.UPDATED);
        assertThat(changed.document().id()).isEqualTo(added.document().id());
        assertThat(changed.document().title()).isEqualTo("Calendar v2");
        assertThat(changed.document().contentHash()).isEqualTo("hash-v2");
        assertThat(changed.document().ingestedAt()).isAfter(oldIngestedAt);
        assertThat(documentCount(source.id())).isEqualTo(1);

        OffsetDateTime beforeForce = OffsetDateTime.parse("2026-02-01T00:00:00Z");
        setIngestedAt(changed.document().id(), beforeForce);

        DocumentWriteResult forced = documentRepository.write(
                document(source.id(), url, "hash-v2", "Calendar v2 forced"), true);

        assertThat(forced.status()).isEqualTo(DocumentWriteStatus.UPDATED);
        assertThat(forced.document().id()).isEqualTo(added.document().id());
        assertThat(forced.document().title()).isEqualTo("Calendar v2 forced");
        assertThat(forced.document().ingestedAt()).isAfter(beforeForce);
        assertThat(documentCount(source.id())).isEqualTo(1);
    }

    @Test
    void sameContentHashAtDifferentUrlsAddsSeparateDocuments() {
        SourceRecord source = createSource("Duplicate hash source");

        DocumentWriteResult first = documentRepository.write(
                document(source.id(), "https://example.edu/page-a", "shared-hash", "Page A"), false);
        DocumentWriteResult second = documentRepository.write(
                document(source.id(), "https://example.edu/page-b", "shared-hash", "Page B"), false);

        assertThat(first.status()).isEqualTo(DocumentWriteStatus.ADDED);
        assertThat(second.status()).isEqualTo(DocumentWriteStatus.ADDED);
        assertThat(second.document().id()).isNotEqualTo(first.document().id());
        assertThat(documentCount(source.id())).isEqualTo(2);
    }

    @Test
    void documentCanBeFoundBySourceIdAndSourceUrl() {
        SourceRecord source = createSource("Identity lookup");
        String url = "https://example.edu/identity-lookup";
        DocumentRecord stored = documentRepository.write(
                document(source.id(), url, "identity-hash", "Identity"), false).document();

        assertThat(documentRepository.findBySourceIdAndSourceUrl(source.id(), url))
                .contains(stored);
        assertThat(documentRepository.findBySourceIdAndSourceUrl(
                source.id(), "https://example.edu/missing"))
                .isEmpty();
    }

    @Test
    void internalDocumentIdentityAndManagementFieldsRoundTrip() {
        SourceRecord source = createSource("Internal document source");
        UUID locator = UUID.fromString("8ed293a1-4a99-4eef-a0cb-9fbf809bbb18");
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-07-25T08:00:00Z");
        DocumentRecord candidate = new DocumentRecord(
                null, source.id(), "Campus rules", "internal://document/" + locator,
                "Example University", observedAt, "internal-hash", "documents/internal-rules",
                null, DocumentReferenceType.INTERNAL, "https://example.edu/rules", locator,
                "Rules.TXT", "rules.txt", DocumentStatus.ACTIVE, null, null,
                observedAt, observedAt, "UNDERGRADUATE", "Engineering", "North", "2026");

        DocumentWriteResult added = documentRepository.write(candidate, false);

        assertThat(added.status()).isEqualTo(DocumentWriteStatus.ADDED);
        assertThat(added.document().referenceType()).isEqualTo(DocumentReferenceType.INTERNAL);
        assertThat(added.document().internalLocator()).isEqualTo(locator);
        assertThat(added.document().externalSourceUrl()).isEqualTo("https://example.edu/rules");
        assertThat(added.document().degree()).isEqualTo("UNDERGRADUATE");
        assertThat(documentRepository.findByInternalLocator(locator)).contains(added.document());
        assertThat(documentRepository.findActiveInternalBySourceIdAndContentHash(
                source.id(), "internal-hash")).contains(added.document());
        assertThat(documentRepository.findActiveInternalBySourceIdAndNormalizedFilename(
                source.id(), "rules.txt")).contains(added.document());

        assertThat(documentRepository.markDeleting(added.document().id())).isTrue();
        DocumentRecord deleting = documentRepository.findById(added.document().id()).orElseThrow();
        assertThat(documentRepository.findInternalBySourceIdAndContentHash(
                source.id(), "internal-hash")).contains(deleting);
        assertThat(documentRepository.findInternalBySourceIdAndNormalizedFilename(
                source.id(), "rules.txt")).contains(deleting);
        assertThat(documentRepository.findActiveInternalBySourceIdAndContentHash(
                source.id(), "internal-hash")).isEmpty();
        assertThat(documentRepository.findActiveInternalBySourceIdAndNormalizedFilename(
                source.id(), "rules.txt")).isEmpty();
    }

    @Test
    void internalIdentitySelectsNewestRowAndDeletionRemainsIdempotent() {
        SourceRecord source = createSource("Internal history source");
        DocumentRecord newest = null;
        for (int index = 0; index < 2; index++) {
            UUID locator = UUID.randomUUID();
            DocumentRecord candidate = new DocumentRecord(
                    null, source.id(), "History", "internal://document/" + locator,
                    null, null, "history-hash", "documents/history-" + index,
                    null, DocumentReferenceType.INTERNAL, null, locator,
                    "History.txt", "history.txt", DocumentStatus.ACTIVE, null, null,
                    null, null, null, null, null, null);
            newest = documentRepository.write(candidate, false).document();
            assertThat(documentRepository.markDeleteFailed(newest.id())).isFalse();
            assertThat(documentRepository.resumeDeleting(newest.id())).isFalse();
            assertThat(documentRepository.deleteDeleting(newest.id())).isFalse();
            assertThat(documentRepository.markDeleting(newest.id())).isTrue();
            assertThat(documentRepository.resumeDeleting(newest.id())).isTrue();
            assertThat(documentRepository.markDeleteFailed(newest.id())).isTrue();
            assertThat(documentRepository.markDeleteFailed(newest.id())).isFalse();
        }
        DocumentRecord expected = documentRepository.findById(newest.id()).orElseThrow();
        assertThat(documentRepository.findInternalBySourceIdAndContentHash(
                source.id(), "history-hash")).contains(expected);
        assertThat(documentRepository.findInternalBySourceIdAndNormalizedFilename(
                source.id(), "history.txt")).contains(expected);
        assertThat(documentRepository.findDeletionCandidates(source.id()))
                .extracting(candidate -> candidate.objectKey())
                .containsExactly("documents/history-0", "documents/history-1");
        assertThat(documentRepository.deleteById(expected.id())).isTrue();
        assertThat(documentRepository.deleteById(expected.id())).isTrue();
    }

    @Test
    void managementPagingCombinesSourceStatusTitleReferenceAndIngestionDateFilters() {
        SourceRecord matchingSource = createSource("Management paging matching");
        SourceRecord otherSource = createSource("Management paging other");
        DocumentRecord first = documentRepository.write(document(
                matchingSource.id(), "https://example.edu/guide-one", "guide-one",
                "Undergraduate Guide"), false).document();
        DocumentRecord second = documentRepository.write(document(
                matchingSource.id(), "https://example.edu/guide-two", "guide-two",
                "Graduate Guide"), false).document();
        documentRepository.write(document(
                otherSource.id(), "https://example.edu/guide-other", "guide-other",
                "Undergraduate Guide"), false);
        setIngestedAt(first.id(), OffsetDateTime.parse("2026-07-10T00:00:00Z"));
        setIngestedAt(second.id(), OffsetDateTime.parse("2026-06-10T00:00:00Z"));

        PageResult<DocumentRecord> result = documentRepository.find(
                1, 10, matchingSource.id(), DocumentStatus.ACTIVE, "undergraduate",
                DocumentReferenceType.EXTERNAL,
                OffsetDateTime.parse("2026-07-01T00:00:00Z"),
                OffsetDateTime.parse("2026-08-01T00:00:00Z"));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).extracting(DocumentRecord::id).containsExactly(first.id());
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.pages()).isEqualTo(1);
    }

    @Test
    void deletionLifecycleHidesContentAndOnlyDeletesClaimedRows() {
        DocumentRecord document = createDocument("document-deletion-lifecycle");

        assertThat(documentRepository.findAvailableForContent(document.id())).contains(document);
        assertThat(documentRepository.markDeleting(document.id())).isTrue();
        assertThat(documentRepository.findAvailableForContent(document.id())).isEmpty();
        assertThat(documentRepository.markDeleting(document.id())).isFalse();
        assertThat(documentRepository.markDeleteFailed(document.id())).isTrue();
        assertThat(documentRepository.resumeDeleting(document.id())).isTrue();
        assertThat(documentRepository.deleteDeleting(document.id())).isTrue();
        assertThat(documentRepository.findById(document.id())).isEmpty();
        assertThat(documentRepository.deleteDeleting(document.id())).isFalse();
    }

    @Test
    void sourceDeletionStateMakesDocumentContentUnavailable() {
        DocumentRecord document = createDocument("source-deleting-document-content");
        jdbcClient.sql("UPDATE source SET deletion_status = 'DELETING' WHERE id = :sourceId")
                .param("sourceId", document.sourceId())
                .update();

        assertThat(documentRepository.findAvailableForContent(document.id())).isEmpty();
    }

    @Test
    @Transactional
    void reindexPublicationLockRequiresBothSourceAndDocumentToRemainActive() {
        DocumentRecord document = createDocument("reindex-publication-lock");

        assertThat(documentRepository.lockActiveForReindex(document.id())).contains(document);
        jdbcClient.sql("UPDATE source SET deletion_status = 'DELETING' WHERE id = :sourceId")
                .param("sourceId", document.sourceId())
                .update();
        assertThat(documentRepository.lockActiveForReindex(document.id())).isEmpty();

        jdbcClient.sql("UPDATE source SET deletion_status = 'NONE' WHERE id = :sourceId")
                .param("sourceId", document.sourceId())
                .update();
        assertThat(documentRepository.markDeleting(document.id())).isTrue();
        assertThat(documentRepository.lockActiveForReindex(document.id())).isEmpty();
    }

    @Test
    void replacingChunksRemovesOldRowsAndEmptyReplacementClearsTheDocument() {
        DocumentRecord document = createDocument("replace-chunks");
        List<EmbeddingChunkRecord> first = embeddingChunkRepository.replaceForDocument(
                document.id(), EMBEDDING_CONFIG_ID, List.of(
                chunk(document.id(), 0, "old zero", unitVector(0), "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", Map.of()),
                chunk(document.id(), 1, "old one", unitVector(1), "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", Map.of())));

        assertThat(first).hasSize(2);
        assertThat(chunkCount(document.id())).isEqualTo(2);

        List<EmbeddingChunkRecord> replacement = embeddingChunkRepository.replaceForDocument(
                document.id(), EMBEDDING_CONFIG_ID, List.of(
                chunk(document.id(), 0, "new zero", unitVector(2), "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", Map.of())));

        assertThat(replacement).hasSize(1);
        assertThat(replacement.getFirst().id()).isNotIn(first.stream().map(EmbeddingChunkRecord::id).toList());
        assertThat(chunkTexts(document.id())).containsExactly("new zero");

        assertThat(embeddingChunkRepository.replaceForDocument(
                document.id(), EMBEDDING_CONFIG_ID, List.of())).isEmpty();
        assertThat(chunkCount(document.id())).isZero();
    }

    @Test
    void replacingChunksRollsBackTheWholeBatchWhenTheSecondInsertIsInvalid() {
        DocumentRecord document = createDocument("rollback-chunks");
        embeddingChunkRepository.replaceForDocument(document.id(), EMBEDDING_CONFIG_ID, List.of(
                chunk(document.id(), 0, "old retained", unitVector(2),
                        "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", Map.of())));
        EmbeddingChunkRecord valid = chunk(
                document.id(), 0, "valid first insert", unitVector(0),
                "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", Map.of());
        EmbeddingChunkRecord invalidAtDatabase = chunk(
                document.id(), 1, "invalid second insert", unitVector(1),
                "X".repeat(33), "GENERAL", "GENERAL", "GENERAL", Map.of());

        assertThatThrownBy(() -> embeddingChunkRepository.replaceForDocument(
                document.id(), EMBEDDING_CONFIG_ID, List.of(valid, invalidAtDatabase)))
                .isInstanceOf(DataAccessException.class);

        assertThat(chunkCount(document.id())).isEqualTo(1);
        assertThat(chunkTexts(document.id())).containsExactly("old retained");
    }

    @Test
    void genericReplaceRequiresEveryChunkToMatchTheTargetReference() {
        DocumentRecord document = createDocument("generic-replace");
        EmbeddingChunkRecord matching = chunk(
                document.id(), 0, "generic replacement", unitVector(0),
                "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", Map.of());

        List<EmbeddingChunkRecord> inserted = embeddingChunkRepository.replace(
                "DOCUMENT", document.id(), EMBEDDING_CONFIG_ID, List.of(matching));

        assertThat(inserted).hasSize(1);
        assertThat(chunkTexts(document.id())).containsExactly("generic replacement");

        EmbeddingChunkRecord mismatched = chunk(
                document.id() + 1, 0, "wrong reference", unitVector(1),
                "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", Map.of());
        assertThatThrownBy(() -> embeddingChunkRepository.replace(
                "DOCUMENT", document.id(), EMBEDDING_CONFIG_ID, List.of(mismatched)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reference");
        assertThat(chunkTexts(document.id())).containsExactly("generic replacement");
    }

    @Test
    void embeddingChunkIdentityIsProtectedByANamedUniqueConstraintAndIndex() {
        String constraintDefinition = jdbcClient.sql("""
                        SELECT pg_get_constraintdef(c.oid)
                        FROM pg_constraint c
                        JOIN pg_class t ON t.oid = c.conrelid
                        JOIN pg_namespace n ON n.oid = t.relnamespace
                        WHERE n.nspname = current_schema()
                          AND t.relname = 'embedding_chunk'
                          AND c.conname = 'uq_embedding_chunk_identity'
                          AND c.contype = 'u'
                        """)
                .query(String.class)
                .single();
        String indexDefinition = jdbcClient.sql("""
                        SELECT indexdef
                        FROM pg_indexes
                        WHERE schemaname = current_schema()
                          AND tablename = 'embedding_chunk'
                          AND indexname = 'uq_embedding_chunk_identity'
                        """)
                .query(String.class)
                .single();

        assertThat(constraintDefinition)
                .contains("UNIQUE")
                .contains("doc_ref_type", "doc_ref_id", "chunk_index");
        assertThat(indexDefinition)
                .contains("UNIQUE INDEX")
                .contains("doc_ref_type", "doc_ref_id", "chunk_index");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void concurrentReplacementsSerializeAndLeaveOneCompleteBatch() throws Exception {
        DocumentRecord document = createDocument("concurrent-replace");
        List<EmbeddingChunkRecord> batchA = List.of(
                chunk(document.id(), 0, "A-0", unitVector(0),
                        "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", Map.of()),
                chunk(document.id(), 1, "A-1", unitVector(1),
                        "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", Map.of()));
        List<EmbeddingChunkRecord> batchB = List.of(
                chunk(document.id(), 0, "B-0", unitVector(2),
                        "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", Map.of()),
                chunk(document.id(), 1, "B-1", unitVector(3),
                        "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", Map.of()));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (Connection lockConnection = dataSource.getConnection()) {
            lockConnection.setAutoCommit(false);
            acquireReplacementLock(lockConnection, "DOCUMENT", document.id());
            Future<List<EmbeddingChunkRecord>> futureA = executor.submit(
                    () -> replaceAfterStart(ready, start, document.id(), batchA));
            Future<List<EmbeddingChunkRecord>> futureB = executor.submit(
                    () -> replaceAfterStart(ready, start, document.id(), batchB));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThatThrownBy(() -> futureA.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> futureB.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            lockConnection.commit();
            assertThat(futureA.get(10, TimeUnit.SECONDS)).hasSize(2);
            assertThat(futureB.get(10, TimeUnit.SECONDS)).hasSize(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        List<String> finalTexts = chunkTexts(document.id());
        assertThat(finalTexts.size()).isEqualTo(2);
        assertThat(finalTexts.equals(List.of("A-0", "A-1"))
                || finalTexts.equals(List.of("B-0", "B-1"))).isTrue();
    }

    @Test
    void nestedJsonbMetadataRoundTripsThroughSearchResults() {
        DocumentRecord document = createDocument("nested-jsonb");
        Map<String, Object> metadata = Map.of(
                "audience", Map.of(
                        "years", List.of(1, 2, 3),
                        "flags", Map.of("international", true, "exchange", false)),
                "tags", List.of("registration", "deadline"));
        embeddingChunkRepository.replaceForDocument(document.id(), EMBEDDING_CONFIG_ID, List.of(
                chunk(document.id(), 0, "metadata chunk", unitVector(0),
                        "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", metadata)));

        List<EmbeddingChunkSearchResult> results = embeddingChunkRepository.search(
                criteria(unitVector(0), 5, null, null, null, null));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().chunk().meta()).isEqualTo(metadata);
    }

    @Test
    void searchHydratesInternalDocumentCitationMetadata() {
        SourceRecord source = createSource("Internal citation source");
        UUID locator = UUID.fromString("b4c44aa6-64e4-4ac4-b15e-46d11d2b7d03");
        DocumentRecord document = documentRepository.write(new DocumentRecord(
                null,
                source.id(),
                "Internal handbook",
                "internal://document/" + locator,
                "Example University",
                OffsetDateTime.parse("2026-07-25T08:00:00Z"),
                "internal-citation-hash",
                "documents/internal-handbook",
                null,
                DocumentReferenceType.INTERNAL,
                "https://example.edu/handbook",
                locator,
                "handbook.txt",
                "handbook.txt",
                DocumentStatus.ACTIVE,
                null,
                null,
                OffsetDateTime.parse("2026-07-25T08:00:00Z"),
                OffsetDateTime.parse("2026-07-25T08:00:00Z"),
                "GENERAL",
                "GENERAL",
                "GENERAL",
                "GENERAL"), false).document();
        List<Double> embedding = unitVector(0);
        embeddingChunkRepository.replaceForDocument(document.id(), EMBEDDING_CONFIG_ID, List.of(
                chunk(document.id(), 0, "internal handbook", embedding,
                        "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", Map.of())));

        EmbeddingChunkSearchResult result = embeddingChunkRepository.search(
                criteria(embedding, 1, "OFFICIAL", null, null, null)).getFirst();

        assertThat(result.documentReferenceType()).isEqualTo(DocumentReferenceType.INTERNAL);
        assertThat(result.documentId()).isEqualTo(document.id());
        assertThat(result.externalSourceUrl()).isEqualTo("https://example.edu/handbook");
    }

    @Test
    void cosineSearchUses1536DimensionVectorsAndReturnsSimilarityScores() {
        DocumentRecord document = createDocument("cosine-search");
        List<Double> exact = unitVector(0);
        List<Double> near = vectorWith(0, 0.8, 1, 0.6);
        List<Double> orthogonal = unitVector(1);
        embeddingChunkRepository.replaceForDocument(document.id(), EMBEDDING_CONFIG_ID, List.of(
                chunk(document.id(), 0, "exact", exact, "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", Map.of()),
                chunk(document.id(), 1, "near", near, "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", Map.of()),
                chunk(document.id(), 2, "orthogonal", orthogonal, "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", Map.of())));

        List<EmbeddingChunkSearchResult> results = embeddingChunkRepository.search(
                criteria(exact, 3, null, null, null, null));

        assertThat(results)
                .extracting(result -> result.chunk().chunkText())
                .containsExactly("exact", "near", "orthogonal");
        assertThat(results)
                .extracting(result -> result.chunk().embeddingConfigId())
                .containsOnly(EMBEDDING_CONFIG_ID);
        assertThat(results.get(0).score()).isCloseTo(1.0, within(0.0001));
        assertThat(results.get(1).score()).isCloseTo(0.8, within(0.0001));
        assertThat(results.get(2).score()).isCloseTo(0.0, within(0.0001));
    }

    @Test
    @Transactional
    void filteredSearchExecutesWithStrictHnswIterativeScanInTheCurrentTransaction() {
        DocumentRecord document = createDocument("hnsw-iterative-scan");
        embeddingChunkRepository.replaceForDocument(document.id(), EMBEDDING_CONFIG_ID, List.of(
                chunk(document.id(), 0, "strict iterative match", unitVector(0),
                        "OFFICIAL", "UNDERGRADUATE", "Engineering", "North", "2024", Map.of())));

        List<EmbeddingChunkSearchResult> results = embeddingChunkRepository.search(criteria(
                unitVector(0), 5, "OFFICIAL", "UNDERGRADUATE", "Engineering", "North", "2024"));
        String iterativeScan = jdbcClient.sql(
                        "SELECT current_setting('hnsw.iterative_scan')")
                .query(String.class)
                .single();
        String vectorVersion = jdbcClient.sql(
                        "SELECT extversion FROM pg_extension WHERE extname = 'vector'")
                .query(String.class)
                .single();

        assertThat(results).extracting(result -> result.chunk().chunkText())
                .containsExactly("strict iterative match");
        assertThat(iterativeScan).isEqualTo("strict_order");
        assertThat(isAtLeastVersion(vectorVersion, 0, 8, 0))
                .as("pgvector %s must support iterative scans introduced in 0.8.0", vectorVersion)
                .isTrue();
    }

    @Test
    void searchSupportsSourceTypeAndIndividualProfileFiltersWithGeneralFallback() {
        DocumentRecord document = createDocument("individual-filters");
        embeddingChunkRepository.replaceForDocument(
                document.id(), EMBEDDING_CONFIG_ID, filterChunks(document.id()));

        assertThat(searchTexts(criteria(unitVector(0), 20, "OFFICIAL", null, null, null)))
                .containsExactlyInAnyOrder(
                        "exact", "general", "hybrid", "degree mismatch", "college mismatch", "campus mismatch");
        assertThat(searchTexts(criteria(unitVector(0), 20, null, "UNDERGRADUATE", null, null)))
                .containsExactlyInAnyOrder(
                        "exact", "general", "hybrid", "college mismatch", "campus mismatch");
        assertThat(searchTexts(criteria(unitVector(0), 20, null, null, "Engineering", null)))
                .containsExactlyInAnyOrder(
                        "exact", "general", "hybrid", "degree mismatch", "campus mismatch");
        assertThat(searchTexts(criteria(unitVector(0), 20, null, null, null, "North")))
                .containsExactlyInAnyOrder(
                        "exact", "general", "hybrid", "degree mismatch", "college mismatch");
    }

    @Test
    void searchCombinesProfileFiltersAndAcceptsGeneralForEachField() {
        DocumentRecord document = createDocument("combined-filters");
        embeddingChunkRepository.replaceForDocument(
                document.id(), EMBEDDING_CONFIG_ID, filterChunks(document.id()));

        List<String> matches = searchTexts(criteria(
                unitVector(0), 20, "OFFICIAL", "UNDERGRADUATE", "Engineering", "North"));

        assertThat(matches).containsExactlyInAnyOrder("exact", "general", "hybrid");
    }

    @Test
    void searchFiltersGradeRangeAndIncludesGeneralChunks() {
        DocumentRecord document = createDocument("grade-filter");
        embeddingChunkRepository.replaceForDocument(document.id(), EMBEDDING_CONFIG_ID, List.of(
                chunk(document.id(), 0, "grade match", unitVector(0),
                        "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", "2024", Map.of()),
                chunk(document.id(), 1, "grade general", unitVector(0),
                        "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", "GENERAL", Map.of()),
                chunk(document.id(), 2, "grade mismatch", unitVector(0),
                        "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", "2023", Map.of())));

        assertThat(searchTexts(criteria(
                unitVector(0), 20, "OFFICIAL", null, null, null, "2024")))
                .containsExactlyInAnyOrder("grade match", "grade general");
        assertThat(searchTexts(criteria(
                unitVector(0), 20, "OFFICIAL", null, null, null, null)))
                .containsExactlyInAnyOrder("grade match", "grade general", "grade mismatch");
    }

    @Test
    void ugcSearchReturnsOnlyApprovedPostsAndExcludesPendingRejectedAndOrphans() {
        Long authorId = jdbcClient.sql("""
                        INSERT INTO users (username, password_hash, display_name, role)
                        VALUES ('retrieval-author', 'hash', 'Retrieval Author', 'STUDENT')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
        Long approvedId = createUgcPost(authorId, "approved", "APPROVED");
        Long pendingId = createUgcPost(authorId, "pending", "PENDING");
        Long rejectedId = createUgcPost(authorId, "rejected", "REJECTED");
        Long orphanId = jdbcClient.sql("SELECT COALESCE(max(id), 0) + 10000 FROM ugc_post")
                .query(Long.class)
                .single();

        embeddingChunkRepository.replace("UGC", approvedId, EMBEDDING_CONFIG_ID, List.of(
                ugcChunk(approvedId, "approved UGC")));
        embeddingChunkRepository.replace("UGC", pendingId, EMBEDDING_CONFIG_ID, List.of(
                ugcChunk(pendingId, "pending UGC")));
        embeddingChunkRepository.replace("UGC", rejectedId, EMBEDDING_CONFIG_ID, List.of(
                ugcChunk(rejectedId, "rejected UGC")));
        embeddingChunkRepository.replace("UGC", orphanId, EMBEDDING_CONFIG_ID, List.of(
                ugcChunk(orphanId, "orphan UGC")));
        DocumentRecord officialDocument = createDocument("ugc-filter-official");
        embeddingChunkRepository.replaceForDocument(
                officialDocument.id(), EMBEDDING_CONFIG_ID, List.of(
                chunk(officialDocument.id(), 0, "legitimate official", unitVector(0),
                        "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", Map.of())));
        DocumentRecord fakeUgcDocument = createDocument("ugc-filter-fake");
        embeddingChunkRepository.replaceForDocument(
                fakeUgcDocument.id(), EMBEDDING_CONFIG_ID, List.of(
                chunk(fakeUgcDocument.id(), 0, "fake document UGC", unitVector(0),
                        "UGC", "GENERAL", "GENERAL", "GENERAL", Map.of("status", "APPROVED"))));
        embeddingChunkRepository.replace("UGC", orphanId + 1, EMBEDDING_CONFIG_ID, List.of(
                referencedChunk("UGC", orphanId + 1, "fake official UGC", "OFFICIAL")));
        Long orphanDocumentId = jdbcClient.sql(
                        "SELECT COALESCE(max(id), 0) + 10000 FROM document")
                .query(Long.class)
                .single();
        embeddingChunkRepository.replace(
                "DOCUMENT", orphanDocumentId, EMBEDDING_CONFIG_ID, List.of(
                referencedChunk(
                        "DOCUMENT", orphanDocumentId, "orphan official document", "OFFICIAL")));
        embeddingChunkRepository.replace(
                "MEMORY", orphanDocumentId, EMBEDDING_CONFIG_ID, List.of(
                referencedChunk("MEMORY", orphanDocumentId, "fake memory official", "OFFICIAL")));
        DocumentRecord unknownSourceDocument = createDocument("ugc-filter-unknown-source");
        embeddingChunkRepository.replaceForDocument(
                unknownSourceDocument.id(), EMBEDDING_CONFIG_ID, List.of(
                chunk(unknownSourceDocument.id(), 0, "unknown source type", unitVector(0),
                        "OTHER", "GENERAL", "GENERAL", "GENERAL", Map.of())));
        jdbcClient.sql("""
                        UPDATE ugc_post
                        SET likes = 17, reports = 3
                        WHERE id = :postId
                        """)
                .param("postId", approvedId)
                .update();

        List<EmbeddingChunkSearchResult> results = embeddingChunkRepository.search(criteria(
                unitVector(0), 20, "UGC", null, null, null, null));

        assertThat(results).extracting(result -> result.chunk().chunkText())
                .containsExactly("approved UGC");
        Map<String, Object> liveMetadata = results.getFirst().chunk().meta();
        assertThat(((Number) liveMetadata.get("likes")).longValue()).isEqualTo(17L);
        assertThat(((Number) liveMetadata.get("reports")).longValue()).isEqualTo(3L);

        assertThat(searchTexts(criteria(
                unitVector(0), 20, null, null, null, null, null)))
                .containsExactlyInAnyOrder("approved UGC", "legitimate official");
        assertThat(searchTexts(criteria(
                unitVector(0), 20, "OFFICIAL", null, null, null, null)))
                .containsExactly("legitimate official");
        assertThat(searchTexts(criteria(
                unitVector(0), 20, "OTHER", null, null, null, null)))
                .isEmpty();
    }

    private SourceRecord source(String name, String baseUrl, boolean enabled) {
        return new SourceRecord(null, name, baseUrl, "OFFICIAL", enabled, null, null);
    }

    private SourceRecord createSource(String name) {
        return sourceRepository.create(source(
                name,
                "https://" + name.toLowerCase().replace(' ', '-') + ".example.edu",
                true));
    }

    private DocumentRecord document(Long sourceId, String url, String hash, String title) {
        return new DocumentRecord(
                null,
                sourceId,
                title,
                url,
                "Example University",
                OffsetDateTime.parse("2026-07-01T09:00:00+08:00"),
                hash,
                "documents/" + hash + ".html",
                null);
    }

    private DocumentRecord createDocument(String slug) {
        SourceRecord source = createSource("Source " + slug);
        return documentRepository.write(
                document(source.id(), "https://example.edu/" + slug, "hash-" + slug, "Title " + slug),
                false).document();
    }

    private EmbeddingChunkRecord chunk(
            Long documentId,
            int chunkIndex,
            String text,
            List<Double> embedding,
            String sourceType,
            String degree,
            String college,
            String campus,
            Map<String, Object> meta) {
        return chunk(
                documentId, chunkIndex, text, embedding, sourceType,
                degree, college, campus, "GENERAL", meta);
    }

    private EmbeddingChunkRecord chunk(
            Long documentId,
            int chunkIndex,
            String text,
            List<Double> embedding,
            String sourceType,
            String degree,
            String college,
            String campus,
            String gradeRange,
            Map<String, Object> meta) {
        return new EmbeddingChunkRecord(
                null,
                EMBEDDING_CONFIG_ID,
                "DOCUMENT",
                documentId,
                chunkIndex,
                text,
                embedding,
                sourceType,
                "https://example.edu/documents/" + documentId,
                "Document " + documentId,
                "Example University",
                OffsetDateTime.parse("2026-07-01T09:00:00+08:00"),
                degree,
                college,
                campus,
                gradeRange,
                meta,
                null);
    }

    private EmbeddingChunkSearchCriteria criteria(
            List<Double> queryEmbedding,
            int topK,
            String sourceType,
            String degree,
            String college,
            String campus) {
        return criteria(queryEmbedding, topK, sourceType, degree, college, campus, null);
    }

    private EmbeddingChunkSearchCriteria criteria(
            List<Double> queryEmbedding,
            int topK,
            String sourceType,
            String degree,
            String college,
            String campus,
            String gradeRange) {
        return new EmbeddingChunkSearchCriteria(
                EMBEDDING_CONFIG_ID,
                queryEmbedding, topK, sourceType, degree, college, campus, gradeRange);
    }

    private Long createUgcPost(Long authorId, String slug, String status) {
        return jdbcClient.sql("""
                        INSERT INTO ugc_post (author_id, title, content, status)
                        VALUES (:authorId, :title, :content, :status)
                        RETURNING id
                        """)
                .param("authorId", authorId)
                .param("title", "UGC " + slug)
                .param("content", "Content " + slug)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private EmbeddingChunkRecord ugcChunk(Long postId, String text) {
        return referencedChunk("UGC", postId, text, "UGC");
    }

    private EmbeddingChunkRecord referencedChunk(
            String docRefType,
            Long docRefId,
            String text,
            String sourceType
    ) {
        return new EmbeddingChunkRecord(
                null,
                EMBEDDING_CONFIG_ID,
                docRefType,
                docRefId,
                0,
                text,
                unitVector(0),
                sourceType,
                null,
                text,
                "Retrieval Author",
                OffsetDateTime.parse("2026-07-01T09:00:00+08:00"),
                "GENERAL",
                "GENERAL",
                "GENERAL",
                "GENERAL",
                Map.of("likes", 0, "reports", 0),
                null);
    }

    private List<EmbeddingChunkRecord> filterChunks(Long documentId) {
        return List.of(
                chunk(documentId, 0, "exact", unitVector(0),
                        "OFFICIAL", "UNDERGRADUATE", "Engineering", "North", Map.of()),
                chunk(documentId, 1, "general", unitVector(0),
                        "OFFICIAL", "GENERAL", "GENERAL", "GENERAL", Map.of()),
                chunk(documentId, 2, "hybrid", unitVector(0),
                        "OFFICIAL", "GENERAL", "Engineering", "GENERAL", Map.of()),
                chunk(documentId, 3, "degree mismatch", unitVector(0),
                        "OFFICIAL", "GRADUATE", "Engineering", "North", Map.of()),
                chunk(documentId, 4, "college mismatch", unitVector(0),
                        "OFFICIAL", "UNDERGRADUATE", "Arts", "North", Map.of()),
                chunk(documentId, 5, "campus mismatch", unitVector(0),
                        "OFFICIAL", "UNDERGRADUATE", "Engineering", "South", Map.of()),
                chunk(documentId, 6, "ugc exact", unitVector(0),
                        "UGC", "UNDERGRADUATE", "Engineering", "North", Map.of()));
    }

    private List<String> searchTexts(EmbeddingChunkSearchCriteria criteria) {
        return embeddingChunkRepository.search(criteria).stream()
                .map(result -> result.chunk().chunkText())
                .toList();
    }

    private List<Double> unitVector(int index) {
        List<Double> vector = zeroVector();
        vector.set(index, 1.0);
        return vector;
    }

    private List<Double> vectorWith(int firstIndex, double firstValue, int secondIndex, double secondValue) {
        List<Double> vector = zeroVector();
        vector.set(firstIndex, firstValue);
        vector.set(secondIndex, secondValue);
        return vector;
    }

    private List<Double> zeroVector() {
        return new ArrayList<>(Collections.nCopies(EMBEDDING_DIMENSIONS, 0.0));
    }

    private boolean isAtLeastVersion(String version, int major, int minor, int patch) {
        String[] components = version.split("[-.]");
        int[] required = {major, minor, patch};
        for (int index = 0; index < required.length; index++) {
            int actual = index < components.length ? Integer.parseInt(components[index]) : 0;
            if (actual != required[index]) {
                return actual > required[index];
            }
        }
        return true;
    }

    private int documentCount(Long sourceId) {
        return jdbcClient.sql("SELECT count(*) FROM document WHERE source_id = :sourceId")
                .param("sourceId", sourceId)
                .query(Integer.class)
                .single();
    }

    private int chunkCount(Long documentId) {
        return jdbcClient.sql("""
                        SELECT count(*)
                        FROM embedding_chunk
                        WHERE doc_ref_type = 'DOCUMENT' AND doc_ref_id = :documentId
                        """)
                .param("documentId", documentId)
                .query(Integer.class)
                .single();
    }

    private List<String> chunkTexts(Long documentId) {
        return jdbcClient.sql("""
                        SELECT chunk_text
                        FROM embedding_chunk
                        WHERE doc_ref_type = 'DOCUMENT' AND doc_ref_id = :documentId
                        ORDER BY chunk_index
                        """)
                .param("documentId", documentId)
                .query(String.class)
                .list();
    }

    private List<EmbeddingChunkRecord> replaceAfterStart(
            CountDownLatch ready,
            CountDownLatch start,
            Long documentId,
            List<EmbeddingChunkRecord> chunks
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent replacement start was not released");
        }
        return embeddingChunkRepository.replaceForDocument(
                documentId, EMBEDDING_CONFIG_ID, chunks);
    }

    private void acquireReplacementLock(
            Connection connection,
            String docRefType,
            Long docRefId
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))
                """)) {
            statement.setString(1, EmbeddingChunkRepository.replacementLockIdentity(
                    docRefType, docRefId, EMBEDDING_CONFIG_ID));
            statement.execute();
        }
    }

    private void setIngestedAt(Long documentId, OffsetDateTime ingestedAt) {
        jdbcClient.sql("UPDATE document SET ingested_at = :ingestedAt WHERE id = :documentId")
                .param("documentId", documentId)
                .param("ingestedAt", ingestedAt)
                .update();
    }
}
