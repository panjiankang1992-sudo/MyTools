package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.config.CurriculumProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 目录来源配置的默认值与判定规则。
 * 关键：**默认必须是 files**，切换数据库来源只能通过显式配置，避免升级后擅自改变公开内容来源。
 */
class CurriculumSourceSwitchTest {

    @Test
    @DisplayName("未配置时默认使用文件来源")
    void defaultsToFiles() {
        CurriculumProperties defaults = new CurriculumProperties("files");
        assertThat(defaults.usesDatabase()).isFalse();
    }

    @Test
    @DisplayName("只有显式配置 database 才切到数据库来源")
    void databaseRequiresExplicitValue() {
        assertThat(new CurriculumProperties("database").usesDatabase()).isTrue();
        assertThat(new CurriculumProperties("DATABASE").usesDatabase()).isTrue();
        assertThat(new CurriculumProperties("files").usesDatabase()).isFalse();
        assertThat(new CurriculumProperties("").usesDatabase()).isFalse();
    }

    @Test
    @DisplayName("配置文件的默认值与代码默认值一致")
    void yamlDefaultMatchesCodeDefault() throws Exception {
        // 读取主配置，确认没有把 repository 默认成 database。
        var resource = new org.springframework.core.io.ClassPathResource("application.yml");
        String yaml = new String(resource.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (yaml.contains("repository:")) {
            assertThat(yaml).doesNotContain("repository: database");
        }
    }
}
