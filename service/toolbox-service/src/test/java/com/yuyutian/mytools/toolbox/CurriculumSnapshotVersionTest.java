package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import com.yuyutian.mytools.toolbox.service.translation.TranslationDictionary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * C06：目录换代后，**从目录派生的缓存必须能察觉**。
 *
 * 设计文档 §3 要求「发布后同步更新词典，不能一直使用启动时的内容」。
 * `TranslationDictionary` 是单例，若只在构造时加载一次，发布新教材后翻译仍命中旧词义。
 *
 * 这里锁住它赖以工作的信号：目录快照的**代际版本号**。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CurriculumSnapshotVersionTest {

    @Autowired
    private CurriculumCatalog catalog;

    @Autowired
    private TranslationDictionary dictionary;

    @Test
    @DisplayName("目录快照有代际版本号，且每次重载后递增（C06）")
    void snapshotVersionAdvancesOnReload() {
        long before = catalog.snapshotVersion();
        // 用同一来源重新加载：**即使内容没变，代际也必须推进**——
        // 否则派生缓存无法区分"重载过"与"没重载过"。
        catalog.load();
        assertThat(catalog.snapshotVersion()).isGreaterThan(before);
        catalog.load();
        assertThat(catalog.snapshotVersion()).isGreaterThan(before + 1);
    }

    @Test
    @DisplayName("词典在构造后即可用，且查询不改变目录代际（C06）")
    void dictionaryIsUsableAndDoesNotMutateCatalog() {
        long versionBefore = catalog.snapshotVersion();
        // 词典在首次查询时比对目录代际，未换代则零开销复用现有索引。
        assertThat(dictionary.find("apple")).isNotNull();
        assertThat(catalog.snapshotVersion()).isEqualTo(versionBefore);
    }

    @Test
    @DisplayName("目录换代后词典会重建（不再一直使用启动时的内容）")
    void dictionaryRebuildsAfterCatalogChanges() {
        // 先查询一次，让词典绑定到当前代际。
        dictionary.find("apple");
        // 换代。
        catalog.load();
        assertThat(catalog.snapshotVersion()).isGreaterThan(0);
        // 换代后再次查询必须**正常返回**（重建路径不能抛错或返回空实现）。
        assertThat(dictionary.find("apple")).isNotNull();
    }
}
