package xyz.v185v.collegeassistant.document.persistence;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("embedding_chunk")
public class EmbeddingChunkDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long embeddingConfigId;

    private String docRefType;

    private Long docRefId;

    private Integer chunkIndex;

    private String chunkText;

    @TableField(exist = false)
    private String embeddingText;

    private String sourceType;

    private String sourceUrl;

    private String title;

    private String sourceOrg;

    private OffsetDateTime publishedAt;

    private String degree;

    private String college;

    private String campus;

    private String gradeRange;

    @TableField(exist = false)
    private String metaJson;

    private OffsetDateTime createdAt;

    @TableField(exist = false)
    private String documentReferenceType;

    @TableField(exist = false)
    private Long documentId;

    @TableField(exist = false)
    private String externalSourceUrl;

    @TableField(exist = false)
    private Double score;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getEmbeddingConfigId() {
        return embeddingConfigId;
    }

    public void setEmbeddingConfigId(Long embeddingConfigId) {
        this.embeddingConfigId = embeddingConfigId;
    }

    public String getDocRefType() {
        return docRefType;
    }

    public void setDocRefType(String docRefType) {
        this.docRefType = docRefType;
    }

    public Long getDocRefId() {
        return docRefId;
    }

    public void setDocRefId(Long docRefId) {
        this.docRefId = docRefId;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getChunkText() {
        return chunkText;
    }

    public void setChunkText(String chunkText) {
        this.chunkText = chunkText;
    }

    public String getEmbeddingText() {
        return embeddingText;
    }

    public void setEmbeddingText(String embeddingText) {
        this.embeddingText = embeddingText;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
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

    public String getMetaJson() {
        return metaJson;
    }

    public void setMetaJson(String metaJson) {
        this.metaJson = metaJson;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getDocumentReferenceType() {
        return documentReferenceType;
    }

    public void setDocumentReferenceType(String documentReferenceType) {
        this.documentReferenceType = documentReferenceType;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public String getExternalSourceUrl() {
        return externalSourceUrl;
    }

    public void setExternalSourceUrl(String externalSourceUrl) {
        this.externalSourceUrl = externalSourceUrl;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }
}
