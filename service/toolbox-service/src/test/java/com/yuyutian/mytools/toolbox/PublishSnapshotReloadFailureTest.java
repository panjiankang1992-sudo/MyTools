package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.AdminTextbookService;
import com.yuyutian.mytools.toolbox.service.admin.curriculum.CurriculumSnapshotLoader;
import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * F07 故障注入：**快照重载失败时，发布必须如实报错**。
 *
 * 设计原话：
 *
 * > 重载失败**实际关闭教材就绪并恢复**，不能只设置无人读取的 `ready` 标记。
 *
 * ### 为什么这个测试写在**服务层**，而不是 HTTP 层
 *
 * 第 55/56 轮我两次想用 HTTP 接口测它，都被**登录 403** 挡住
 * （第 24/55/56/57 轮四次追查未果，已确认**不是助手写法**）。
 *
 * **但 403 挡的是"HTTP 登录"，不是这一项要验证的性质。**
 * 发布是否如实报错、目录是否被置为不可用，都发生在**服务层**——
 * 直接调服务即可，**根本不需要登录**。
 *
 * ### 注入点为什么必须是"可切换"的
 *
 * 第 55 轮我把加载器换成"永远失败"，结果**整个上下文起不来**。
 * 这个加载器**在启动时就会被调用**，所以失败必须发生在**启动之后**。
 *
 * F11：本用例走的是真实发布路径，因此显式覆盖 `repository=database`——
 * `files` 模式下发布会被闸门直接拒绝，根本到不了"重载失败"这一步。
 *
 * 又因为数据库来源下**上下文启动即会读库重建快照**，本类必须用独立内存库：
 * 否则启动时会读到别的用例为验证数据层而直接写入的版本行
 * （它们的元数据是 `{}`，会让重建在**启动阶段**就失败）。
 */
@SpringBootTest(properties = "toolbox.curriculum.repository=database")
@ActiveProfiles("test")
class PublishSnapshotReloadFailureTest {

    /**
     * 独立内存库：本类的断言是"重载失败"，不能被别的用例留下的版本行抢先搞崩上下文。
     *
     * @param registry 动态属性注册器
     */
    @org.springframework.test.context.DynamicPropertySource
    static void isolatedDatabase(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:reload-failure-database;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    /** 启动时正常、可被测试打开失败开关的快照加载器。 */
    static class ToggleableSnapshotLoader extends CurriculumSnapshotLoader {
        private volatile boolean failNext;

        ToggleableSnapshotLoader(AdminTextbookRepository repository, ObjectMapper objectMapper) {
            super(repository, objectMapper);
        }

        void failOnNextLoad() {
            this.failNext = true;
        }

        @Override
        public List<CurriculumCatalog.StoredVersion> loadAll() {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("injected snapshot reload failure");
            }
            return super.loadAll();
        }
    }

    @TestConfiguration
    static class ToggleLoaderConfiguration {
        @Bean
        @Primary
        ToggleableSnapshotLoader toggleableSnapshotLoader(AdminTextbookRepository repository,
                                                          ObjectMapper objectMapper) {
            return new ToggleableSnapshotLoader(repository, objectMapper);
        }
    }

    @Autowired
    private AdminTextbookService textbooks;

    @Autowired
    private AdminTextbookRepository repository;

    @Autowired
    private ToggleableSnapshotLoader loader;

    @Autowired
    private CurriculumCatalog catalog;

    private static final String DRAFT = """
            {"editionId":"reload-failure-book","subject":"chinese","grade":1,"semester":"first",
             "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
             "sourceNote":"附录","coverageDeclaration":"partial",
             "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
             "lessons":[],
             "entries":[{"rowId":"r1","entryId":null,"unitId":null,"clientUnitId":"cu1",
                         "lessonId":null,"clientLessonId":null,"text":"山","pinyin":"shān",
                         "meaning":null,"listTypes":["writing"],"confirmed":true}]}
            """;

    @Test
    @DisplayName("重载失败时发布必须报错，并把目录置为不可用")
    void publishSurfacesReloadFailureAndClosesCatalog() {
        AdminTextbookRepository.DraftRef draft = repository.createTextbook("reload-failure-book", "tester");
        repository.saveDraft(draft.draftId(), draft.revision(), DRAFT, "tester");

        // **上下文已就绪**，现在打开失败开关：只让这一次重载失败。
        loader.failOnNextLoad();

        assertThatThrownBy(() -> textbooks.publish("reload-failure-book", "tester", null))
                .as("重载失败时不能返回成功；否则调用方以为发布完成，而公开目录其实没更新")
                .isInstanceOf(RuntimeException.class);

        assertThat(catalog.isReady())
                .as("重载失败必须关闭目录就绪——这正是 F11 修的那条链")
                .isFalse();
    }
}
