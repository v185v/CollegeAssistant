package xyz.v185v.collegeassistant;

import static xyz.v185v.collegeassistant.testsupport.PersistenceMigrationAssertions.assertFinalArchitectureGuards;
import static xyz.v185v.collegeassistant.testsupport.PersistenceMigrationAssertions.assertMapperBacked;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditRepository;
import xyz.v185v.collegeassistant.admin.backup.DatabaseBackupRepository;
import xyz.v185v.collegeassistant.admin.job.AdminJobRepository;
import xyz.v185v.collegeassistant.admin.model.EmbeddingRebuildManifestRepository;
import xyz.v185v.collegeassistant.admin.system.ConsistencyScanService;
import xyz.v185v.collegeassistant.admin.system.DatabaseOverviewService;
import xyz.v185v.collegeassistant.auth.UserRepository;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunItemRepository;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;
import xyz.v185v.collegeassistant.profile.ProfileRepository;
import xyz.v185v.collegeassistant.chat.ChatRepository;
import xyz.v185v.collegeassistant.ugc.UgcRepository;
import xyz.v185v.collegeassistant.ai.config.ModelConfigurationRepository;

class PersistenceMigrationContractTest {
    @Test
    void productionPersistenceHasOnlyApprovedJdbcExceptionsAndSafeMapperXml() throws IOException {
        assertFinalArchitectureGuards();
    }

    @Test
    void profileRepositoryUsesMapper() throws IOException {
        assertMapperBacked(ProfileRepository.class);
    }

    @Test
    void embeddingRebuildManifestRepositoryUsesMapper() throws IOException {
        assertMapperBacked(EmbeddingRebuildManifestRepository.class);
    }

    @Test
    void adminAuditRepositoryUsesMapper() throws IOException {
        assertMapperBacked(AdminAuditRepository.class);
    }

    @Test
    void adminJobRepositoryUsesMapper() throws IOException {
        assertMapperBacked(AdminJobRepository.class);
    }

    @Test
    void databaseBackupRepositoryUsesMapper() throws IOException {
        assertMapperBacked(DatabaseBackupRepository.class);
    }

    @Test
    void userRepositoryUsesMapper() throws IOException {
        assertMapperBacked(UserRepository.class);
    }

    @Test
    void sourceRepositoryUsesMapper() throws IOException {
        assertMapperBacked(SourceRepository.class);
    }

    @Test
    void documentRepositoryUsesMapper() throws IOException {
        assertMapperBacked(DocumentRepository.class);
    }

    @Test
    void embeddingChunkRepositoryUsesMapper() throws IOException {
        assertMapperBacked(EmbeddingChunkRepository.class);
    }

    @Test
    void ingestRunRepositoryUsesMapper() throws IOException {
        assertMapperBacked(IngestRunRepository.class);
    }

    @Test
    void ingestRunItemRepositoryUsesMapper() throws IOException {
        assertMapperBacked(IngestRunItemRepository.class);
    }

    @Test
    void chatRepositoryUsesMapper() throws IOException {
        assertMapperBacked(ChatRepository.class);
    }

    @Test
    void ugcRepositoryUsesMapper() throws IOException {
        assertMapperBacked(UgcRepository.class);
    }

    @Test
    void modelConfigurationRepositoryUsesMapper() throws IOException {
        assertMapperBacked(ModelConfigurationRepository.class);
    }

    @Test
    void consistencyScanServiceUsesMapper() throws IOException {
        assertMapperBacked(ConsistencyScanService.class);
    }

    @Test
    void databaseOverviewServiceUsesMapper() throws IOException {
        assertMapperBacked(DatabaseOverviewService.class);
    }
}
