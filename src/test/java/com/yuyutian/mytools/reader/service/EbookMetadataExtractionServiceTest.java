package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.reader.model.EbookMetadata;
import com.yuyutian.mytools.reader.model.EbookCover;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EbookMetadataExtractionServiceTest {
    @TempDir
    Path temporaryDirectory;

    /**
     * 验证TXT索引复用原文件名标记，但不把标记计入正文统计。
     */
    @Test
    void shouldExtractTxtMetadataWithoutInternalMarker() throws Exception {
        Path path = temporaryDirectory.resolve("download_hash.txt");
        Files.writeString(path, "[Original filename] 示例小说.txt\n第一章 开始\n这是正文。\n第二章 继续\n内容");
        LocalFile file = file(path, "download_hash.txt", "txt");

        EbookMetadata metadata = service().extract(file);

        assertEquals("示例小说", metadata.getTitle());
        assertEquals("READY", metadata.getStatus());
        assertEquals(2, metadata.getChapterCount());
        assertEquals("zh", metadata.getLanguage());
        assertTrue(metadata.getWordCount() > 0);
        assertFalse(metadata.getTitle().contains("Original filename"));
    }

    /**
     * 验证暂未提供专用解析器的格式以部分元数据状态入库。
     */
    @Test
    void shouldKeepUnsupportedParserResultPartial() {
        LocalFile file = file(temporaryDirectory.resolve("sample.docx"), "sample_book.docx", "docx");

        EbookMetadata metadata = service().extract(file);

        assertEquals("sample book", metadata.getTitle());
        assertEquals("PARTIAL", metadata.getStatus());
        assertEquals("filename-v1", metadata.getParserName());
    }

    /**
     * 验证中文历史TXT可以使用GB18030回退解码。
     */
    @Test
    void shouldDecodeGb18030Text() throws Exception {
        Path path = temporaryDirectory.resolve("legacy.txt");
        Files.write(path, "第一章 开始\n中文正文".getBytes(Charset.forName("GB18030")));

        EbookMetadata metadata = service().extract(file(path, "legacy.txt", "txt"));

        assertEquals("READY", metadata.getStatus());
        assertEquals("zh", metadata.getLanguage());
        assertEquals(1, metadata.getChapterCount());
    }

    /**
     * 验证EPUB标准OPF元数据、书脊章节数和真实封面提取。
     */
    @Test
    void shouldExtractEpubPackageAndCover() throws Exception {
        Path path = temporaryDirectory.resolve("sample.epub");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            zip(output, "META-INF/container.xml", """
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="OPS/content.opf"/></rootfiles>
                    </container>
                    """.getBytes(StandardCharsets.UTF_8));
            zip(output, "OPS/content.opf", """
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>EPUB 示例</dc:title><dc:creator>测试作者</dc:creator>
                        <dc:language>zh-CN</dc:language><dc:description>真实简介</dc:description>
                      </metadata>
                      <manifest><item id="cover" href="images/cover%20image.png" media-type="image/png"
                        properties="cover-image"/></manifest>
                      <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
                    </package>
                    """.getBytes(StandardCharsets.UTF_8));
            zip(output, "OPS/images/cover image.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        }

        EbookMetadata metadata = service().extract(file(path, "sample.epub", "epub"));

        assertEquals("READY", metadata.getStatus());
        assertEquals("EPUB 示例", metadata.getTitle());
        assertEquals("测试作者", metadata.getAuthor());
        assertEquals("真实简介", metadata.getDescription());
        assertEquals(2, metadata.getChapterCount());
        assertTrue(Files.isRegularFile(Path.of(metadata.getCoverPath())));
        assertTrue(Path.of(metadata.getCoverPath()).startsWith(temporaryDirectory.resolve("covers")));
        EbookCover cover = service().readCover(metadata.getCoverPath());
        assertEquals("image/png", cover.mediaType());
        assertEquals(8, cover.content().length);
    }

    /**
     * 验证EPUB解析拒绝DOCTYPE和外部实体。
     */
    @Test
    void shouldRejectEpubDoctype() throws Exception {
        Path path = temporaryDirectory.resolve("unsafe.epub");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            zip(output, "META-INF/container.xml", """
                    <!DOCTYPE container [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                    <container><rootfiles><rootfile full-path="&xxe;"/></rootfiles></container>
                    """.getBytes(StandardCharsets.UTF_8));
        }

        EbookMetadata metadata = service().extract(file(path, "unsafe.epub", "epub"));

        assertEquals("FAILED", metadata.getStatus());
    }

    /**
     * 验证封面接口不能读取缓存目录外的任意文件。
     */
    @Test
    void shouldRejectCoverOutsideCacheDirectory() throws Exception {
        Path outside = temporaryDirectory.resolve("outside.png");
        Files.write(outside, new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});

        assertThrows(java.io.IOException.class, () -> service().readCover(outside.toString()));
    }

    /**
     * 验证索引清理只删除专用缓存目录中的失效封面。
     */
    @Test
    void shouldDeleteOnlyOrphanedCachedCovers() throws Exception {
        Path covers = temporaryDirectory.resolve("covers");
        Files.createDirectories(covers);
        Path active = covers.resolve("active.png");
        Path orphan = covers.resolve("orphan.png");
        Files.write(active, new byte[] {1});
        Files.write(orphan, new byte[] {2});

        int deleted = service().cleanupCoverCache(java.util.List.of(active.toString()));

        assertEquals(1, deleted);
        assertTrue(Files.exists(active));
        assertFalse(Files.exists(orphan));
    }

    /**
     * 验证PDF基础信息和页对象数量解析。
     */
    @Test
    void shouldExtractPdfInfoAndPages() throws Exception {
        Path path = temporaryDirectory.resolve("sample.pdf");
        Files.writeString(path, "%PDF-1.7\n/Title (PDF Sample) /Author (Writer)\n"
                + "1 0 obj <</Type /Page>> endobj\n2 0 obj <</Type /Page>> endobj", StandardCharsets.ISO_8859_1);

        EbookMetadata metadata = service().extract(file(path, "sample.pdf", "pdf"));

        assertEquals("PARTIAL", metadata.getStatus());
        assertEquals("PDF Sample", metadata.getTitle());
        assertEquals("Writer", metadata.getAuthor());
        assertEquals(2, metadata.getChapterCount());
    }

    /**
     * 验证MOBI主标题和EXTH作者、更新标题解析。
     */
    @Test
    void shouldExtractMobiHeaderAndExth() throws Exception {
        byte[] bytes = new byte[640];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int recordOffset = 100;
        int mobiOffset = recordOffset + 16;
        buffer.putInt(78, recordOffset);
        putAscii(bytes, mobiOffset, "MOBI");
        buffer.putInt(mobiOffset + 4, 232);
        buffer.putInt(mobiOffset + 84, 500 - recordOffset);
        byte[] fallbackTitle = "Fallback title".getBytes(StandardCharsets.UTF_8);
        buffer.putInt(mobiOffset + 88, fallbackTitle.length);
        System.arraycopy(fallbackTitle, 0, bytes, 500, fallbackTitle.length);
        int exthOffset = mobiOffset + 232;
        putAscii(bytes, exthOffset, "EXTH");
        byte[] author = "MOBI Author".getBytes(StandardCharsets.UTF_8);
        byte[] title = "MOBI Title".getBytes(StandardCharsets.UTF_8);
        buffer.putInt(exthOffset + 4, 12 + 8 + author.length + 8 + title.length);
        buffer.putInt(exthOffset + 8, 2);
        int cursor = exthOffset + 12;
        buffer.putInt(cursor, 100);
        buffer.putInt(cursor + 4, 8 + author.length);
        System.arraycopy(author, 0, bytes, cursor + 8, author.length);
        cursor += 8 + author.length;
        buffer.putInt(cursor, 503);
        buffer.putInt(cursor + 4, 8 + title.length);
        System.arraycopy(title, 0, bytes, cursor + 8, title.length);
        Path path = temporaryDirectory.resolve("sample.mobi");
        Files.write(path, bytes);

        EbookMetadata metadata = service().extract(file(path, "sample.mobi", "mobi"));

        assertEquals("PARTIAL", metadata.getStatus());
        assertEquals("MOBI Title", metadata.getTitle());
        assertEquals("MOBI Author", metadata.getAuthor());
    }

    private EbookMetadataExtractionService service() {
        return new EbookMetadataExtractionService(temporaryDirectory.resolve("covers").toString());
    }

    private void zip(ZipOutputStream output, String name, byte[] content) throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(content);
        output.closeEntry();
    }

    private void putAscii(byte[] target, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    private LocalFile file(Path path, String filename, String extension) {
        LocalFile file = new LocalFile();
        file.setId(9L);
        file.setFilename(filename);
        file.setFilePath(path.toString());
        file.setExtension(extension);
        file.setFileHash("hash");
        return file;
    }
}
