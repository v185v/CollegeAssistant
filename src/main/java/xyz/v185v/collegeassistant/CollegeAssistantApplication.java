package xyz.v185v.collegeassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** 智能校园助手后端应用入口。 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CollegeAssistantApplication {

    /**
     * 启动 Spring Boot 应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(CollegeAssistantApplication.class, args);
    }

}
