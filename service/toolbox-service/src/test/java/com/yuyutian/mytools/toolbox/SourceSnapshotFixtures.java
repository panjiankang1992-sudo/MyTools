package com.yuyutian.mytools.toolbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 来源快照测试用的内容根目录夹具。
 *
 * 在临时目录里生成一份**结构完整、哈希自洽**的内容包（清单、正文、来源文件），
 * 供构建器单测与导入／导出／恢复集成测试共用，避免各测试各写一份容易漂移的 JSON。
 */
final class SourceSnapshotFixtures {

    /** 夹具里的教材标识。 */
    static final String EDITION_ID = "snap-book";

    /** 夹具里的内容版本号。 */
    static final String CONTENT_VERSION = "1.0.0";

    /** 夹具清单里的目录版本号：用于验证快照**不拿 contentVersion 冒充 catalogVersion**。 */
    static final String CATALOG_VERSION = "2026-10-04.1";

    /** 字段完整的来源标识。 */
    static final String SOURCE_ID = "snap-source-full";

    /** 字段缺失的来源标识。 */
    static final String PARTIAL_SOURCE_ID = "snap-source-partial";

    /** 来源文件的相对路径。 */
    static final String SOURCES_RELATIVE = "sources/snap-sources.json";

    /** 来源文件内容：一条完整记录 + 一条刻意缺字段的记录。 */
    static String sourcesJson() {
        return """
                {"schemaVersion":1,"sources":[
                  {"sourceId":"snap-source-full","kind":"officialEbook","title":"义务教育教科书电子教材",
                   "publisher":"人民教育出版社","isbn":"9787107383007","editionLabel":"2024 年版",
                   "url":"https://example.org/book.pdf","accessBasis":"publicEbookWithTextLayer",
                   "verifiedAt":"2026-10-02","reviewedBy":"cross-source-verification",
                   "reviewNotes":"由教材识字表提取并交叉核对","knownGaps":["本册没有词语表"],
                   "revisionReviews":[{"revision":"2024","reviewedBy":"someone"}],
                   "locators":["目录：第 4—6 页"]},
                  {"sourceId":"snap-source-partial","kind":"officialCatalog","title":"部分字段来源"}
                ]}
                """;
    }

    /** 正文：一册一条内容，条目带明确的 `{sourceId, locator, page}`。 */
    static String bookJson() {
        return """
                {"edition":{"editionId":"snap-book","subject":"chinese","grade":1,"semester":"first",
                   "regionCodes":["jiangsu"],"publisher":"人民教育出版社","series":"统编语文",
                   "editionLabel":"2024 年版","curriculumYear":2022,"isbn":"9787107383007",
                   "sourceIds":["snap-source-full"],"schemaVersion":1,"contentVersion":"1.0.0",
                   "contentSha256":"","reviewStatus":"published","coverageStatus":"partial",
                   "verifiedAt":"2026-10-02T00:00:00Z","publishedAt":"2026-10-02T15:57:08.791Z",
                   "availableEntryCount":1,"expectedEntryCount":null,"missingScopes":[]},
                 "units":[{"unitId":"snap-book-u01","editionId":"snap-book","order":1,"title":"第一单元"}],
                 "lessons":[],
                 "entries":[{"entryId":"snap-book-e0001","entryVersion":"1.0.0","editionId":"snap-book",
                   "unitId":"snap-book-u01","lessonId":null,"order":1,"form":"character",
                   "listTypes":["writing"],"text":"山","pinyinDisplay":"shan",
                   "pinyinSegments":[{"grapheme":"山","syllable":"shan"}],"meaning":null,
                   "partOfSpeech":null,"senseId":null,"context":null,"promptContext":null,
                   "acceptableAnswers":[],"sourceRefs":[{"sourceId":"snap-source-full","locator":"row 2","page":7}],
                   "reviewStatus":"reviewed","blockingIssues":[]}]}
                """;
    }

    /** 清单：声明本册的目录版本、版本号与正文哈希。 */
    static String manifestJson(String bookSha256) {
        return """
                {"schemaVersion":1,"catalogVersion":"%s","publishedAt":"2026-10-04T00:00:00Z",
                 "editions":[{"editionId":"snap-book","subject":"chinese","grade":1,"semester":"first",
                   "regionCodes":["jiangsu"],"publisher":"人民教育出版社","series":"统编语文",
                   "editionLabel":"2024 年版","curriculumYear":2022,"isbn":"9787107383007",
                   "sourceIds":["snap-source-full"],"schemaVersion":1,"contentVersion":"1.0.0",
                   "contentSha256":"%s","reviewStatus":"published","coverageStatus":"partial",
                   "verifiedAt":"2026-10-02T00:00:00Z","publishedAt":"2026-10-02T15:57:08.791Z",
                   "availableEntryCount":1,"expectedEntryCount":null,"missingScopes":[]}]}
                """.formatted(CATALOG_VERSION, bookSha256);
    }

    /**
     * 把整套夹具写进给定根目录。
     *
     * @param root 内容根目录
     * @throws IOException 写入失败
     */
    static void seed(Path root) throws IOException {
        byte[] book = bookJson().getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("sources"));
        Files.createDirectories(root.resolve("catalog"));
        Files.createDirectories(root.resolve("textbooks").resolve(EDITION_ID).resolve(CONTENT_VERSION));
        Files.writeString(root.resolve(SOURCES_RELATIVE), sourcesJson(), StandardCharsets.UTF_8);
        Files.write(root.resolve("textbooks").resolve(EDITION_ID).resolve(CONTENT_VERSION).resolve("book.json"),
                book);
        Files.writeString(root.resolve("catalog").resolve("published.json"), manifestJson(sha256Hex(book)),
                StandardCharsets.UTF_8);
    }

    /**
     * 计算字节的 SHA-256 十六进制值。
     *
     * @param bytes 字节
     * @return 十六进制摘要
     */
    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
