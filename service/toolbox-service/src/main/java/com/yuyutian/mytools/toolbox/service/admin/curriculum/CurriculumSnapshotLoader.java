package com.yuyutian.mytools.toolbox.service.admin.curriculum;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import com.yuyutian.mytools.toolbox.model.TextbookEdition;
import com.yuyutian.mytools.toolbox.service.curriculum.CatalogValidationException;
import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 从数据库读取在册版本并还原为可交给目录校验的形态。
 * 启动加载与发布后重建共用本组件，避免两条路径的解释逻辑分叉。
 */
@Component
public class CurriculumSnapshotLoader {

    private final AdminTextbookRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * 创建加载器。
     *
     * @param repository   教材仓储
     * @param objectMapper JSON 解析器
     */
    public CurriculumSnapshotLoader(AdminTextbookRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取全部在册版本。
     *
     * @return 声明元数据与正文原始字节
     */
    public List<CurriculumCatalog.StoredVersion> loadAll() {
        List<CurriculumCatalog.StoredVersion> versions = new ArrayList<>();
        for (AdminTextbookRepository.VersionRow row : repository.listCurrentVersions()) {
            String metadata = repository.findVersionMetadata(row.editionId(), row.contentVersion())
                    .orElseThrow(() -> new CatalogValidationException(
                            "version metadata missing: " + row.versionId()));
            try {
                TextbookEdition declared = objectMapper.readValue(metadata, TextbookEdition.class);
                versions.add(new CurriculumCatalog.StoredVersion(declared, row.contentBytes()));
            } catch (Exception failure) {
                throw new CatalogValidationException("version metadata unparsable: " + row.versionId(), failure);
            }
        }
        return versions;
    }

    /**
     * 读取当前目录版本号。
     *
     * F14：**读库中保存的 `catalog_version` 列**，不再用 `"catalog-" + epoch` 现算。
     * 初始迁移保留的旧版本号（如 `catalog-7`）与世代号没有派生关系，
     * 现算会让公开目录报出与库里不一致的版本号。
     *
     * @return 目录版本号
     */
    public String currentCatalogVersion() {
        return repository.currentCatalogVersion();
    }
}
