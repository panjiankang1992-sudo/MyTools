package com.yuyutian.mytools.toolbox.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.CurriculumSnapshotLoader;
import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 教材目录配置：启动时加载并校验已发布内容，校验失败直接让进程启动失败。
 */
@Configuration
public class CurriculumConfig {

    private static final Logger LOG = LoggerFactory.getLogger(CurriculumConfig.class);

    /**
     * 创建并加载教材目录快照。
     *
     * 来源由 `toolbox.curriculum.repository` 决定：
     * - `files`（默认）：读只读文件目录，与既有生产行为一致；
     * - `database`：读工具箱数据库中的在册版本。
     *
     * 默认保持 files，切换必须是显式配置——避免升级后未经确认就改变公开内容来源。
     *
     * @param properties        门面配置
     * @param curriculumProperties 目录来源配置
     * @param adminProperties   管理端配置（仅用于启动提示）
     * @param loader            数据库快照加载器
     * @param objectMapper      JSON 解析器
     * @return 已校验的教材内容目录
     */
    @Bean
    public CurriculumCatalog curriculumCatalog(ToolboxProperties properties,
                                               CurriculumProperties curriculumProperties,
                                               AdminProperties adminProperties,
                                               CurriculumSnapshotLoader loader,
                                               ObjectMapper objectMapper) {
        CurriculumCatalog catalog = new CurriculumCatalog(Path.of(properties.catalogPath()), objectMapper);
        if (curriculumProperties.usesDatabase()) {
            catalog.reloadFromVersions(loader.currentCatalogVersion(), loader.loadAll());
        } else {
            catalog.load();
            // 管理端已启用但目录来源仍是文件：**发布只写数据库，公开目录不会随之变化**。
            // 这不是故障，而是一个容易误解的运维事实——因此启动时明确说出来，
            // 否则运维会以为"发布了却没生效"是缺陷。
            if (adminProperties.enabled()) {
                LOG.warn("toolbox.admin.enabled=true 但 toolbox.curriculum.repository=files："
                        + "管理端发布写入数据库，公开目录仍读取文件目录，"
                        + "因此在切换为 repository=database 之前，新发布的教材不会对外可见。");
            }
        }
        return catalog;
    }
}
