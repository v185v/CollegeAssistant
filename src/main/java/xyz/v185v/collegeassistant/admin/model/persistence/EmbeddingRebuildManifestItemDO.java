package xyz.v185v.collegeassistant.admin.model.persistence;

import java.time.OffsetDateTime;

public class EmbeddingRebuildManifestItemDO {

    private Long jobId;
    private String targetType;
    private Long targetId;
    private String contentVersion;
    private Integer chunkCount;
    private String chunkTextChecksum;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getContentVersion() {
        return contentVersion;
    }

    public void setContentVersion(String contentVersion) {
        this.contentVersion = contentVersion;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getChunkTextChecksum() {
        return chunkTextChecksum;
    }

    public void setChunkTextChecksum(String chunkTextChecksum) {
        this.chunkTextChecksum = chunkTextChecksum;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
