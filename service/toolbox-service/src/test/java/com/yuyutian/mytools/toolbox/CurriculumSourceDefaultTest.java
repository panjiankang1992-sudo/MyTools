package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.config.CurriculumProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * C02：目录来源的**默认值**及其后果。
 *
 * 默认必须是 `files`——切换公开内容来源是**运维的显式决定**，
 * 不能因为升级而自动改变（设计 §8）。
 *
 * 但这也意味着一个容易被误解的事实：**在默认配置下，管理端发布写入数据库，
 * 公开目录仍读文件，新发布的教材不会对外可见**。这里把这个默认值锁住，
 * 并由启动日志负责把这个后果说清楚。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CurriculumSourceDefaultTest {

    @Autowired
    private CurriculumProperties properties;

    @Test
    @DisplayName("目录来源默认是 files，不随升级自动切换（C02）")
    void repositoryDefaultsToFiles() {
        // 测试 profile 没有覆盖该配置项，因此这里读到的就是**生产默认值**。
        assertThat(properties.repository()).isEqualTo("files");
        assertThat(properties.usesDatabase()).isFalse();
    }

    @Test
    @DisplayName("仅当显式配置 database 时才读数据库")
    void databaseOnlyWhenExplicit() {
        assertThat(new CurriculumProperties("database").usesDatabase()).isTrue();
        // 大小写不敏感，避免因配置写法不同而静默退回文件来源。
        assertThat(new CurriculumProperties("DATABASE").usesDatabase()).isTrue();
        assertThat(new CurriculumProperties("files").usesDatabase()).isFalse();
        // 拼错时**不会**被当成 database——即不会意外切换到数据库来源。
        assertThat(new CurriculumProperties("databse").usesDatabase()).isFalse();
    }
}
