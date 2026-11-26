package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import com.yuyutian.mytools.toolbox.model.CoverageStatus;
import com.yuyutian.mytools.toolbox.model.ReviewStatus;
import com.yuyutian.mytools.toolbox.model.Semester;
import com.yuyutian.mytools.toolbox.model.Subject;
import com.yuyutian.mytools.toolbox.model.TextbookEdition;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.CurriculumPublisher;
import com.yuyutian.mytools.toolbox.service.curriculum.CatalogQuery;
import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 发布与公开读取的闭环行为（验收项 C02／C05 的服务端部分）。
 * 重点：发布后目录立即更新（无需重启）；版本号与哈希由服务端分配。
 *
 * F11：这里显式覆盖 `repository=database`。发布写的是数据库、公开目录也读数据库时
 * 才谈得上"发布后立即可见"；`files` 模式下发布会被闸门拒绝（见
 * `FilesRepositoryPublishBlockedTest`），那是另一条性质。
 *
 * 数据库来源下**上下文启动即会读库重建快照**，所以本类用独立内存库，
 * 不去读（也不去污染）其它用例留下的版本行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "toolbox.curriculum.repository=database")
@ActiveProfiles("test")
class CurriculumPublisherTest {

    /**
     * 独立内存库：避免"启动时读到别的用例写入的版本行"这种顺序耦合。
     *
     * @param registry 动态属性注册器
     */
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:publisher-database;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private CurriculumPublisher publisher;

    @Autowired
    private CurriculumCatalog catalog;

    @Autowired
    private AdminTextbookRepository repository;

    /** 构造声明元数据；版本号与哈希会被服务端覆盖。 */
    private static TextbookEdition declared(String editionId) {
        return new TextbookEdition(editionId, Subject.CHINESE, 1, Semester.FIRST, List.of(),
                "人民教育出版社", "统编语文", "2024 年修订版", 2024, null, List.of(),
                1, "0.0.0",
                "0000000000000000000000000000000000000000000000000000000000000000",
                ReviewStatus.PUBLISHED, CoverageStatus.PARTIAL, Instant.now(), Instant.now(), 0, null, List.of());
    }

    @Test
    @DisplayName("发布后目录立即包含新版本，无需重启（C02）")
    void publishMakesEditionVisibleWithoutRestart() {
        String editionId = "publish-visible-book";
        repository.createTextbook(editionId, "admin-1");
        CurriculumPublisher.PublishResult result = publisher.publishDraft(editionId, declared(editionId),
                List.of(), List.of(), List.of(), repository.nextContentVersion(editionId), "首次发布", "admin-1");

        assertThat(result.snapshotReloaded()).isTrue();
        assertThat(publisher.isReady()).isTrue();
        var query = new CatalogQuery(null, null, null, null);
        assertThat(catalog.catalog(query).editions())
                .anySatisfy(edition -> assertThat(edition.editionId()).isEqualTo(editionId));
        assertThat(catalog.editionCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("版本号与哈希由服务端分配，调用方传入的占位值被覆盖")
    void serverAssignsVersionAndHash() {
        String editionId = "publish-assign-book";
        repository.createTextbook(editionId, "admin-1");
        CurriculumPublisher.PublishResult first = publisher.publishDraft(editionId, declared(editionId),
                List.of(), List.of(), List.of(), repository.nextContentVersion(editionId), "首次", "admin-1");
        assertThat(first.contentVersion()).isEqualTo("1.0.0");
        assertThat(first.contentSha256()).hasSize(64);
        // 再次发布必须递增，不能原地覆盖已发布版本
        CurriculumPublisher.PublishResult second = publisher.publishDraft(editionId, declared(editionId),
                List.of(), List.of(), List.of(), repository.nextContentVersion(editionId), "修订", "admin-1");
        assertThat(second.contentVersion()).isEqualTo("1.0.1");
        assertThat(second.versionId()).isNotEqualTo(first.versionId());
        assertThat(repository.findVersion(editionId, "1.0.0")).isPresent();
    }
}
