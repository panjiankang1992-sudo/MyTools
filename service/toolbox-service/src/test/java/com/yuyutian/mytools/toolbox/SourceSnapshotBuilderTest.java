package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.model.EntrySourceRef;
import com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder;
import com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.EntryLocator;
import com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.FrozenSource;
import com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.ManualSource;
import com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.SnapshotCapture;
import com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.SnapshotRequest;
import com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder.SnapshotResult;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 来源快照构建器：缺失即标记、确定性序列化、不装绝对路径与凭据。
 *
 * 这三条都是"快照能不能被信任"的前提：编造字段会让审计失真，
 * 不确定的字节会让"同一输入同一 hash"失效，装进机器路径／凭据则会让包不能被安全分发。
 */
class SourceSnapshotBuilderTest {

    @TempDir
    Path contentRoot;

    private final SourceSnapshotBuilder builder = new SourceSnapshotBuilder();

    /** 每个用例先用同一份夹具铺好内容根目录。 */
    @BeforeEach
    void seedContent() throws Exception {
        SourceSnapshotFixtures.seed(contentRoot);
    }

    /** 固定采集信息，使"同一输入"包含时间也一致。 */
    private SnapshotCapture capture() {
        return new SnapshotCapture("2026-10-07T00:00:00Z", "1001", "publish:snap-book@1.0.0", "publish", null);
    }

    /** 用夹具内容构造一次快照请求。 */
    private SnapshotResult build(List<String> sourceIds, List<EntryLocator> locators, List<ManualSource> manual) {
        return builder.build(new SnapshotRequest(contentRoot, SourceSnapshotFixtures.EDITION_ID,
                SourceSnapshotFixtures.CONTENT_VERSION,
                SourceSnapshotFixtures.sha256Hex(SourceSnapshotFixtures.bookJson()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                sourceIds, locators, manual, capture(), null));
    }

    @Test
    @DisplayName("缺失字段标记为缺失，不编造 reviewedBy/verifiedAt 之类证据")
    void missingFieldsAreMarkedNotInvented() {
        SnapshotResult result = build(
                List.of(SourceSnapshotFixtures.SOURCE_ID, "snap-source-missing"),
                List.of(new EntryLocator("snap-book-e0001",
                        List.of(new EntrySourceRef(SourceSnapshotFixtures.SOURCE_ID, "row 2", 7)))),
                List.of());

        var document = result.node();
        // 1) 引用但内容根目录里没有：保留缺失说明。
        FrozenSource absent = null;
        FrozenSource partial = null;
        for (var source : document.path("sources")) {
            if ("snap-source-missing".equals(source.path("sourceId").asText())) {
                absent = new FrozenSource(source.path("sourceId").asText(), source.path("missing").asBoolean(),
                        source.path("missingReason").asText(), null, List.of(), null);
            }
            if (SourceSnapshotFixtures.PARTIAL_SOURCE_ID.equals(source.path("sourceId").asText())
                    || "snap-source-full".equals(source.path("sourceId").asText())) {
                if (!source.path("missingFields").isEmpty()) {
                    partial = new FrozenSource(source.path("sourceId").asText(), false, null, null, List.of(), null);
                }
            }
        }
        assertThat(absent).as("被引用但找不到的来源必须出现且标记 missing").isNotNull();
        assertThat(absent.missing()).isTrue();
        assertThat(absent.missingReason()).isEqualTo("declaredButNotFoundInContentRoot");
        assertThat(document.path("missing").toString()).contains("source:snap-source-missing");

        // 2) 字段完整的来源：不应被标记缺失，且原对象逐字段冻结（含 verifiedAt/reviewedBy）。
        var full = sourceNode(document, SourceSnapshotFixtures.SOURCE_ID);
        assertThat(full.path("missing").asBoolean()).isFalse();
        assertThat(full.path("record").path("reviewedBy").asText()).isEqualTo("cross-source-verification");
        assertThat(full.path("record").path("verifiedAt").asText()).isEqualTo("2026-10-02");
        assertThat(full.path("record").path("revisionReviews").isArray()).isTrue();
        assertThat(full.path("record").path("knownGaps").isArray()).isTrue();
        assertThat(full.path("missingFields").toString())
                .as("字段完整的来源不应有缺失字段").doesNotContain("reviewedBy");

        // 3) 手工来源：机器不补审阅证据——缺失清单里必须点出 reviewedBy/verifiedAt。
        SnapshotResult withManual = build(List.of(), List.of(),
                List.of(new ManualSource(null, "manualEntry", "管理员手填来源", "https://example.org/x",
                        "某出版社", "9780000000000", "2024 年版", "第 12 页", 12, "手填备注")));
        var manualSource = withManual.node().path("sources").get(0);
        assertThat(manualSource.path("origin").asText()).isEqualTo("manual");
        assertThat(manualSource.path("record").path("url").asText()).isEqualTo("https://example.org/x");
        assertThat(manualSource.path("record").path("page").asInt()).isEqualTo(12);
        assertThat(manualSource.path("record").has("reviewedBy"))
                .as("机器补全不得写入“已人工核对”的证据").isFalse();
        assertThat(manualSource.path("missingFields").toString()).contains("reviewedBy", "verifiedAt");
    }

    @Test
    @DisplayName("同一输入得到同一字节与同一 hash")
    void sameInputProducesSameBytesAndHash() {
        List<EntryLocator> locators = List.of(new EntryLocator("snap-book-e0001",
                List.of(new EntrySourceRef(SourceSnapshotFixtures.SOURCE_ID, "row 2", 7))));
        SnapshotResult first = build(List.of(SourceSnapshotFixtures.SOURCE_ID), locators, List.of());
        SnapshotResult second = build(List.of(SourceSnapshotFixtures.SOURCE_ID), locators, List.of());
        assertThat(second.json()).isEqualTo(first.json());
        assertThat(second.sha256()).isEqualTo(first.sha256()).hasSize(64);
        // 目录基线必须来自 manifest 的 catalogVersion，**不是** contentVersion。
        assertThat(first.node().path("manifestBaseline").path("catalogVersion").asText())
                .isEqualTo(SourceSnapshotFixtures.CATALOG_VERSION);
        assertThat(first.node().path("version").path("contentVersion").asText())
                .isEqualTo(SourceSnapshotFixtures.CONTENT_VERSION);
    }

    @Test
    @DisplayName("快照不装机器绝对路径与凭据")
    void snapshotCarriesNoAbsolutePathOrCredential() {
        SnapshotResult result = build(List.of(SourceSnapshotFixtures.SOURCE_ID),
                List.of(new EntryLocator("snap-book-e0001",
                        List.of(new EntrySourceRef(SourceSnapshotFixtures.SOURCE_ID, "row 2", 7)))),
                List.of());
        String json = result.json();
        assertThat(json).doesNotContain(contentRoot.toAbsolutePath().toString());
        assertThat(json).doesNotContain("/Users/", "/home/", "file://");
        assertThat(json.toLowerCase()).doesNotContain("password", "secret", "credential", "api_key", "token");
        // 文件证据必须是内容根目录内的相对路径。
        var files = result.node().path("files");
        assertThat(files).hasSize(1);
        assertThat(files.get(0).path("relativePath").asText()).isEqualTo(SourceSnapshotFixtures.SOURCES_RELATIVE);
        assertThat(files.get(0).path("sha256").asText()).hasSize(64);
        assertThat(files.get(0).path("utf8Text").asText()).contains("snap-source-full");
    }

    @Test
    @DisplayName("条目定位原样保留 {sourceId, locator, page}")
    void entryLocatorsArePreservedVerbatim() {
        SnapshotResult result = build(List.of(SourceSnapshotFixtures.SOURCE_ID),
                List.of(new EntryLocator("snap-book-e0001",
                        List.of(new EntrySourceRef(SourceSnapshotFixtures.SOURCE_ID, "row 2", 7)))),
                List.of());
        var ref = result.node().path("entryLocators").get(0).path("sourceRefs").get(0);
        assertThat(ref.path("sourceId").asText()).isEqualTo(SourceSnapshotFixtures.SOURCE_ID);
        assertThat(ref.path("locator").asText()).isEqualTo("row 2");
        assertThat(ref.path("page").asInt()).isEqualTo(7);
    }

    /** 按 sourceId 取来源节点。 */
    private static com.fasterxml.jackson.databind.JsonNode sourceNode(
            com.fasterxml.jackson.databind.JsonNode document, String sourceId) {
        for (var node : document.path("sources")) {
            if (sourceId.equals(node.path("sourceId").asText())) {
                return node;
            }
        }
        throw new AssertionError("source not found: " + sourceId);
    }
}
