package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.localfile.entity.LocalFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EbookArchiveNormalizationServiceTest {
    @TempDir
    Path temporaryDirectory;

    /**
     * 验证保留字符路径会被稳定改名，并同步重写容器、OPF和正文中的相对引用。
     */
    @Test
    void shouldNormalizeReservedPathsAndReferences() throws Exception {
        Path source = temporaryDirectory.resolve("unsafe.epub");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(source), StandardCharsets.UTF_8)) {
            mimetype(output);
            zip(output, "META-INF/container.xml", """
                    <container><rootfiles><rootfile full-path="OPS:book/content.opf"/></rootfiles></container>
                    """);
            zip(output, "OPS:book/content.opf", """
                    <package><manifest>
                      <item id="c1" href="chapter%2A%3A1.xhtml" media-type="application/xhtml+xml"/>
                      <item id="cover" href="images/cover%2A%3Amain~slim.png" media-type="image/png"/>
                    </manifest><spine><itemref idref="c1"/></spine></package>
                    """);
            zip(output, "OPS:book/chapter*:1.xhtml",
                    "<html><body><img src=\"images/cover%2A%3Amain~slim.png\"/></body></html>");
            zip(output, "OPS:book/images/cover*:main~slim.png", "image");
        }
        LocalFile file = file(source);
        EbookArchiveNormalizationService service = service();

        Path normalized = service.prepareReadableArchive(file, source);

        assertNotEquals(source, normalized);
        assertEquals(normalized, service.requireCachedArchive(normalized.toString()));
        Path stale = normalized.getParent().resolve(
                "9-v3-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef.epub");
        Files.writeString(stale, "stale");
        assertEquals(normalized, service.prepareReadableArchive(file, source));
        assertFalse(Files.exists(stale));
        byte[] archiveBytes = Files.readAllBytes(normalized);
        assertEquals(0x04034b50, littleEndianInt(archiveBytes, 0));
        assertEquals(0, littleEndianShort(archiveBytes, 8));
        assertEquals("mimetype".length(), littleEndianShort(archiveBytes, 26));
        assertEquals(0, littleEndianShort(archiveBytes, 28));
        try (ZipFile archive = new ZipFile(normalized.toFile(), StandardCharsets.UTF_8)) {
            assertEquals("mimetype", archive.entries().nextElement().getName());
            assertEquals(ZipEntry.STORED, archive.getEntry("mimetype").getMethod());
            String container = text(archive, "META-INF/container.xml");
            String packagePath = attribute(container, "full-path");
            assertFalse(packagePath.contains(":"));
            ZipEntry packageEntry = archive.getEntry(packagePath);
            assertNotNull(packageEntry);
            String opf = text(archive, packagePath);
            String chapterReference = firstAttribute(opf, "href");
            String imageReference = secondAttribute(opf, "href");
            assertFalse(chapterReference.contains(":"));
            assertFalse(imageReference.contains(":"));
            String parent = packagePath.substring(0, packagePath.lastIndexOf('/') + 1);
            String chapterPath = parent + decodeUriPath(chapterReference);
            String imagePath = parent + decodeUriPath(imageReference);
            assertNotNull(archive.getEntry(chapterPath));
            assertNotNull(archive.getEntry(imagePath));
            assertTrue(text(archive, chapterPath).contains(imageReference));
        }
    }

    /**
     * 验证路径已经安全的EPUB不产生冗余副本。
     */
    @Test
    void shouldKeepSafeArchiveUnchanged() throws Exception {
        Path source = temporaryDirectory.resolve("safe.epub");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(source), StandardCharsets.UTF_8)) {
            mimetype(output);
            zip(output, "META-INF/container.xml",
                    "<container><rootfiles><rootfile full-path=\"OPS/content.opf\"/></rootfiles></container>");
            zip(output, "OPS/content.opf", "<package/>");
        }

        assertEquals(source, service().prepareReadableArchive(file(source), source));
    }

    /**
     * 验证目录穿越条目不会因为归一化流程而获得下载票据。
     */
    @Test
    void shouldRejectTraversalEntry() throws Exception {
        Path source = temporaryDirectory.resolve("traversal.epub");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(source), StandardCharsets.UTF_8)) {
            mimetype(output);
            zip(output, "../outside.xhtml", "unsafe");
        }

        assertThrows(java.io.IOException.class, () -> service().prepareReadableArchive(file(source), source));
    }

    private EbookArchiveNormalizationService service() {
        return new EbookArchiveNormalizationService(temporaryDirectory.resolve("archives").toString());
    }

    private LocalFile file(Path source) {
        LocalFile file = new LocalFile();
        file.setId(9L);
        file.setFilePath(source.toString());
        file.setFilename(source.getFileName().toString());
        file.setExtension("epub");
        file.setFileHash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        return file;
    }

    private void mimetype(ZipOutputStream output) throws Exception {
        byte[] content = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(content);
        ZipEntry entry = new ZipEntry("mimetype");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        entry.setCrc(crc.getValue());
        output.putNextEntry(entry);
        output.write(content);
        output.closeEntry();
    }

    private void zip(ZipOutputStream output, String name, String content) throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(content.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private String text(ZipFile archive, String path) throws Exception {
        try (var input = archive.getInputStream(archive.getEntry(path))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String attribute(String source, String name) {
        return source.replaceAll("(?s).*" + name + "=\"([^\"]+)\".*", "$1");
    }

    private String firstAttribute(String source, String name) {
        return source.replaceFirst("(?s).*?" + name + "=\"([^\"]+)\".*", "$1");
    }

    private String secondAttribute(String source, String name) {
        String first = firstAttribute(source, name);
        int offset = source.indexOf(name + "=\"" + first + "\"") + name.length() + first.length() + 3;
        return firstAttribute(source.substring(offset), name);
    }

    private String decodeUriPath(String value) {
        return java.net.URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private int littleEndianShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | (bytes[offset + 1] & 0xff) << 8;
    }

    private int littleEndianInt(byte[] bytes, int offset) {
        return littleEndianShort(bytes, offset) | littleEndianShort(bytes, offset + 2) << 16;
    }
}
