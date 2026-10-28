package xyz.v185v.collegeassistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import xyz.v185v.collegeassistant.admin.AdminUgcController;
import xyz.v185v.collegeassistant.admin.audit.AdminAuditRepository;
import xyz.v185v.collegeassistant.admin.backup.persistence.DatabaseBackupMapper;
import xyz.v185v.collegeassistant.admin.job.AdminJobLeaseGuard;
import xyz.v185v.collegeassistant.admin.job.AdminJobRepository;
import xyz.v185v.collegeassistant.admin.model.persistence.EmbeddingRebuildManifestMapper;
import xyz.v185v.collegeassistant.admin.system.persistence.SystemDatabaseMapper;
import xyz.v185v.collegeassistant.ai.EmbeddingGateway;
import xyz.v185v.collegeassistant.ai.config.persistence.ModelConfigurationMapper;
import xyz.v185v.collegeassistant.auth.AuthController;
import xyz.v185v.collegeassistant.auth.UserRepository;
import xyz.v185v.collegeassistant.chat.ChatRepository;
import xyz.v185v.collegeassistant.document.DocumentRepository;
import xyz.v185v.collegeassistant.document.EmbeddingChunkRepository;
import xyz.v185v.collegeassistant.document.SourceRepository;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunRepository;
import xyz.v185v.collegeassistant.ingestion.persistence.IngestRunItemRepository;
import xyz.v185v.collegeassistant.ingestion.IngestRunLeaseGuard;
import xyz.v185v.collegeassistant.profile.ProfileController;
import xyz.v185v.collegeassistant.profile.ProfileRepository;
import xyz.v185v.collegeassistant.rag.StreamingGenerationGateway;
import xyz.v185v.collegeassistant.storage.ObjectStorage;
import xyz.v185v.collegeassistant.ugc.UgcController;
import xyz.v185v.collegeassistant.ugc.UgcRepository;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;

@SpringBootTest
@ActiveProfiles("test")
class CollegeAssistantApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ProfileRepository profileRepository;

    @MockitoBean
    private UgcRepository ugcRepository;

    @MockitoBean
    private SourceRepository sourceRepository;

    @MockitoBean
    private DocumentRepository documentRepository;

    @MockitoBean
    private EmbeddingChunkRepository embeddingChunkRepository;

    @MockitoBean
    private IngestRunRepository ingestRunRepository;

    @MockitoBean
    private IngestRunItemRepository ingestRunItemRepository;

    @MockitoBean
    private IngestRunLeaseGuard ingestRunLeaseGuard;

    @MockitoBean
    private AdminJobRepository adminJobRepository;

    @MockitoBean
    private AdminJobLeaseGuard adminJobLeaseGuard;

    @MockitoBean
    private AdminAuditRepository adminAuditRepository;

    @MockitoBean
    private DatabaseBackupMapper databaseBackupMapper;

    @MockitoBean
    private EmbeddingRebuildManifestMapper embeddingRebuildManifestMapper;

    @MockitoBean
    private ModelConfigurationMapper modelConfigurationMapper;

    @MockitoBean
    private SystemDatabaseMapper systemDatabaseMapper;

    @MockitoBean
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private JdbcClient jdbcClient;

    @MockitoBean
    private ChatRepository chatRepository;

    @Test
    void contextLoads() {
        assertThat(applicationContext.getBeansOfType(AuthController.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(ProfileController.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(UgcController.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(AdminUgcController.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(ObjectStorage.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(ChatModel.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(StreamingChatModel.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(EmbeddingModel.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(EmbeddingGateway.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(StreamingGenerationGateway.class))
                .hasSize(1);
    }

}
