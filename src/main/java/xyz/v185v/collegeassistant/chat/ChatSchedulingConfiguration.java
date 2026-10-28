package xyz.v185v.collegeassistant.chat;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 启用聊天草稿定时清理任务。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ChatSchedulingConfiguration {
}
