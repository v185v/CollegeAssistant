package xyz.v185v.collegeassistant.admin.system.persistence;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 管理数据库健康服务使用的只读投影。 */
@Mapper
public interface SystemDatabaseMapper {

    @Select("SELECT current_setting('server_version')")
    String postgresVersion();

    @Select("SELECT installed_version FROM pg_available_extensions WHERE name = 'vector'")
    String pgvectorVersion();

    String flywayVersion();

    @Select("SELECT pg_database_size(current_database())")
    long databaseSizeBytes();

    long indexSizeBytes();

    TableCountsRow tableCounts();

    int activeConnections();

    List<RecentIngestionFailureRow> recentIngestionFailures();

    List<DocumentObjectRow> documentsMissingObjects();

    List<Long> activeDocumentsMissingVectors();

    List<VectorReferenceRow> orphanVectorReferences();

    List<String> referencedObjectKeys();

    List<Long> staleIngestRuns(@Param("timeout") String timeout);

    boolean ingestItemExists(
            @Param("runId") long runId,
            @Param("itemId") long itemId);

    record DocumentObjectRow(Long id, String objectKey) {
    }

    record VectorReferenceRow(String referenceType, Long referenceId) {
    }

    record TableCountsRow(
            Long users,
            Long source,
            Long document,
            Long embeddingChunk,
            Long ingestRun,
            Long ugcPost,
            Long adminJob,
            Long adminAuditLog
    ) {
    }

    record RecentIngestionFailureRow(
            Long id,
            Long sourceId,
            String status,
            String errorCode,
            OffsetDateTime finishedAt
    ) {
    }
}
