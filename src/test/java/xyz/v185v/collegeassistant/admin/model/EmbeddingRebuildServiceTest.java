package xyz.v185v.collegeassistant.admin.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import xyz.v185v.collegeassistant.admin.job.AdminJobDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobExecutionContext;
import xyz.v185v.collegeassistant.admin.job.AdminJobItemDto;
import xyz.v185v.collegeassistant.admin.job.AdminJobRepository;
import xyz.v185v.collegeassistant.admin.job.AdminJobStatus;
import xyz.v185v.collegeassistant.admin.job.AdminJobType;
import xyz.v185v.collegeassistant.admin.job.LeaseGuardedJobOperations;
import xyz.v185v.collegeassistant.ai.EffectiveEmbeddingContext;
import xyz.v185v.collegeassistant.ai.EmbeddingGateway;
import xyz.v185v.collegeassistant.ai.ModelRegistry;
import xyz.v185v.collegeassistant.ai.ModelRuntimeFactory;
import xyz.v185v.collegeassistant.ai.config.ModelConfiguration;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationRepository;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationStatus;
import xyz.v185v.collegeassistant.ai.config.ModelKind;
import xyz.v185v.collegeassistant.ai.config.ModelSecretCipher;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;
import xyz.v185v.collegeassistant.ingestion.parsing.DocumentParser;
import xyz.v185v.collegeassistant.ingestion.parsing.TextChunker;
import xyz.v185v.collegeassistant.storage.ObjectStorage;
import xyz.v185v.collegeassistant.ugc.UgcRepository;

class EmbeddingRebuildServiceTest {

    private static final AuthPrincipal ADMIN = new AuthPrincipal(3L, "admin", "ADMIN");

    @Test
    void startsARebuildUnderTheMaintenanceLockWithAnImmutableEmptyManifest() {
        AdminJobRepository jobs = mock(AdminJobRepository.class);
        ModelConfigurationRepository configurations = mock(ModelConfigurationRepository.class);
        EmbeddingRebuildManifestRepository manifest = mock(EmbeddingRebuildManifestRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        UgcRepository ugc = mock(UgcRepository.class);
        when(configurations.lockById(19L)).thenReturn(Optional.of(candidate(19L)));
        when(jobs.hasBlockingEmbeddingRebuildForPendingConfiguration()).thenReturn(false);
        when(jobs.hasConflictingEmbeddingRebuildWork()).thenReturn(false);
        when(jobs.findLatestEmbeddingRebuild(19L)).thenReturn(Optional.empty());
        when(documents.findActiveForEmbeddingRebuild()).thenReturn(List.of());
        when(ugc.findApprovedForEmbeddingRebuild()).thenReturn(List.of());
        AdminJobDto job = job(41L, 19L, AdminJobStatus.QUEUED);
        when(jobs.create(
                AdminJobType.EMBEDDING_REBUILD,
                "EMBEDDING_CONFIGURATION",
                19L,
                ADMIN.userId(),
                Map.of("embeddingConfigurationId", 19L, "targetCount", 0)))
                .thenReturn(job);

        EmbeddingRebuildService service = service(
                jobs, configurations, manifest, documents, ugc);

        assertThat(service.start(ADMIN, 19L)).isEqualTo(job);

        verify(jobs).lockEmbeddingMaintenanceState();
        verify(manifest).insertBatch(41L, List.of());
    }

    @Test
    void refusesToStartWhileAnotherMutationCanChangeTheSnapshot() {
        AdminJobRepository jobs = mock(AdminJobRepository.class);
        ModelConfigurationRepository configurations = mock(ModelConfigurationRepository.class);
        when(configurations.lockById(19L)).thenReturn(Optional.of(candidate(19L)));
        when(jobs.hasBlockingEmbeddingRebuildForPendingConfiguration()).thenReturn(false);
        when(jobs.hasConflictingEmbeddingRebuildWork()).thenReturn(true);

        EmbeddingRebuildService service = service(
                jobs, configurations, mock(EmbeddingRebuildManifestRepository.class),
                mock(DocumentRepository.class), mock(UgcRepository.class));

        assertThatThrownBy(() -> service.start(ADMIN, 19L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("active ingestion");

        verify(jobs, never()).create(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                anyMap());
    }

    @ParameterizedTest
    @EnumSource(value = AdminJobStatus.class, names = {
            "SUCCEEDED", "FAILED", "PARTIAL_SUCCESS", "CANCELLED"
    })
    void discardRetiresCandidatesAfterEveryTerminalRebuildState(AdminJobStatus terminalStatus) {
        AdminJobRepository jobs = mock(AdminJobRepository.class);
        ModelConfigurationRepository configurations = mock(ModelConfigurationRepository.class);
        when(configurations.lockById(19L)).thenReturn(Optional.of(candidate(19L)));
        when(jobs.findLatestEmbeddingRebuild(19L)).thenReturn(Optional.of(
                job(41L, 19L, terminalStatus)));
        AdminJobDto cleanup = job(42L, 19L, AdminJobStatus.QUEUED);
        when(jobs.create(
                AdminJobType.INDEX_CLEANUP,
                "EMBEDDING_CONFIGURATION",
                19L,
                ADMIN.userId(),
                Map.of("embeddingConfigurationId", 19L)))
                .thenReturn(cleanup);

        EmbeddingRebuildService service = service(
                jobs, configurations, mock(EmbeddingRebuildManifestRepository.class),
                mock(DocumentRepository.class), mock(UgcRepository.class));

        assertThat(service.discard(ADMIN, 19L)).isEqualTo(cleanup);

        verify(jobs).lockEmbeddingMaintenanceState();
        verify(configurations).retirePendingEmbedding(
                19L, "Embedding rebuild discarded by administrator");
    }

    @Test
    void discardCancelsAQueuedRebuildBeforeRetiringItsCandidate() {
        AdminJobRepository jobs = mock(AdminJobRepository.class);
        ModelConfigurationRepository configurations = mock(ModelConfigurationRepository.class);
        when(configurations.lockById(19L)).thenReturn(Optional.of(candidate(19L)));
        when(jobs.findLatestEmbeddingRebuild(19L)).thenReturn(Optional.of(
                job(41L, 19L, AdminJobStatus.QUEUED)));
        when(jobs.cancelQueuedEmbeddingRebuild(19L)).thenReturn(true);
        AdminJobDto cleanup = job(42L, 19L, AdminJobStatus.QUEUED);
        when(jobs.create(
                AdminJobType.INDEX_CLEANUP,
                "EMBEDDING_CONFIGURATION",
                19L,
                ADMIN.userId(),
                Map.of("embeddingConfigurationId", 19L)))
                .thenReturn(cleanup);

        EmbeddingRebuildService service = service(
                jobs, configurations, mock(EmbeddingRebuildManifestRepository.class),
                mock(DocumentRepository.class), mock(UgcRepository.class));

        assertThat(service.discard(ADMIN, 19L)).isEqualTo(cleanup);

        verify(jobs).cancelQueuedEmbeddingRebuild(19L);
        verify(configurations).retirePendingEmbedding(
                19L, "Embedding rebuild discarded by administrator");
    }

    @Test
    void activationQueuesCleanupForTheRetiredNonBootstrapVectorSpace() {
        AdminJobRepository jobs = mock(AdminJobRepository.class);
        ModelConfigurationRepository configurations = mock(ModelConfigurationRepository.class);
        EmbeddingRebuildManifestRepository manifest = mock(EmbeddingRebuildManifestRepository.class);
        ModelRegistry registry = mock(ModelRegistry.class);
        EffectiveEmbeddingContext context = new EffectiveEmbeddingContext(19L, gateway());
        when(configurations.lockById(19L)).thenReturn(Optional.of(candidate(19L)));
        when(configurations.findActive(ModelKind.EMBEDDING)).thenReturn(Optional.of(active(7L)));
        when(jobs.findLatestEmbeddingRebuild(19L)).thenReturn(Optional.of(
                job(41L, 19L, AdminJobStatus.SUCCEEDED)));
        when(manifest.findByJobId(41L)).thenReturn(List.of());
        when(manifest.countIncomplete(41L)).thenReturn(0L);
        when(registry.pendingEmbedding()).thenReturn(Optional.of(context));

        EmbeddingRebuildService service = service(
                jobs, configurations, manifest, mock(DocumentRepository.class),
                mock(UgcRepository.class), registry);

        service.activate(ADMIN, 19L);

        verify(configurations).activateEmbedding(19L);
        verify(jobs).create(
                AdminJobType.INDEX_CLEANUP,
                "EMBEDDING_CONFIGURATION",
                7L,
                ADMIN.userId(),
                Map.of("embeddingConfigurationId", 7L));
        verify(registry).activateEmbedding(context);
    }

    @Test
    void executePersistsExplicitManifestVerificationAfterValidatingAnEmptySnapshot() {
        AdminJobRepository jobs = mock(AdminJobRepository.class);
        ModelConfigurationRepository configurations = mock(ModelConfigurationRepository.class);
        EmbeddingRebuildManifestRepository manifest = mock(EmbeddingRebuildManifestRepository.class);
        EmbeddingChunkRepository chunks = mock(EmbeddingChunkRepository.class);
        ModelRegistry registry = mock(ModelRegistry.class);
        EffectiveEmbeddingContext embedding = new EffectiveEmbeddingContext(19L, gateway());
        AdminJobExecutionContext context = mock(AdminJobExecutionContext.class);
        LeaseGuardedJobOperations operations = mock(LeaseGuardedJobOperations.class);
        AdminJobItemDto verification = new AdminJobItemDto(
                71L, 41L, "manifest:verified", "EMBEDDING_REBUILD_MANIFEST",
                "EMBEDDING_CONFIGURATION", 19L, AdminJobStatus.QUEUED,
                0, null, null, Map.of(), 0,
                OffsetDateTime.parse("2026-07-26T00:00:00Z"),
                OffsetDateTime.parse("2026-07-26T00:00:00Z"));
        when(configurations.findById(19L)).thenReturn(Optional.of(candidate(19L)));
        when(registry.pendingEmbedding()).thenReturn(Optional.of(embedding));
        when(manifest.findByJobId(41L)).thenReturn(List.of());
        when(chunks.findTargetsByConfiguration(19L)).thenReturn(List.of());
        when(context.job()).thenReturn(job(41L, 19L, AdminJobStatus.RUNNING));
        when(context.upsertItem(
                "manifest:verified", "EMBEDDING_REBUILD_MANIFEST",
                "EMBEDDING_CONFIGURATION", 19L)).thenReturn(verification);
        when(context.inLeaseTransaction(any())).thenAnswer(invocation -> {
            Function<LeaseGuardedJobOperations, Object> work = invocation.getArgument(0);
            return work.apply(operations);
        });

        EmbeddingRebuildService service = service(
                jobs, configurations, manifest, mock(DocumentRepository.class),
                mock(UgcRepository.class), registry, chunks);

        service.execute(context);

        ArgumentCaptor<Map<String, Object>> checkpoint = ArgumentCaptor.forClass(Map.class);
        verify(operations).checkpoint(
                eq(71L), eq(AdminJobStatus.SUCCEEDED), checkpoint.capture());
        assertThat(checkpoint.getValue()).containsEntry("manifestVerified", true)
                .containsEntry("targetCount", 0);
    }

    private EmbeddingRebuildService service(
            AdminJobRepository jobs,
            ModelConfigurationRepository configurations,
            EmbeddingRebuildManifestRepository manifest,
            DocumentRepository documents,
            UgcRepository ugc
    ) {
        return service(jobs, configurations, manifest, documents, ugc, mock(ModelRegistry.class));
    }

    private EmbeddingRebuildService service(
            AdminJobRepository jobs,
            ModelConfigurationRepository configurations,
            EmbeddingRebuildManifestRepository manifest,
            DocumentRepository documents,
            UgcRepository ugc,
            ModelRegistry registry
    ) {
        return service(
                jobs, configurations, manifest, documents, ugc, registry,
                mock(EmbeddingChunkRepository.class));
    }

    private EmbeddingRebuildService service(
            AdminJobRepository jobs,
            ModelConfigurationRepository configurations,
            EmbeddingRebuildManifestRepository manifest,
            DocumentRepository documents,
            UgcRepository ugc,
            ModelRegistry registry,
            EmbeddingChunkRepository chunks
    ) {
        return new EmbeddingRebuildService(
                jobs,
                configurations,
                manifest,
                documents,
                ugc,
                chunks,
                mock(ObjectStorage.class),
                mock(DocumentParser.class),
                new TextChunker(),
                mock(ModelSecretCipher.class),
                mock(ModelRuntimeFactory.class),
                registry);
    }

    private ModelConfiguration candidate(long id) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-26T00:00:00Z");
        return new ModelConfiguration(
                id, "embedding-" + id, ModelKind.EMBEDDING,
                "openai", "text-embedding-3-small", "https://api.openai.com/v1",
                null, 1536, null, null, null,
                ModelConfigurationStatus.PENDING, ADMIN.userId(), null, null, now, now);
    }

    private ModelConfiguration active(long id) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-26T00:00:00Z");
        return new ModelConfiguration(
                id, "embedding-" + id, ModelKind.EMBEDDING,
                "openai", "text-embedding-3-small", "https://api.openai.com/v1",
                null, 1536, null, null, null,
                ModelConfigurationStatus.ACTIVE, ADMIN.userId(), now, null, now, now);
    }

    private EmbeddingGateway gateway() {
        return new EmbeddingGateway() {
            @Override
            public List<Double> embed(String text) {
                return List.of(1.0);
            }

            @Override
            public List<List<Double>> embedAll(List<String> texts) {
                return texts.stream().map(ignored -> List.of(1.0)).toList();
            }

            @Override
            public int dimension() {
                return 1536;
            }
        };
    }

    private AdminJobDto job(long id, long configurationId, AdminJobStatus status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-26T00:00:00Z");
        return new AdminJobDto(
                id, AdminJobType.EMBEDDING_REBUILD, status,
                "EMBEDDING_CONFIGURATION", configurationId, ADMIN.userId(),
                0, null, null, null, null, null, null, null, null,
                Map.of(), now, now, List.of());
    }
}
