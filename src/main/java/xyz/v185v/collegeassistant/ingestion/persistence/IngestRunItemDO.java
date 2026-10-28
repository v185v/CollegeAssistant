package xyz.v185v.collegeassistant.ingestion.persistence;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import xyz.v185v.collegeassistant.persistence.typehandler.JsonbStringTypeHandler;

@TableName(value = "ingest_run_item", autoResultMap = true)
public class IngestRunItemDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long runId;

    private String itemKey;

    private String itemKind;

    private String status;

    private Long documentId;

    private Integer retryCount;

    private OffsetDateTime nextAttemptAt;

    private String errorCode;

    private String errorSummary;

    @TableField(value = "checkpoint", typeHandler = JsonbStringTypeHandler.class)
    private String checkpointJson;

    private Integer checkpointAttempt;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public String getItemKey() {
        return itemKey;
    }

    public void setItemKey(String itemKey) {
        this.itemKey = itemKey;
    }

    public String getItemKind() {
        return itemKind;
    }

    public void setItemKind(String itemKind) {
        this.itemKind = itemKind;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public OffsetDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(OffsetDateTime nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public void setErrorSummary(String errorSummary) {
        this.errorSummary = errorSummary;
    }

    public String getCheckpointJson() {
        return checkpointJson;
    }

    public void setCheckpointJson(String checkpointJson) {
        this.checkpointJson = checkpointJson;
    }

    public Integer getCheckpointAttempt() {
        return checkpointAttempt;
    }

    public void setCheckpointAttempt(Integer checkpointAttempt) {
        this.checkpointAttempt = checkpointAttempt;
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
