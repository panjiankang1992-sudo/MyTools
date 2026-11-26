package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuyutian.mytools.toolbox.mapper.admin.AdminTextbookRepository;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 草稿 CAS、版本不可变与目录世代的真实数据库行为。
 * 对应验收矩阵 B01／B08／B09／C05 的数据层部分。
 *
 * 本类刻意用**最小且无效**的夹具直接写版本行（元数据 `{}`、哈希 `sha-1`），
 * 用来验证数据层自身的约束。这类行**不能留在共享库里**：
 * 数据库来源下应用启动会读库重建目录快照，遇到它们会**按设计拒绝启动**
 * （`stored contentSha256 mismatch`）。因此本类使用独立内存库，
 * 不把故意构造的非法数据泄漏给其它测试上下文。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminTextbookRepositoryTest {

    /**
     * 独立内存库：本类写入的版本行是"故意非法"的数据层夹具。
     *
     * @param registry 动态属性注册器
     */
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:textbook-repository;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private AdminTextbookRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    /** 建一本新教材并返回草稿标识。 */
    private String newTextbook(String editionId) {
        return repository.createTextbook(editionId, "admin-1").draftId();
    }

    @Test
    @DisplayName("新建教材只产生草稿，不产生任何已发布版本")
    void createOnlyMakesDraft() {
        String draftId = newTextbook("test-book-new");
        assertThat(repository.findDraft(draftId)).isPresent();
        Integer versions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM toolbox_textbook_version WHERE edition_id = ?", Integer.class, "test-book-new");
        assertThat(versions).isZero();
    }

    @Test
    @DisplayName("草稿保存按修订号 CAS：正确修订成功，旧修订返回空（B08）")
    void saveDraftIsCompareAndSet() {
        String draftId = newTextbook("test-book-cas");
        Optional<Long> first = repository.saveDraft(draftId, 1L, "{\"v\":1}", "admin-1");
        assertThat(first).contains(2L);

        // 另一位管理员仍拿着旧修订号 1，必须失败而不是覆盖
        Optional<Long> stale = repository.saveDraft(draftId, 1L, "{\"v\":999}", "admin-2");
        assertThat(stale).isEmpty();

        // 库中仍是第一位管理员写入的内容
        assertThat(repository.findDraft(draftId).orElseThrow().editableJson()).isEqualTo("{\"v\":1}");
    }

    @Test
    @DisplayName("发布写入不可变版本，重复的同一内容版本被唯一键拒绝（C05）")
    void publishedVersionIsImmutable() {
        String editionId = "test-book-publish";
        newTextbook(editionId);
        byte[] bytes = "{\"book\":1}".getBytes(StandardCharsets.UTF_8);
        String versionId = repository.publishVersion(editionId, "1.0.0", bytes, "sha-1", "{}", "admin-1");
        assertThat(versionId).isEqualTo(editionId + "@1.0.0");

        // 同一内容版本不允许再次插入：已发布版本不可原地覆盖
        assertThatThrownBy(() -> repository.publishVersion(editionId, "1.0.0", bytes, "sha-1", "{}", "admin-1"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("版本读取返回原始字节与哈希，发布指针指向最新版本")
    void versionBytesArePreserved() {
        String editionId = "test-book-bytes";
        newTextbook(editionId);
        byte[] bytes = "原始字节".getBytes(StandardCharsets.UTF_8);
        repository.publishVersion(editionId, "1.0.0", bytes, "sha-original", "{}", "admin-1");
        AdminTextbookRepository.VersionRow row = repository.findVersion(editionId, "1.0.0").orElseThrow();
        assertThat(row.contentBytes()).isEqualTo(bytes);
        assertThat(row.contentSha256()).isEqualTo("sha-original");
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM toolbox_textbook WHERE edition_id = ?",
                String.class, editionId)).isEqualTo(editionId + "@1.0.0");
    }

    @Test
    @DisplayName("发布递增目录世代，且世代与版本名同步推进")
    void publishAdvancesCatalogEpoch() {
        long before = repository.currentEpoch();
        String editionId = "test-book-epoch";
        newTextbook(editionId);
        repository.publishVersion(editionId, "1.0.0", "x".getBytes(StandardCharsets.UTF_8), "sha", "{}", "admin-1");
        long after = repository.currentEpoch();
        assertThat(after).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject(
                "SELECT catalog_version FROM toolbox_curriculum_catalog WHERE catalog_key = 'current'", String.class))
                .isEqualTo("catalog-" + after);
    }

    @Test
    @DisplayName("幂等回执可写入并读回")
    void operationReceiptRoundTrip() {
        repository.recordOperation("admin-1", "publish", "key-1", "digest-1", "{\"ok\":true}");
        assertThat(repository.findOperation("admin-1", "publish", "key-1")).isPresent();
        assertThat(repository.findOperation("admin-1", "publish", "key-missing")).isEmpty();
    }
}
