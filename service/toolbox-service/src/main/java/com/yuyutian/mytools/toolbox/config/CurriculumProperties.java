package com.yuyutian.mytools.toolbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 教材目录来源配置。
 *
 * @param repository 目录来源：`files` 读只读文件目录（默认，保持现有生产行为），`database` 读工具箱数据库
 */
@ConfigurationProperties(prefix = "toolbox.curriculum")
public record CurriculumProperties(
        @DefaultValue("files") String repository) {

    /**
     * 判断是否使用数据库作为目录来源。
     *
     * @return 使用数据库时为 true
     */
    public boolean usesDatabase() {
        return "database".equalsIgnoreCase(repository);
    }
}
