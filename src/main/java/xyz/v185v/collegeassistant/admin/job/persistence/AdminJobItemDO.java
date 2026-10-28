package xyz.v185v.collegeassistant.admin.job.persistence;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import xyz.v185v.collegeassistant.persistence.typehandler.JsonbStringTypeHandler;

@TableName(value = "admin_job_item", autoResultMap = true)
public class AdminJobItemDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long jobId;

    private String itemKey;

    private String itemType;

    private String targetType;

    private Long targetId;

    private String status;

    private Integer retryCount;

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

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public String getItemKey() {
        return itemKey;
    }

    public void setItemKey(String itemKey) {
        this.itemKey = itemKey;
    }

    public String getItemType() {
        return itemType;
    }

    public void setItemType(String itemType) {
        this.itemType = itemType;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
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
