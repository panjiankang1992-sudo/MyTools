package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.toolbox.service.admin.migration.SourceSnapshotBuilder;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 来源快照全链路：导入冻结基线 → 发布后不可变 → 导出带上快照 → 空库恢复逐项一致 → 显式补录。
 *
 * 这里刻意走**真实 HTTP + 真实随机端口 + 隔离 H2**：只有在真实序列化往返中，
 * "快照不丢"才有意义；只测内存对象证明不了导出包能把它带回来。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 内容根目录覆盖为夹具目录（见 CONTENT_ROOT）。
                // `repository=database` 是发布闸门（F11）要求的可写目录来源。
                "toolbox.curriculum.repository=database",
                // **专用隔离库**：恢复演练必须从一个真正独立的空库开始，
                // 也避免与共享 H2 库里其它测试留下的版本行互相污染。
                "spring.datasource.url=jdbc:h2:mem:source-snapshot;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        })
@ActiveProfiles("test")
class SourceSnapshotRoundTripTest {

    /** 夹具内容根目录：类初始化时铺好，供 Spring 启动与构建器共用。 */
    private static final Path CONTENT_ROOT = createContentRoot();

    /** 注入式假身份：否则登录会走不可达的真实上游，表现为与原因无关的 403。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class FakeIdentity {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityClient fakeIdentityClient() {
            return new com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentityClient() {
                @Override
                public UpstreamLogin login(String account, String password) {
                    return new UpstreamLogin("access-x", "refresh-x", 3600L);
                }

                @Override
                public com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentity validate(String accessToken) {
                    return new com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentity(
                            true, "1001", "someone",
                            java.util.List.of(com.yuyutian.mytools.toolbox.service.admin.identity.AdminIdentity.ROLE_ADMIN));
                }

                @Override
                public void logout(String accessToken) {
                }
            };
        }
    }

    /** 把夹具目录登记成内容根目录；在上下文创建前完成。 */
    @DynamicPropertySource
    static void catalogPath(DynamicPropertyRegistry registry) {
        registry.add("toolbox.catalog-path", () -> CONTENT_ROOT.toString());
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SourceSnapshotBuilder snapshotBuilder;

    /** 建目录并铺夹具内容。 */
    private static Path createContentRoot() {
        try {
            Path root = Files.createTempDirectory("toolbox-source-snapshot");
            SourceSnapshotFixtures.seed(root);
            return root;
        } catch (IOException failure) {
            throw new IllegalStateException("cannot seed source snapshot content root", failure);
        }
    }

    /** 用例前确保夹具来源文件是原始内容（上一个用例可能改过它）。 */
    @PostConstruct
    void resetSourceFile() throws IOException {
        Files.writeString(CONTENT_ROOT.resolve(SourceSnapshotFixtures.SOURCES_RELATIVE),
                SourceSnapshotFixtures.sourcesJson(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("不可变性：发布后修改本地 sources，旧版快照逐字节不变")
    void storedSnapshotSurvivesLocalSourceEdit() throws Exception {
        HttpHeaders headers = loginAndHeaders();
        importFromFiles(headers);
        String before = versionSnapshot(headers, SourceSnapshotFixtures.EDITION_ID,
                SourceSnapshotFixtures.CONTENT_VERSION).path("snapshotJson").asText();
        assertThat(before).contains(SourceSnapshotFixtures.CATALOG_VERSION);

        Path sourceFile = CONTENT_ROOT.resolve(SourceSnapshotFixtures.SOURCES_RELATIVE);
        byte[] original = Files.readAllBytes(sourceFile);
        try {
            // 改动本地来源：标题与核验人全变。
            Files.writeString(sourceFile,
                    SourceSnapshotFixtures.sourcesJson().replace("义务教育教科书电子教材", "被改动的标题")
                            .replace("cross-source-verification", "someone-else"), StandardCharsets.UTF_8);

            String after = versionSnapshot(headers, SourceSnapshotFixtures.EDITION_ID,
                    SourceSnapshotFixtures.CONTENT_VERSION).path("snapshotJson").asText();
            assertThat(after).as("发布后修改本地 sources 不得改变旧版快照").isEqualTo(before);

            // 导出同样只能读版本里存的快照。
            JsonNode exported = exportedEntry(exportPackage(headers), SourceSnapshotFixtures.EDITION_ID,
                    SourceSnapshotFixtures.CONTENT_VERSION);
            assertThat(exported.path("sourceMetadataJson").asText())
                    .as("导出必须从版本快照读，而不是回读磁盘").isEqualTo(before);

            // 反向证明：现在从磁盘重建会得到不同的内容（说明上一条断言不是因为磁盘没变）。
            var rebuilt = snapshotBuilder.build(new SourceSnapshotBuilder.SnapshotRequest(CONTENT_ROOT,
                    SourceSnapshotFixtures.EDITION_ID, SourceSnapshotFixtures.CONTENT_VERSION,
                    exported.path("contentSha256").asText(),
                    List.of(SourceSnapshotFixtures.SOURCE_ID),
                    snapshotBuilder.entryLocatorsFromBookBytes(
                            versionBytes(headers, SourceSnapshotFixtures.EDITION_ID,
                                    SourceSnapshotFixtures.CONTENT_VERSION)),
                    List.of(),
                    new SourceSnapshotBuilder.SnapshotCapture("2026-10-07T00:00:00Z", "1001", "probe", "publish", null),
                    null));
            assertThat(rebuilt.json()).isNotEqualTo(before);
        } finally {
            Files.write(sourceFile, original);
        }
    }

    @Test
    @DisplayName("往返不丢：导出 → 隔离空库恢复后 book 原 bytes/hash、目录版本、来源与 current 指针逐项一致")
    void exportRestoreIntoEmptyDatabaseReconcilesEverything() throws Exception {
        HttpHeaders headers = loginAndHeaders();
        importFromFiles(headers);
        // 再发布一版，制造"被取代的历史版本"——历史留存也是恢复必须逐项一致的内容。
        long revision = draftRevision(headers, SourceSnapshotFixtures.EDITION_ID);
        saveDraft(headers, SourceSnapshotFixtures.EDITION_ID, revision, draftJson(SourceSnapshotFixtures.EDITION_ID,
                "水", "shuǐ", "\"snap-source-full\"", "row 9", 3));
        publish(headers, SourceSnapshotFixtures.EDITION_ID);

        JsonNode packageData = exportPackage(headers);
        JsonNode first = exportedEntry(packageData, SourceSnapshotFixtures.EDITION_ID,
                SourceSnapshotFixtures.CONTENT_VERSION);
        JsonNode second = exportedEntry(packageData, SourceSnapshotFixtures.EDITION_ID, "1.0.1");
        assertThat(first).as("导出包必须带上来源快照").isNotNull();
        assertThat(second).as("历史版本必须一并导出").isNotNull();
        assertThat(second.path("current").asBoolean()).isTrue();

        byte[] originalBytes = versionBytes(headers, SourceSnapshotFixtures.EDITION_ID, "1.0.1");
        String originalSha = SourceSnapshotFixtures.sha256Hex(originalBytes);
        String originalSnapshot = second.path("sourceMetadataJson").asText();
        String catalogBaseline = catalogVersionOf(originalSnapshot);
        assertThat(catalogBaseline).isEqualTo(SourceSnapshotFixtures.CATALOG_VERSION);

        // 清单也必须带上快照：否则"清单 + 原始字节"这条恢复路径还得回头读磁盘。
        JsonNode manifest = TestJson.parse(rest.exchange("/admin/v1/curriculum/export-manifest",
                HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody()).path("data");
        JsonNode manifestSnapshot = null;
        for (JsonNode edition : manifest.path("editions")) {
            if (!SourceSnapshotFixtures.EDITION_ID.equals(edition.path("editionId").asText())) {
                continue;
            }
            for (JsonNode item : edition.path("history")) {
                if ("1.0.1".equals(item.path("contentVersion").asText())) {
                    manifestSnapshot = item;
                }
            }
        }
        assertThat(manifestSnapshot).as("导出清单必须包含该册的当前版本历史项").isNotNull();
        assertThat(manifestSnapshot.path("hasSourceSnapshot").asBoolean()).isTrue();
        assertThat(manifestSnapshot.path("sourceMetadataJson").asText()).isEqualTo(originalSnapshot);

        // 模拟隔离空库：只删这一册的行（不动全局目录世代，不碰其它教材）。
        jdbc.update("DELETE FROM toolbox_textbook_version WHERE edition_id = ?", SourceSnapshotFixtures.EDITION_ID);
        jdbc.update("DELETE FROM toolbox_textbook_draft WHERE edition_id = ?", SourceSnapshotFixtures.EDITION_ID);
        jdbc.update("DELETE FROM toolbox_textbook WHERE edition_id = ?", SourceSnapshotFixtures.EDITION_ID);
        assertThat(countVersions(SourceSnapshotFixtures.EDITION_ID)).isZero();

        HttpHeaders restoreHeaders = new HttpHeaders();
        restoreHeaders.putAll(headers);
        ResponseEntity<String> restored = rest.exchange("/admin/v1/curriculum/restore-package", HttpMethod.POST,
                new HttpEntity<>(packageData.toString(), restoreHeaders), String.class);
        assertThat(restored.getStatusCode().value()).isEqualTo(200);
        JsonNode report = TestJson.parse(restored.getBody()).path("data");
        assertThat(report.path("ok").asBoolean()).isTrue();
        assertThat(report.path("restoredVersions").asInt()).isGreaterThanOrEqualTo(2);

        JsonNode reconciliation = null;
        for (JsonNode item : report.path("reconciliations")) {
            if (SourceSnapshotFixtures.EDITION_ID.equals(item.path("editionId").asText())) {
                reconciliation = item;
            }
        }
        assertThat(reconciliation).as("恢复报告必须包含该册对账").isNotNull();
        assertThat(reconciliation.path("contentSha256Matches").asBoolean()).isTrue();
        assertThat(reconciliation.path("sourceSnapshotMatches").asBoolean()).isTrue();
        assertThat(reconciliation.path("currentPointerRestored").asBoolean()).isTrue();
        assertThat(reconciliation.path("historyCount").asInt()).isEqualTo(2);
        assertThat(reconciliation.path("catalogVersionBaseline").asText())
                .as("恢复后目录基线仍是 manifest 的 catalogVersion，不是 contentVersion")
                .isEqualTo(SourceSnapshotFixtures.CATALOG_VERSION);

        // 逐项对账：原 bytes/hash、current 指针、来源快照、sourceRefs。
        byte[] restoredBytes = versionBytes(headers, SourceSnapshotFixtures.EDITION_ID, "1.0.1");
        assertThat(restoredBytes).isEqualTo(originalBytes);
        assertThat(SourceSnapshotFixtures.sha256Hex(restoredBytes)).isEqualTo(originalSha);
        assertThat(currentVersionId(SourceSnapshotFixtures.EDITION_ID))
                .isEqualTo(SourceSnapshotFixtures.EDITION_ID + "@1.0.1");
        assertThat(versionSnapshot(headers, SourceSnapshotFixtures.EDITION_ID, "1.0.1")
                .path("snapshotJson").asText()).isEqualTo(originalSnapshot);
        JsonNode entryRef = TestJson.parse(new String(restoredBytes, StandardCharsets.UTF_8))
                .path("entries").get(0).path("sourceRefs").get(0);
        assertThat(entryRef.path("sourceId").asText()).isEqualTo(SourceSnapshotFixtures.SOURCE_ID);
        assertThat(entryRef.path("locator").asText()).isEqualTo("row 9");
        assertThat(entryRef.path("page").asInt()).isEqualTo(3);

        // 指针修复也是恢复的一部分：把 current 摆到旧版，再恢复一次，必须被摆回包内声明的当前版本。
        jdbc.update("UPDATE toolbox_textbook SET current_version_id = ? WHERE edition_id = ?",
                SourceSnapshotFixtures.EDITION_ID + "@1.0.0", SourceSnapshotFixtures.EDITION_ID);
        ResponseEntity<String> secondRestore = rest.exchange("/admin/v1/curriculum/restore-package",
                HttpMethod.POST, new HttpEntity<>(packageData.toString(), restoreHeaders), String.class);
        assertThat(secondRestore.getStatusCode().value()).isEqualTo(200);
        JsonNode secondReconciliation = null;
        for (JsonNode item : TestJson.parse(secondRestore.getBody()).path("data").path("reconciliations")) {
            if (SourceSnapshotFixtures.EDITION_ID.equals(item.path("editionId").asText())) {
                secondReconciliation = item;
            }
        }
        assertThat(secondReconciliation.path("currentPointerRestored").asBoolean()).isTrue();
        assertThat(currentVersionId(SourceSnapshotFixtures.EDITION_ID))
                .isEqualTo(SourceSnapshotFixtures.EDITION_ID + "@1.0.1");
    }

    @Test
    @DisplayName("手工录入：来源说明/页码/链接变成明确来源记录，机器不生成已核对证据")
    void manualEntryBecomesExplicitSourceRecords() {
        HttpHeaders headers = loginAndHeaders();
        String editionId = "manual-source-book";
        String draft = """
                {"editionId":"%s","subject":"chinese","grade":2,"semester":"first",
                 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
                 "sourceNote":"纸质教材附录，第 78 页","coverageDeclaration":"partial",
                 "sourceIds":["snap-source-full"],
                 "sources":[{"kind":"manualEntry","title":"管理员手填的教辅","url":"https://example.org/manual",
                             "publisher":"某出版社","isbn":"9780000000000","editionLabel":"2024 年版",
                             "locator":"第 12 页","page":12,"note":"手填备注"}],
                 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
                 "lessons":[],
                 "entries":[{"rowId":"r1","entryId":null,"unitId":null,"clientUnitId":"cu1","lessonId":null,
                             "clientLessonId":null,"text":"水","pinyin":"shuǐ","meaning":null,
                             "listTypes":["writing"],"confirmed":true,
                             "sourceRefs":[{"sourceId":"snap-source-full","locator":"row 9","page":3}]}]}
                """.formatted(editionId);
        assertThat(rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draft, freshKey(headers)), String.class).getStatusCode().value()).isEqualTo(201);

        // 草稿详情必须把这些具名字段带回来（草稿→详情不丢）。
        JsonNode draftData = TestJson.parse(rest.exchange("/admin/v1/textbooks/" + editionId + "/draft",
                HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody()).path("data");
        assertThat(draftData.path("draft").path("sourceIds").get(0).asText())
                .isEqualTo(SourceSnapshotFixtures.SOURCE_ID);
        assertThat(draftData.path("draft").path("sources").get(0).path("url").asText())
                .isEqualTo("https://example.org/manual");
        assertThat(draftData.path("draft").path("entries").get(0).path("sourceRefs").get(0).path("page").asInt())
                .isEqualTo(3);
        assertThat(draftData.path("sourceMetadata").asText()).contains("manual");

        publish(headers, editionId);
        JsonNode snapshot = versionSnapshot(headers, editionId, "1.0.0");
        JsonNode document = TestJson.parse(snapshot.path("snapshotJson").asText());

        JsonNode manual = null;
        JsonNode frozen = null;
        for (JsonNode source : document.path("sources")) {
            // 手工来源里既可能有"管理员手填记录"，也可能有"教材级来源说明"派生的记录，
            // 这里按 url 精确挑出前者，避免断言到另一条。
            if ("manual".equals(source.path("origin").asText())
                    && "https://example.org/manual".equals(source.path("record").path("url").asText())) {
                manual = source;
            }
            if (SourceSnapshotFixtures.SOURCE_ID.equals(source.path("sourceId").asText())
                    && "contentRoot".equals(source.path("origin").asText())) {
                frozen = source;
            }
        }
        assertThat(manual).as("管理员手填的来源必须成为明确来源记录").isNotNull();
        assertThat(manual.path("record").path("url").asText()).isEqualTo("https://example.org/manual");
        assertThat(manual.path("record").path("page").asInt()).isEqualTo(12);
        assertThat(manual.path("record").path("locator").asText()).isEqualTo("第 12 页");
        assertThat(manual.path("record").has("reviewedBy"))
                .as("机器补全不得生成“已经人工核对”的证据").isFalse();
        assertThat(manual.path("record").has("verifiedAt")).isFalse();
        assertThat(manual.path("missingFields").toString()).contains("reviewedBy", "verifiedAt");
        assertThat(frozen).as("教材级 sourceIds 必须按内容根目录冻结完整来源对象").isNotNull();
        assertThat(frozen.path("record").path("reviewedBy").asText()).isEqualTo("cross-source-verification");

        // 条目定位原样保留。
        JsonNode ref = document.path("entryLocators").get(0).path("sourceRefs").get(0);
        assertThat(ref.path("sourceId").asText()).isEqualTo(SourceSnapshotFixtures.SOURCE_ID);
        assertThat(ref.path("locator").asText()).isEqualTo("row 9");
        assertThat(ref.path("page").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("补录：历史无来源版本补录后原正文不变，且带补录时间与依据")
    void repairAddsProvenanceWithoutChangingBytes() {
        HttpHeaders headers = loginAndHeaders();
        String editionId = "repair-source-book";
        String draft = draftJson(editionId, "山", "shān", "\"snap-source-full\",\"snap-source-absent\"", "row 4", 1);
        assertThat(rest.exchange("/admin/v1/textbooks", HttpMethod.POST,
                new HttpEntity<>(draft, freshKey(headers)), String.class).getStatusCode().value()).isEqualTo(201);
        publish(headers, editionId);

        String versionId = editionId + "@1.0.0";
        String bytesBefore = jdbc.queryForObject(
                "SELECT content_sha256 FROM toolbox_textbook_version WHERE version_id = ?", String.class, versionId);
        // 模拟"历史版本当时没有存来源"：把快照列清空（正文一个字节都不动）。
        jdbc.update("UPDATE toolbox_textbook_version SET source_metadata_json = NULL WHERE version_id = ?", versionId);

        ResponseEntity<String> repaired = rest.exchange(
                "/admin/v1/curriculum/versions/" + editionId + "/1.0.0/repair-source-snapshot?basis="
                        + java.net.URLEncoder.encode("同版留存文件 sources/snap-sources.json", StandardCharsets.UTF_8),
                HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(repaired.getStatusCode().value()).isEqualTo(200);
        JsonNode result = TestJson.parse(repaired.getBody()).path("data");
        assertThat(result.path("applied").asBoolean()).isTrue();
        assertThat(result.path("overwroteOriginal").asBoolean()).isFalse();

        // 原正文一字未改。
        String bytesAfter = jdbc.queryForObject(
                "SELECT content_sha256 FROM toolbox_textbook_version WHERE version_id = ?", String.class, versionId);
        assertThat(bytesAfter).isEqualTo(bytesBefore);

        JsonNode snapshot = versionSnapshot(headers, editionId, "1.0.0");
        JsonNode document = TestJson.parse(snapshot.path("snapshotJson").asText());
        assertThat(document.path("snapshot").path("origin").asText()).isEqualTo("repair");
        assertThat(document.path("snapshot").path("repairBasis").asText()).contains("snap-sources.json");
        assertThat(document.path("snapshot").path("capturedAt").asText()).isNotBlank();
        assertThat(document.path("snapshot").path("capturedBy").asText()).isEqualTo("1001");
        // 对不上的来源保留缺失说明，不编造。
        assertThat(document.path("missing").toString()).contains("source:snap-source-absent");

        // 第二次补录：已有快照，**不覆盖**，快照字节不变。
        ResponseEntity<String> again = rest.exchange(
                "/admin/v1/curriculum/versions/" + editionId + "/1.0.0/repair-source-snapshot?basis=again",
                HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(again.getStatusCode().value()).isEqualTo(200);
        JsonNode againResult = TestJson.parse(again.getBody()).path("data");
        assertThat(againResult.path("applied").asBoolean()).isFalse();
        assertThat(againResult.path("reason").asText()).isEqualTo("snapshotAlreadyPresent");
        assertThat(versionSnapshot(headers, editionId, "1.0.0").path("snapshotJson").asText())
                .isEqualTo(snapshot.path("snapshotJson").asText());

        // 没有依据的补录必须被拒绝（否则就是一次"谎称"）。
        ResponseEntity<String> noBasis = rest.exchange(
                "/admin/v1/curriculum/versions/" + editionId + "/1.0.0/repair-source-snapshot",
                HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(noBasis.getStatusCode().value()).isEqualTo(422);
    }

    // ---------- HTTP 辅助 ----------

    /** 发布一条可发布的最小草稿 JSON（可选带来源定位）。 */
    private static String draftJson(String editionId, String text, String pinyin, String sourceIdLiteral,
                                    String locator, int page) {
        return """
                {"editionId":"%s","subject":"chinese","grade":1,"semester":"first",
                 "publisher":"人民教育出版社","series":"统编语文","editionLabel":"2024 年修订版",
                 "sourceNote":"纸质教材附录","coverageDeclaration":"partial",
                 "sourceIds":[%s],
                 "units":[{"unitId":null,"clientUnitId":"cu1","title":"第一单元","order":1}],
                 "lessons":[],
                 "entries":[{"rowId":"r1","entryId":null,"unitId":null,"clientUnitId":"cu1","lessonId":null,
                             "clientLessonId":null,"text":"%s","pinyin":"%s","meaning":null,
                             "listTypes":["writing"],"confirmed":true,
                             "sourceRefs":[{"sourceId":"snap-source-full","locator":"%s","page":%d}]}]}
                """.formatted(editionId, sourceIdLiteral, text, pinyin, locator, page);
    }

    /** 取草稿修订号。 */
    private long draftRevision(HttpHeaders headers, String editionId) {
        return TestJson.parse(rest.exchange("/admin/v1/textbooks/" + editionId + "/draft", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody()).path("data").path("revision").asLong();
    }

    /** 覆盖保存草稿。 */
    private void saveDraft(HttpHeaders headers, String editionId, long revision, String draft) {
        HttpHeaders write = new HttpHeaders();
        write.putAll(headers);
        write.set("If-Match", "\"draft-" + revision + "\"");
        write.set("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(rest.exchange("/admin/v1/textbooks/" + editionId + "/draft", HttpMethod.PUT,
                new HttpEntity<>(draft, write), String.class).getStatusCode().value()).isEqualTo(200);
    }

    /** 发布一册。 */
    private void publish(HttpHeaders headers, String editionId) {
        assertThat(rest.exchange("/admin/v1/textbooks/" + editionId + "/publish", HttpMethod.POST,
                new HttpEntity<>(freshKey(headers)), String.class).getStatusCode().value()).isEqualTo(200);
    }

    /** 触发文件仓储 → 数据库的迁移导入。 */
    private void importFromFiles(HttpHeaders headers) {
        assertThat(rest.exchange("/admin/v1/curriculum/import-from-files", HttpMethod.POST,
                new HttpEntity<>(headers), String.class).getStatusCode().value()).isEqualTo(200);
    }

    /** 读取某版本保存的来源快照。 */
    private JsonNode versionSnapshot(HttpHeaders headers, String editionId, String contentVersion) {
        ResponseEntity<String> response = rest.exchange(
                "/admin/v1/curriculum/versions/" + editionId + "/" + contentVersion + "/source-snapshot",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return TestJson.parse(response.getBody()).path("data");
    }

    /** 导出完整包。 */
    private JsonNode exportPackage(HttpHeaders headers) {
        ResponseEntity<String> response = rest.exchange("/admin/v1/curriculum/export-package",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return TestJson.parse(response.getBody()).path("data");
    }

    /** 在导出包里找一条版本条目；找不到返回 null。 */
    private static JsonNode exportedEntry(JsonNode packageData, String editionId, String contentVersion) {
        for (JsonNode version : packageData.path("versions")) {
            if (editionId.equals(version.path("editionId").asText())
                    && contentVersion.equals(version.path("contentVersion").asText())) {
                return version;
            }
        }
        throw new AssertionError("package entry not found: " + editionId + "@" + contentVersion);
    }

    /** 下载某版本原始字节。 */
    private byte[] versionBytes(HttpHeaders headers, String editionId, String contentVersion) {
        ResponseEntity<byte[]> response = rest.exchange(
                "/admin/v1/curriculum/versions/" + editionId + "/" + contentVersion + "/bytes",
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    /** 读 current 指针。 */
    private String currentVersionId(String editionId) {
        return jdbc.queryForObject("SELECT current_version_id FROM toolbox_textbook WHERE edition_id = ?",
                String.class, editionId);
    }

    /** 统计某册的版本行数。 */
    private int countVersions(String editionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM toolbox_textbook_version WHERE edition_id = ?",
                Integer.class, editionId);
        return count == null ? 0 : count;
    }

    /** 从快照 JSON 里读目录基线。 */
    private static String catalogVersionOf(String snapshotJson) {
        return TestJson.parse(snapshotJson).path("manifestBaseline").path("catalogVersion").asText();
    }

    /** 给写请求带上新的幂等键。 */
    private static HttpHeaders freshKey(HttpHeaders headers) {
        HttpHeaders write = new HttpHeaders();
        write.putAll(headers);
        write.set("Idempotency-Key", UUID.randomUUID().toString());
        return write;
    }

    /** 走完整登录流程。 */
    private HttpHeaders loginAndHeaders() {
        ResponseEntity<String> csrf = rest.getForEntity("/admin/v1/auth/csrf", String.class);
        String preAuth = cookieOf(csrf, "toolbox_admin_login_local");
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.add(HttpHeaders.COOKIE, "toolbox_admin_login_local=" + preAuth);
        loginHeaders.add("X-Toolbox-Admin-Csrf",
                TestJson.parse(csrf.getBody()).path("data").path("csrfToken").asText());
        ResponseEntity<String> login = rest.exchange("/admin/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"account\":\"someone\",\"password\":\"secret\"}", loginHeaders), String.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, "toolbox_admin_local=" + cookieOf(login, "toolbox_admin_local"));
        headers.add("X-Toolbox-Admin-Csrf",
                TestJson.parse(login.getBody()).path("data").path("csrfToken").asText());
        return headers;
    }

    /** 从响应取 Cookie 值。 */
    private static String cookieOf(ResponseEntity<String> response, String name) {
        for (String header : response.getHeaders().getOrDefault(HttpHeaders.SET_COOKIE, List.of())) {
            if (header.startsWith(name + "=")) {
                return header.substring(name.length() + 1).split(";")[0];
            }
        }
        throw new AssertionError("cookie not found: " + name);
    }
}
