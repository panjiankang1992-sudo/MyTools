package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.config.CurriculumProperties;
import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.AdminTextbookService;
import com.yuyutian.mytools.toolbox.service.admin.validation.AdminTextbookDraft;
import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * F11 的反向断言：**显式切到 `repository=database` 之后，发布必须照常成功**。
 *
 * 闸门不能变成"永远拒绝发布"——否则等于用一条新缺陷替换旧缺陷。
 * 本类与 {@link FilesRepositoryPublishBlockedTest} 用**同一份草稿、同一个调用**，
 * 唯一差别是测试专用的 `repository` 覆盖，因此两者的结论之差只可能来自来源模式。
 *
 * `repository=database` 只在这个测试类里覆盖，生产默认值仍是 `files`。
 */
@SpringBootTest(properties = "toolbox.curriculum.repository=database")
@ActiveProfiles("test")
class DatabaseRepositoryPublishAllowedTest {

    /** 测试操作者标识。 */
    private static final String ACTOR = "gate-test-admin";

    /**
     * 独立内存库：发布写入的版本只属于本用例。
     *
     * @param registry 动态属性注册器
     */
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:database-publish-allowed;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private AdminTextbookService textbooks;

    @Autowired
    private AdminTextbookRepository repository;

    @Autowired
    private CurriculumProperties curriculumProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CurriculumCatalog catalog;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 构造一份**最小可发布**的中文草稿 JSON（与公开接口用的是同一形状）。 */
    private static String draftJson(String editionId) {
        return """
                {"editionId":"%s","subject":"chinese","grade":1,"semester":"first",
                 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
                 "sourceNote":"纸质教材附录，第 78 页","coverageDeclaration":"partial",
                 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
                 "lessons":[],"entries":[]}
                """.formatted(editionId);
    }

    @Test
    @DisplayName("F11：显式配置 repository=database 后发布成功，目录立即生效")
    void publishSucceedsWhenRepositoryIsDatabase() throws Exception {
        // 前提：来源确实被测试专用 properties 切到了数据库。
        assertThat(curriculumProperties.usesDatabase())
                .as("本用例的前提是显式覆盖 repository=database").isTrue();

        String editionId = "gate-database-book";
        AdminTextbookDraft draft = objectMapper.readValue(draftJson(editionId), AdminTextbookDraft.class);
        AdminTextbookRepository.DraftRef created = textbooks.create(draft, ACTOR);
        long revision = textbooks.saveDraft(editionId, created.revision(), draft, ACTOR);

        var published = textbooks.publish(editionId, ACTOR, revision);

        // 发布成功：服务端分配版本号，且公开目录快照确实重建（不是"写库但对外不变"）。
        assertThat(published.contentVersion()).isEqualTo("1.0.0");
        assertThat(published.snapshotReloaded())
                .as("数据库来源下发布必须让公开目录立即生效").isTrue();
        assertThat(repository.findCurrentVersionId(editionId)).contains(editionId + "@1.0.0");
    }

    @Test
    @DisplayName("D3：发布事务回滚后，内存目录快照不得被交换——否则公开接口会服务从未提交的版本")
    void rolledBackPublishDoesNotSwapTheCatalogSnapshot() throws Exception {
        // 缺陷原状：`publishDraft` 在**外层事务内部**就 `reloadSnapshot` 交换内存快照。
        // 同一事务随后回滚时 —— **数据库回滚了、内存没回退** ——
        // 公开接口继续 200 提供那个从未提交的版本
        // （2026-07-07 真实 MySQL 实测：served `catalog-5` 而库里是 `catalog-4`，
        //  按 served 版本读 1.0.0 反而 404）。
        //
        // 修法：把交换推迟到 `afterCommit`。本用例就是那条"撤回修复必须失败"的反向验证载体。
        String editionId = "d3-rollback-book";
        AdminTextbookDraft draft = objectMapper.readValue(draftJson(editionId), AdminTextbookDraft.class);
        AdminTextbookRepository.DraftRef created = textbooks.create(draft, ACTOR);
        long revision = textbooks.saveDraft(editionId, created.revision(), draft, ACTOR);

        long snapshotBefore = catalog.snapshotVersion();
        String catalogVersionBefore = repository.currentCatalogVersion();
        long epochBefore = repository.currentEpoch();

        // 在一个**注定回滚**的事务里发布。
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            textbooks.publish(editionId, ACTOR, revision);
            throw new IllegalStateException("forced-rollback-after-publish");
        })).isInstanceOf(Exception.class);

        // 1) 数据库确实回滚了：没有任何版本落库。
        assertThat(repository.findCurrentVersionId(editionId))
                .as("回滚后不该留下当前版本指针").isEmpty();

        // 2) **内存目录快照也必须没被交换** —— 这是本用例的核心断言。
        assertThat(catalog.snapshotVersion())
                .as("回滚的发布不得交换内存目录快照")
                .isEqualTo(snapshotBefore);

        // 3) 目录版本与世代也不得前进。
        assertThat(repository.currentCatalogVersion()).isEqualTo(catalogVersionBefore);
        assertThat(repository.currentEpoch()).isEqualTo(epochBefore);
    }
}
