package xyz.v185v.collegeassistant.profile.persistence;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import xyz.v185v.collegeassistant.persistence.typehandler.JsonbStringTypeHandler;

@TableName("user_profile")
public class UserProfileDO {

    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    private String major;

    private Integer grade;

    private String campus;

    private String degree;

    private String college;

    private String lifecycleStage;

    @TableField(value = "course_records", typeHandler = JsonbStringTypeHandler.class)
    private String courseRecordsJson;

    private OffsetDateTime updatedAt;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getMajor() {
        return major;
    }

    public void setMajor(String major) {
        this.major = major;
    }

    public Integer getGrade() {
        return grade;
    }

    public void setGrade(Integer grade) {
        this.grade = grade;
    }

    public String getCampus() {
        return campus;
    }

    public void setCampus(String campus) {
        this.campus = campus;
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

    public String getLifecycleStage() {
        return lifecycleStage;
    }

    public void setLifecycleStage(String lifecycleStage) {
        this.lifecycleStage = lifecycleStage;
    }

    public String getCourseRecordsJson() {
        return courseRecordsJson;
    }

    public void setCourseRecordsJson(String courseRecordsJson) {
        this.courseRecordsJson = courseRecordsJson;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
