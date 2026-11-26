package com.yuyutian.mytools.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuyutian.mytools.toolbox.service.curriculum.CurriculumCatalog;
import com.yuyutian.mytools.toolbox.service.translation.TranslationDictionary;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * C06：**发布修订释义后，词典真的用新内容**。
 *
 * 已验证的是"目录换代后词典会重建"（信号）与"缓存按代际隔离"（行为）。
 * 这一条补的是最后一环：**内容本身确实换了**。
 *
 * 用**生产同一套文件格式**造夹具（`catalog/published.json` + `textbooks/<id>/<version>/book.json`），
 * 因此它同时也验证了"文件仓储下这条链是通的"。
 */
@SpringBootTest
@ActiveProfiles("test")
class TranslationDictionaryRefreshTest {

    /** 夹具目录必须在 Spring 读取属性**之前**就存在，因此用静态初始化。 */
    private static final Path ROOT = createFixture();

    private static Path bookFile() {
        return ROOT.resolve("textbooks/apple-book/1.0.0/book.json");
    }

    /** 建一册只含 apple 的英语教材夹具。 */
    private static Path createFixture() {
        try {
            Path root = Files.createTempDirectory("tb-dict-refresh");
            writeBook(root, "1.0.0", "苹果", "2026-10-05.1");
            return root;
        } catch (Exception failure) {
            throw new IllegalStateException("夹具建立失败", failure);
        }
    }

    /** 写入正文与清单；`contentSha256` 必须等于**正文字节的 SHA-256**，否则目录加载会被拒绝。 */
    private static void writeBook(Path root, String version, String meaning, String catalogVersion) throws Exception {
        String book = """
                {"edition":{"editionId":"apple-book","subject":"english","grade":3,"semester":"first",
                  "regionCodes":[],"publisher":"夹具出版社","series":"夹具英语","editionLabel":"夹具版",
                  "curriculumYear":null,"isbn":null,"sourceIds":[],"schemaVersion":1,
                  "contentVersion":"%s","contentSha256":"","reviewStatus":"published",
                  "coverageStatus":"partial","missingScopes":[],
                  "publishedAt":"2026-10-05T00:00:00.000Z","availableEntryCount":1},
                 "units":[{"unitId":"apple-book-u01","editionId":"apple-book","order":1,"title":"Unit 1"}],
                 "lessons":[],
                 "entries":[{"entryId":"apple-book-0001","entryVersion":"%s","editionId":"apple-book",
                   "unitId":"apple-book-u01","lessonId":null,"order":0,"form":"word",
                   "listTypes":["vocabulary"],"text":"apple","pinyinDisplay":null,"pinyinSegments":[],
                   "meaning":"%s","partOfSpeech":null,"senseId":null,"context":null,"promptContext":null,
                   "reviewStatus":"reviewed","blockingIssues":[]}]}
                """.formatted(version, version, meaning);
        byte[] bookBytes = book.getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bookBytes));

        Path bookPath = root.resolve("textbooks/apple-book/" + version + "/book.json");
        Files.createDirectories(bookPath.getParent());
        Files.write(bookPath, bookBytes);

        // 清单里的 edition 与正文里的 edition 保持一致，只把哈希填进去。
        String manifest = """
                {"schemaVersion":1,"catalogVersion":"%s","publishedAt":"2026-10-05T00:00:00.000Z",
                 "note":"夹具","sourceCount":1,"editions":[
                   {"editionId":"apple-book","subject":"english","grade":3,"semester":"first",
                    "regionCodes":[],"publisher":"夹具出版社","series":"夹具英语","editionLabel":"夹具版",
                    "curriculumYear":null,"isbn":null,"sourceIds":[],"schemaVersion":1,
                    "contentVersion":"%s","contentSha256":"%s","reviewStatus":"published",
                    "coverageStatus":"partial","missingScopes":[],
                    "publishedAt":"2026-10-05T00:00:00.000Z","availableEntryCount":1}]}
                """.formatted(catalogVersion, version, sha);
        Path catalogDir = root.resolve("catalog");
        Files.createDirectories(catalogDir);
        Files.writeString(catalogDir.resolve("published.json"), manifest, StandardCharsets.UTF_8);
    }

    @DynamicPropertySource
    static void catalogPath(DynamicPropertyRegistry registry) {
        registry.add("toolbox.catalog-path", () -> ROOT.toString());
    }

    @Autowired
    private CurriculumCatalog catalog;

    @Autowired
    private TranslationDictionary dictionary;

    @Test
    @DisplayName("改写正文但不重载目录时，词典仍用旧释义（证明它读的是目录快照）")
    void withoutReloadDictionaryKeepsOldMeaning() throws Exception {
        assertThat(dictionary.find("apple").orElseThrow().meaning()).isEqualTo("苹果");
        // 只改文件、不重载：词典必须**看不到**这次改动。
        writeBook(ROOT, "1.0.0", "苹果（修订）", "2026-10-05.2");
        assertThat(dictionary.find("apple").orElseThrow().meaning())
                .as("未重载目录时不得读到新内容——否则测试会用错误的理由通过").isEqualTo("苹果");
    }

    @Test
    @DisplayName("重载目录（等价于发布后重建快照）后，词典使用新释义（C06）")
    void afterReloadDictionaryUsesNewMeaning() throws Exception {
        assertThat(dictionary.find("apple").orElseThrow().meaning()).isEqualTo("苹果");
        writeBook(ROOT, "1.0.0", "苹果（修订）", "2026-10-05.3");
        // 发布成功的最后一步就是重建目录快照。
        catalog.load();
        assertThat(dictionary.find("apple").orElseThrow().meaning())
                .as("换代后词典必须使用新释义").isEqualTo("苹果（修订）");
    }
}
