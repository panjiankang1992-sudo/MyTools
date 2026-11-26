package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuyutian.mytools.toolbox.model.CoverageStatus;
import com.yuyutian.mytools.toolbox.model.ReviewStatus;
import com.yuyutian.mytools.toolbox.model.Semester;
import com.yuyutian.mytools.toolbox.model.Subject;
import com.yuyutian.mytools.toolbox.model.TextbookEdition;
import com.yuyutian.mytools.toolbox.service.curriculum.CatalogQuery;
import java.time.Instant;
import com.yuyutian.mytools.toolbox.service.curriculum.CatalogValidationException;
import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 由数据库重建目录快照的行为（设计文档 §5.5、验收项 C02／C05）。
 * 关键安全属性：重建失败时**不得替换**现有快照，否则会对外提供半成品目录。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CatalogReloadTest {

    @Autowired
    private CurriculumCatalog catalog;

    /** 构造一个声明齐全但正文哈希必然不匹配的版本。 */
    private static CurriculumCatalog.StoredVersion mismatchedVersion() {
        TextbookEdition declared = new TextbookEdition(
                "reload-book", Subject.CHINESE, 1, Semester.FIRST, List.of(),
                "人民教育出版社", "统编语文", "2024 年修订版", 2024, null, List.of(),
                1, "1.0.0",
                "0000000000000000000000000000000000000000000000000000000000000000",
                ReviewStatus.PUBLISHED, CoverageStatus.PARTIAL, Instant.now(), Instant.now(), 0, null, List.of());
        return new CurriculumCatalog.StoredVersion(declared, "{}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("正文哈希不匹配时重建失败，且现有快照保持不变")
    void failedReloadKeepsPreviousSnapshot() {
        int editionsBefore = catalog.editionCount();
        int entriesBefore = catalog.entryCount();
        String versionBefore = catalog.catalog(new CatalogQuery(null, null, null, null)).catalogVersion();

        assertThatThrownBy(() -> catalog.reloadFromVersions("catalog-broken", List.of(mismatchedVersion())))
                .isInstanceOf(CatalogValidationException.class);

        // 失败不得替换：数量、条目与目录版本都必须与失败前一致
        assertThat(catalog.editionCount()).isEqualTo(editionsBefore);
        assertThat(catalog.entryCount()).isEqualTo(entriesBefore);
        assertThat(catalog.catalog(new CatalogQuery(null, null, null, null)).catalogVersion())
                .isEqualTo(versionBefore);
    }

    @Test
    @DisplayName("空列表可正常重建为空目录，并更新目录版本号")
    void emptyReloadProducesEmptyCatalog() {
        catalog.reloadFromVersions("catalog-empty-test", List.of());
        assertThat(catalog.editionCount()).isZero();
        assertThat(catalog.entryCount()).isZero();
        assertThat(catalog.catalog(new CatalogQuery(null, null, null, null)).catalogVersion())
                .isEqualTo("catalog-empty-test");
    }
}
