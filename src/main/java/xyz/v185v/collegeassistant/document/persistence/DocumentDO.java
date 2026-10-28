package xyz.v185v.collegeassistant.document.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.apache.ibatis.type.ObjectTypeHandler;

@TableName(value = "document", autoResultMap = true)
public class DocumentDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long sourceId;

    private String title;

    private String sourceUrl;

    private String sourceOrg;

    private OffsetDateTime publishedAt;

    private String contentHash;

    private String fileObjectKey;

    private OffsetDateTime ingestedAt;

    private String referenceType;

    private String externalSourceUrl;

    @TableField(typeHandler = ObjectTypeHandler.class)
    private UUID internalLocator;

    private String originalFilename;

    private String normalizedFilename;

    private String documentStatus;

    private String etag;

    private OffsetDateTime httpLastModified;

    private OffsetDateTime lastSeenAt;

    private OffsetDateTime lastCheckedAt;

    private String degree;

    private String college;

    private String campus;

    private String gradeRange;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public void setSourceId(Long sourceId) {
        this.sourceId = sourceId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getSourceOrg() {
        return sourceOrg;
    }

    public void setSourceOrg(String sourceOrg) {
        this.sourceOrg = sourceOrg;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(OffsetDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getFileObjectKey() {
        return fileObjectKey;
    }

    public void setFileObjectKey(String fileObjectKey) {
        this.fileObjectKey = fileObjectKey;
    }

    public OffsetDateTime getIngestedAt() {
        return ingestedAt;
    }

    public void setIngestedAt(OffsetDateTime ingestedAt) {
        this.ingestedAt = ingestedAt;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public void setReferenceType(String referenceType) {
        this.referenceType = referenceType;
    }

    public String getExternalSourceUrl() {
        return externalSourceUrl;
    }

    public void setExternalSourceUrl(String externalSourceUrl) {
        this.externalSourceUrl = externalSourceUrl;
    }

    public UUID getInternalLocator() {
        return internalLocator;
    }

    public void setInternalLocator(UUID internalLocator) {
        this.internalLocator = internalLocator;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getNormalizedFilename() {
        return normalizedFilename;
    }

    public void setNormalizedFilename(String normalizedFilename) {
        this.normalizedFilename = normalizedFilename;
    }

    public String getDocumentStatus() {
        return documentStatus;
    }

    public void setDocumentStatus(String documentStatus) {
        this.documentStatus = documentStatus;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public OffsetDateTime getHttpLastModified() {
        return httpLastModified;
    }

    public void setHttpLastModified(OffsetDateTime httpLastModified) {
        this.httpLastModified = httpLastModified;
    }

    public OffsetDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(OffsetDateTime lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public OffsetDateTime getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(OffsetDateTime lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }

    public String getDegree() {
        return degree;
    }

    public void setDegree(String degree) {
        this.degree = degree;
    }

    public String getCollege() {
        return college;
    }

    public void setCollege(String college) {
        this.college = college;
    }

    public String getCampus() {
        return campus;
    }

    public void setCampus(String campus) {
        this.campus = campus;
    }

    public String getGradeRange() {
        return gradeRange;
    }

    public void setGradeRange(String gradeRange) {
        this.gradeRange = gradeRange;
    }
}
