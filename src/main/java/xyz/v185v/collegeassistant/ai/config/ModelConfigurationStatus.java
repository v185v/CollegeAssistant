package xyz.v185v.collegeassistant.ai.config;

/** 一个不可变模型配置版本的持久化生命周期状态。 */
public enum ModelConfigurationStatus {
    ACTIVE,
    PENDING,
    FAILED,
    RETIRED
}
