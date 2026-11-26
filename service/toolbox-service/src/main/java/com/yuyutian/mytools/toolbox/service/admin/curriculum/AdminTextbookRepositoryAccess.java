package com.yuyutian.mytools.toolbox.service.admin.curriculum;

import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 为对账提供两侧的逐册统计。
 *
 * 抽出来是为了让"如何从仓储取出可比统计"与"如何比较"分开：
 * 比较逻辑可以纯函数测试，取数逻辑依赖真实仓储。
 */
@Component
public class AdminTextbookRepositoryAccess {

    /**
     * 单册统计。
     *
     * @param version    内容版本
     * @param entryCount 条目数
     * @param sha256     正文字节哈希
     */
    public record EditionStat(String version, int entryCount, String sha256) {
    }

    /**
     * 取文件仓储的逐册统计。
     *
     * @param catalog 已加载的文件仓储目录；为 null 时返回空
     * @return 教材标识到统计的映射
     */
    public Map<String, EditionStat> fileStatistics(CurriculumCatalog catalog) {
        if (catalog == null) {
            return Map.of();
        }
        Map<String, EditionStat> stats = new LinkedHashMap<>();
        for (var edition : catalog.catalog(new com.yuyutian.mytools.toolbox.service.curriculum.CatalogQuery(
                null, null, null, null)).editions()) {
            stats.put(edition.editionId(), new EditionStat(edition.contentVersion(),
                    edition.availableEntryCount() == null ? 0 : edition.availableEntryCount(),
                    edition.contentSha256()));
        }
        return stats;
    }

    /**
     * 取数据库侧的逐册统计。
     *
     * @param loader 数据库快照加载器
     * @return 教材标识到统计的映射
     */
    public Map<String, EditionStat> databaseStatistics(CurriculumSnapshotLoader loader) {
        Map<String, EditionStat> stats = new LinkedHashMap<>();
        for (CurriculumCatalog.StoredVersion stored : loader.loadAll()) {
            stats.put(stored.declared().editionId(), new EditionStat(stored.declared().contentVersion(),
                    stored.declared().availableEntryCount() == null ? 0 : stored.declared().availableEntryCount(),
                    stored.declared().contentSha256()));
        }
        return stats;
    }
}
