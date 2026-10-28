package xyz.v185v.collegeassistant.admin.system;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import xyz.v185v.collegeassistant.admin.system.DatabaseOverviewDto.ModelStatus;
import xyz.v185v.collegeassistant.admin.system.DatabaseOverviewDto.RecentIngestionFailure;
import xyz.v185v.collegeassistant.admin.system.persistence.SystemDatabaseMapper;
import xyz.v185v.collegeassistant.auth.AuthPrincipal;
import xyz.v185v.collegeassistant.auth.UserRole;
import xyz.v185v.collegeassistant.common.BusinessException;
import xyz.v185v.collegeassistant.common.ErrorCode;
import xyz.v185v.collegeassistant.config.AiProperties;
import xyz.v185v.collegeassistant.storage.ObjectStorage;

/** 执行固定且有界的一组脱敏数据库健康查询。 */
@Service
public class DatabaseOverviewService {

    private final SystemDatabaseMapper mapper;
    private final ObjectStorage objectStorage;
    private final AiProperties aiProperties;

    public DatabaseOverviewService(
            SystemDatabaseMapper mapper,
            ObjectStorage objectStorage,
            AiProperties aiProperties
    ) {
        this.mapper = mapper;
        this.objectStorage = objectStorage;
        this.aiProperties = aiProperties;
    }

    @Transactional(readOnly = true, timeout = 5)
    public DatabaseOverviewDto getOverview(AuthPrincipal actor) {
        requireAdmin(actor);
        return new DatabaseOverviewDto(
                scalarString(mapper.postgresVersion(), "unknown"),
                pgvectorVersion(),
                flywayVersion(),
                mapper.databaseSizeBytes(),
                mapper.indexSizeBytes(),
                tableCounts(),
                mapper.activeConnections(),
                storageAvailable(),
                modelStatus(),
                recentIngestionFailures());
    }

    private String pgvectorVersion() {
        return scalarString(mapper.pgvectorVersion(), "unavailable");
    }

    private String flywayVersion() {
        return scalarString(mapper.flywayVersion(), "unavailable");
    }

    private String scalarString(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private Map<String, Long> tableCounts() {
        SystemDatabaseMapper.TableCountsRow row = mapper.tableCounts();
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("users", row.users());
        counts.put("source", row.source());
        counts.put("document", row.document());
        counts.put("embedding_chunk", row.embeddingChunk());
        counts.put("ingest_run", row.ingestRun());
        counts.put("ugc_post", row.ugcPost());
        counts.put("admin_job", row.adminJob());
        counts.put("admin_audit_log", row.adminAuditLog());
        return counts;
    }

    private boolean storageAvailable() {
        try {
            return objectStorage.isAvailable();
        } catch (BusinessException exception) {
            return false;
        }
    }

    private ModelStatus modelStatus() {
        AiProperties.Embedding embedding = aiProperties.getEmbedding();
        String provider = embedding == null ? null : embedding.getProvider();
        String model = embedding == null ? null : embedding.getModel();
        int dimension = embedding == null ? 0 : embedding.getDimension();
        boolean configured = provider != null && !provider.isBlank()
                && model != null && !model.isBlank()
                && dimension > 0;
        return new ModelStatus(provider, model, configured, dimension);
    }

    private List<RecentIngestionFailure> recentIngestionFailures() {
        return mapper.recentIngestionFailures().stream()
                .map(row -> new RecentIngestionFailure(
                        row.id(),
                        row.sourceId(),
                        row.status(),
                        row.errorCode(),
                        row.finishedAt()))
                .toList();
    }

    private void requireAdmin(AuthPrincipal actor) {
        if (actor == null || !UserRole.ADMIN.name().equals(actor.role())) {
            throw new BusinessException(ErrorCode.ADMIN_REQUIRED, "Administrator role required");
        }
    }
}
