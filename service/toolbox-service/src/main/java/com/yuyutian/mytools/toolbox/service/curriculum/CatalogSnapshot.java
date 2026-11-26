package com.yuyutian.mytools.toolbox.service.curriculum;

import com.yuyutian.mytools.toolbox.model.RequiredSlot;
import java.util.List;
import java.util.Map;

/**
 * 一次加载或一次发布产生的完整目录快照。
 *
 * 存在的理由：目录版本、必需槽位、已发布版本与其索引必须**始终同代**。
 * 若像过去那样分别更新多个 volatile 字段，并发发布期间会出现
 * 「新 catalogVersion + 旧版本列表」这类混合代读取。
 *
 * @param catalogVersion 目录版本号
 * @param requiredSlots  必需槽位清单
 * @param editions       已发布版本
 * @param byVersionKey   以 `editionId@contentVersion` 为键的索引
 * @param generation     与内容一起原子替换的进程内代际
 */
public record CatalogSnapshot(
        String catalogVersion,
        List<RequiredSlot> requiredSlots,
        List<CurriculumCatalog.LoadedEdition> editions,
        Map<String, CurriculumCatalog.LoadedEdition> byVersionKey,
        long generation) {

    /**
     * 返回空快照，用于启动初期尚无内容时。
     *
     * @return 空快照
     */
    public static CatalogSnapshot empty() {
        return new CatalogSnapshot(CurriculumCatalog.CATALOG_VERSION_UNAVAILABLE, List.of(), List.of(), Map.of(), 0);
    }
}
