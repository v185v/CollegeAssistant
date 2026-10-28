package xyz.v185v.collegeassistant.ugc.persistence;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import xyz.v185v.collegeassistant.persistence.typehandler.JsonbStringTypeHandler;

@TableName(value = "ugc_post", autoResultMap = true)
public class UgcPostDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long authorId;
    private String title;
    private String content;
    @TableField(value = "tags", typeHandler = JsonbStringTypeHandler.class)
    private String tagsJson;
    private String relatedCourse;
    private String relatedLocation;
    private String status;
    private Long likes;
    private Long reports;
    private Long reviewedBy;
    private String rejectReason;
    private OffsetDateTime createdAt;
    private OffsetDateTime reviewedAt;
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public void setAuthorId(Long authorId) {
        this.authorId = authorId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getTagsJson() {
        return tagsJson;
    }

    public void setTagsJson(String tagsJson) {
        this.tagsJson = tagsJson;
    }

    public String getRelatedCourse() {
        return relatedCourse;
    }

    public void setRelatedCourse(String relatedCourse) {
        this.relatedCourse = relatedCourse;
    }

    public String getRelatedLocation() {
        return relatedLocation;
    }

    public void setRelatedLocation(String relatedLocation) {
        this.relatedLocation = relatedLocation;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getLikes() {
        return likes;
    }

    public void setLikes(Long likes) {
        this.likes = likes;
    }

    public Long getReports() {
        return reports;
    }

    public void setReports(Long reports) {
        this.reports = reports;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(Long reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(OffsetDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
