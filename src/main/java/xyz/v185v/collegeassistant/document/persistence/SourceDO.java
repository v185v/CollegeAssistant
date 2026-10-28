package xyz.v185v.collegeassistant.document.persistence;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import xyz.v185v.collegeassistant.persistence.typehandler.JsonbStringTypeHandler;

@TableName(value = "source", autoResultMap = true)
public class SourceDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;

    private String baseUrl;

    private String type;

    private Boolean enabled;

    private String connectorType;

    private String deletionStatus;

    private String scheduleType;

    private String scheduleZone;

    private String cronExpression;

    private Boolean sameHostOnly;

    private Integer maxDepth;

    private Integer maxPages;

    private Integer maxConcurrency;

    @TableField(value = "include_patterns", typeHandler = JsonbStringTypeHandler.class)
    private String includePatternsJson;

    @TableField(value = "exclude_patterns", typeHandler = JsonbStringTypeHandler.class)
    private String excludePatternsJson;

    private OffsetDateTime nextRunAt;

    private OffsetDateTime lastRunAt;

    private Long createdBy;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getConnectorType() {
        return connectorType;
    }

    public void setConnectorType(String connectorType) {
        this.connectorType = connectorType;
    }

    public String getDeletionStatus() {
        return deletionStatus;
    }

    public void setDeletionStatus(String deletionStatus) {
        this.deletionStatus = deletionStatus;
    }

    public String getScheduleType() {
        return scheduleType;
    }

    public void setScheduleType(String scheduleType) {
        this.scheduleType = scheduleType;
    }

    public String getScheduleZone() {
        return scheduleZone;
    }

    public void setScheduleZone(String scheduleZone) {
        this.scheduleZone = scheduleZone;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public Boolean getSameHostOnly() {
        return sameHostOnly;
    }

    public void setSameHostOnly(Boolean sameHostOnly) {
        this.sameHostOnly = sameHostOnly;
    }

    public Integer getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(Integer maxDepth) {
        this.maxDepth = maxDepth;
    }

    public Integer getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(Integer maxPages) {
        this.maxPages = maxPages;
    }

    public Integer getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(Integer maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public String getIncludePatternsJson() {
        return includePatternsJson;
    }

    public void setIncludePatternsJson(String includePatternsJson) {
        this.includePatternsJson = includePatternsJson;
    }

    public String getExcludePatternsJson() {
        return excludePatternsJson;
    }

    public void setExcludePatternsJson(String excludePatternsJson) {
        this.excludePatternsJson = excludePatternsJson;
    }

    public OffsetDateTime getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(OffsetDateTime nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public OffsetDateTime getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(OffsetDateTime lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
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
