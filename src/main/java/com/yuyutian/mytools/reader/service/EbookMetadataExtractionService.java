package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.reader.model.EbookMetadata;
import com.yuyutian.mytools.reader.model.EbookCover;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 使用本地文件的确定性信息生成电子书基础元数据。
 */
@Service
public class EbookMetadataExtractionService {
    public static final int METADATA_VERSION = 2;
    private static final long MAX_TEXT_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_EPUB_ENTRY_BYTES = 10L * 1024L * 1024L;
    private static final int MAX_PDF_SCAN_BYTES = 8 * 1024 * 1024;
    private static final int MAX_MOBI_SCAN_BYTES = 4 * 1024 * 1024;
    private static final String ORIGINAL_FILENAME_PREFIX = "[Original filename] ";
    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:第[0-9零一二三四五六七八九十百千万两]+[章节卷回]|chapter\\s+\\d+).*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PDF_PAGE_PATTERN = Pattern.compile("/Type\\s*/Page\\b");
    private static final Pattern PDF_TITLE_PATTERN = Pattern.compile("/Title\\s*\\(([^)]{1,500})\\)");
    private static final Pattern PDF_AUTHOR_PATTERN = Pattern.compile("/Author\\s*\\(([^)]{1,300})\\)");
    private final Path coverCacheDirectory;

    /**
     * 创建电子书元数据解析服务。
     *
     * @param coverCacheDirectory EPUB封面缓存目录
     */
    public EbookMetadataExtractionService(
            @Value("${mytools.ebook.cover-cache-dir:/opt/extend/resource/.mytools/ebook-covers}")
            String coverCacheDirectory) {
        this.coverCacheDirectory = Path.of(coverCacheDirectory).toAbsolutePath().normalize();
    }

    /**
     * 提取单个文件的确定性元数据。
     *
     * @param file 本地文件
     * @return 可幂等写入的元数据
     */
    public EbookMetadata extract(LocalFile file) {
        EbookMetadata metadata = baseMetadata(file);
        String extension = file.getExtension() == null ? "" : file.getExtension().toLowerCase(Locale.ROOT);
        if ("txt".equals(extension) || "md".equals(extension)) return extractText(file, metadata);
        if ("epub".equals(extension)) return extractEpub(file, metadata);
        if ("pdf".equals(extension)) return extractPdf(file, metadata);
        if ("mobi".equals(extension) || "azw3".equals(extension)) return extractMobi(file, metadata);
        return metadata;
    }

    /**
     * 从受控缓存目录读取已经提取的电子书封面。
     *
     * @param coverPath 数据库中的封面路径
     * @return 有界封面内容和媒体类型
     * @throws IOException 路径非法、文件不存在或格式不支持
     */
    public EbookCover readCover(String coverPath) throws IOException {
        if (coverPath == null || coverPath.isBlank()) throw new IOException("Ebook cover is missing");
        Path path = Path.of(coverPath).toAbsolutePath().normalize();
        if (!path.startsWith(coverCacheDirectory) || !Files.isRegularFile(path)
                || Files.size(path) > MAX_EPUB_ENTRY_BYTES) {
            throw new IOException("Ebook cover path is invalid");
        }
        byte[] content = Files.readAllBytes(path);
        String extension = imageExtension(content);
        String mediaType = switch (extension) {
            case "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> throw new IOException("Ebook cover image is unsupported");
        };
        return new EbookCover(content, mediaType);
    }

    /**
     * 删除专用缓存目录中不再被元数据引用的旧封面。
     *
     * @param activeCoverPaths 当前有效封面路径
     * @return 删除文件数量
     */
    public int cleanupCoverCache(java.util.Collection<String> activeCoverPaths) {
        if (!Files.isDirectory(coverCacheDirectory)) return 0;
        Set<Path> active = activeCoverPaths.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> Path.of(value).toAbsolutePath().normalize())
                .filter(value -> value.startsWith(coverCacheDirectory))
                .collect(Collectors.toSet());
        int deleted = 0;
        try (var files = Files.list(coverCacheDirectory)) {
            for (Path path : files.limit(10000).toList()) {
                if (!Files.isRegularFile(path) || active.contains(path.toAbsolutePath().normalize())) continue;
                Files.deleteIfExists(path);
                deleted++;
            }
        } catch (IOException exception) {
            return deleted;
        }
        return deleted;
    }

    private EbookMetadata extractText(LocalFile file, EbookMetadata metadata) {
        Path path = Path.of(file.getFilePath());
        try {
            // 文本统计设置上限，避免索引任务一次把超大文件完整载入内存。
            if (Files.size(path) > MAX_TEXT_BYTES) {
                metadata.setStatus("PARTIAL");
                metadata.setParserName("txt-size-limited-v1");
                return metadata;
            }
            String content = decodeText(Files.readAllBytes(path)).replaceFirst("^\\uFEFF", "");
            String originalFilename = originalFilename(content);
            content = removeOriginalFilenameMarker(content);
            if (!originalFilename.isBlank()) metadata.setTitle(titleFromFilename(originalFilename));
            metadata.setWordCount(content.codePoints().filter(value -> !Character.isWhitespace(value)).count());
            long chapters = CHAPTER_PATTERN.matcher(content).results().count();
            metadata.setChapterCount((int) Math.max(1, Math.min(Integer.MAX_VALUE, chapters)));
            metadata.setLanguage(containsCjk(content) ? "zh" : "");
            metadata.setStatus("READY");
            metadata.setParserName("txt-utf8-v1");
        } catch (IOException | RuntimeException exception) {
            metadata.setStatus("FAILED");
            metadata.setErrorMessage(limitMessage(exception.getMessage()));
            metadata.setParserName("txt-utf8-v1");
        }
        return metadata;
    }

    private EbookMetadata extractEpub(LocalFile file, EbookMetadata metadata) {
        metadata.setParserName("epub-opf-v1");
        try (ZipFile archive = new ZipFile(file.getFilePath(), StandardCharsets.UTF_8)) {
            String packagePath = epubPackagePath(archive);
            Document packageDocument = parseXml(readZipEntry(archive, packagePath, 4L * 1024L * 1024L));
            metadata.setTitle(firstText(packageDocument, "title").orElse(metadata.getTitle()));
            metadata.setAuthor(firstText(packageDocument, "creator").orElse(""));
            metadata.setDescription(firstText(packageDocument, "description").orElse(""));
            metadata.setLanguage(firstText(packageDocument, "language").orElse(""));
            metadata.setChapterCount(Math.max(1, packageDocument.getElementsByTagNameNS("*", "itemref").getLength()));
            String coverEntry = epubCoverEntry(packageDocument, packagePath);
            if (!coverEntry.isBlank()) metadata.setCoverPath(extractEpubCover(archive, coverEntry, file));
            metadata.setStatus("READY");
        } catch (IOException | RuntimeException exception) {
            metadata.setStatus("FAILED");
            metadata.setErrorMessage(limitMessage(exception.getMessage()));
        }
        return metadata;
    }

    private EbookMetadata extractPdf(LocalFile file, EbookMetadata metadata) {
        metadata.setParserName("pdf-basic-v1");
        try (InputStream input = Files.newInputStream(Path.of(file.getFilePath()))) {
            byte[] bytes = input.readNBytes(MAX_PDF_SCAN_BYTES);
            String source = new String(bytes, StandardCharsets.ISO_8859_1);
            metadata.setTitle(pdfValue(PDF_TITLE_PATTERN, source).orElse(metadata.getTitle()));
            metadata.setAuthor(pdfValue(PDF_AUTHOR_PATTERN, source).orElse(""));
            long pages = PDF_PAGE_PATTERN.matcher(source).results().count();
            metadata.setChapterCount((int) Math.max(1, Math.min(Integer.MAX_VALUE, pages)));
            // 基础扫描不处理对象流中的完整元数据与首页渲染，因此明确保持部分状态。
            metadata.setStatus("PARTIAL");
        } catch (IOException | RuntimeException exception) {
            metadata.setStatus("FAILED");
            metadata.setErrorMessage(limitMessage(exception.getMessage()));
        }
        return metadata;
    }

    private EbookMetadata extractMobi(LocalFile file, EbookMetadata metadata) {
        metadata.setParserName("mobi-header-v1");
        try (InputStream input = Files.newInputStream(Path.of(file.getFilePath()))) {
            byte[] bytes = input.readNBytes(MAX_MOBI_SCAN_BYTES);
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            if (bytes.length < 100) throw new IOException("MOBI header is incomplete");
            int recordOffset = buffer.getInt(78);
            int mobiOffset = recordOffset + 16;
            if (!range(bytes, mobiOffset, 92) || !"MOBI".equals(ascii(bytes, mobiOffset, 4))) {
                throw new IOException("MOBI signature is missing");
            }
            int fullNameOffset = buffer.getInt(mobiOffset + 84);
            int fullNameLength = buffer.getInt(mobiOffset + 88);
            if (range(bytes, recordOffset + fullNameOffset, fullNameLength)) {
                String title = decodeBookText(bytes, recordOffset + fullNameOffset, fullNameLength);
                if (!title.isBlank()) metadata.setTitle(title.strip());
            }
            int mobiHeaderLength = buffer.getInt(mobiOffset + 4);
            parseExth(bytes, mobiOffset + mobiHeaderLength, metadata);
            metadata.setStatus("PARTIAL");
        } catch (IOException | RuntimeException exception) {
            metadata.setStatus("FAILED");
            metadata.setErrorMessage(limitMessage(exception.getMessage()));
        }
        return metadata;
    }

    private EbookMetadata baseMetadata(LocalFile file) {
        EbookMetadata metadata = new EbookMetadata();
        metadata.setLocalFileId(file.getId());
        metadata.setFileHash(file.getFileHash());
        metadata.setMetadataVersion(METADATA_VERSION);
        metadata.setStatus("PARTIAL");
        metadata.setTitle(titleFromFilename(file.getFilename()));
        metadata.setAuthor("");
        metadata.setDescription("");
        metadata.setLanguage("");
        metadata.setCategory("");
        metadata.setCompletionStatus("");
        metadata.setParserName("filename-v1");
        metadata.setModelName("");
        metadata.setModelVersion("");
        metadata.setIndexedAt(LocalDateTime.now());
        return metadata;
    }

    private String decodeText(byte[] bytes) throws CharacterCodingException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            // 中文历史TXT常见GB18030，严格UTF-8失败后再使用兼容解码器。
            return Charset.forName("GB18030").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    private String epubPackagePath(ZipFile archive) throws IOException {
        Document container = parseXml(readZipEntry(archive, "META-INF/container.xml", 1024L * 1024L));
        NodeList rootFiles = container.getElementsByTagNameNS("*", "rootfile");
        for (int index = 0; index < rootFiles.getLength(); index++) {
            Element rootFile = (Element) rootFiles.item(index);
            String path = normalizeZipPath("", rootFile.getAttribute("full-path"));
            if (!path.isBlank() && archive.getEntry(path) != null) return path;
        }
        throw new IOException("EPUB package document is missing");
    }

    private String epubCoverEntry(Document document, String packagePath) {
        String coverId = "";
        NodeList metadataItems = document.getElementsByTagNameNS("*", "meta");
        for (int index = 0; index < metadataItems.getLength(); index++) {
            Element item = (Element) metadataItems.item(index);
            if ("cover".equalsIgnoreCase(item.getAttribute("name"))) coverId = item.getAttribute("content");
        }
        NodeList manifestItems = document.getElementsByTagNameNS("*", "item");
        for (int index = 0; index < manifestItems.getLength(); index++) {
            Element item = (Element) manifestItems.item(index);
            boolean explicitCover = item.getAttribute("properties").contains("cover-image");
            boolean legacyCover = !coverId.isBlank() && coverId.equals(item.getAttribute("id"));
            if (!explicitCover && !legacyCover) continue;
            String mediaType = item.getAttribute("media-type");
            if (!mediaType.startsWith("image/")) continue;
            return normalizeZipPath(packagePath, item.getAttribute("href"));
        }
        return "";
    }

    private String extractEpubCover(ZipFile archive, String entryName, LocalFile file) throws IOException {
        byte[] image = readZipEntry(archive, entryName, MAX_EPUB_ENTRY_BYTES);
        String extension = imageExtension(image);
        if (extension.isBlank()) throw new IOException("EPUB cover image is unsupported");
        Files.createDirectories(coverCacheDirectory);
        String fingerprint = file.getFileHash() == null || file.getFileHash().isBlank()
                ? String.valueOf(file.getId()) : file.getFileHash().substring(0, Math.min(16, file.getFileHash().length()));
        Path target = coverCacheDirectory.resolve(file.getId() + "-" + fingerprint + "." + extension).normalize();
        if (!target.startsWith(coverCacheDirectory)) throw new IOException("EPUB cover target is invalid");
        Path temporary = Files.createTempFile(coverCacheDirectory, "ebook-cover-", ".part");
        try {
            Files.write(temporary, image);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return target.toString();
    }

    private byte[] readZipEntry(ZipFile archive, String entryName, long maximumBytes) throws IOException {
        ZipEntry entry = archive.getEntry(entryName);
        if (entry == null || entry.isDirectory() || entry.getSize() > maximumBytes) {
            throw new IOException("EPUB entry is missing or too large");
        }
        try (InputStream input = archive.getInputStream(entry)) {
            byte[] bytes = input.readNBytes((int) maximumBytes + 1);
            if (bytes.length > maximumBytes) throw new IOException("EPUB entry is too large");
            return bytes;
        }
    }

    private Document parseXml(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                /** 将普通XML解析错误转换为索引失败。 */
                @Override
                public void error(org.xml.sax.SAXParseException exception) throws org.xml.sax.SAXException {
                    throw exception;
                }

                /** 将致命XML解析错误转换为索引失败。 */
                @Override
                public void fatalError(org.xml.sax.SAXParseException exception) throws org.xml.sax.SAXException {
                    throw exception;
                }
            });
            return builder.parse(new java.io.ByteArrayInputStream(bytes));
        } catch (Exception exception) {
            throw new IOException("EPUB XML is invalid", exception);
        }
    }

    private Optional<String> firstText(Document document, String localName) {
        NodeList nodes = document.getElementsByTagNameNS("*", localName);
        for (int index = 0; index < nodes.getLength(); index++) {
            String value = nodes.item(index).getTextContent();
            if (value != null && !value.isBlank()) return Optional.of(value.strip());
        }
        return Optional.empty();
    }

    private String normalizeZipPath(String packagePath, String href) {
        if (href == null || href.isBlank() || href.contains("\\")) return "";
        String decodedHref;
        try {
            // OPF中的href允许百分号编码，保留字面加号后再做UTF-8解码。
            decodedHref = java.net.URLDecoder.decode(href.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return "";
        }
        int slash = packagePath.lastIndexOf('/');
        String parent = slash < 0 ? "" : packagePath.substring(0, slash + 1);
        Path normalized = Path.of("/").resolve(parent).resolve(decodedHref).normalize();
        String value = normalized.toString().replace('\\', '/');
        if (!value.startsWith("/") || value.contains("/../")) return "";
        return value.substring(1);
    }

    private Optional<String> pdfValue(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) return Optional.empty();
        String value = matcher.group(1).replace("\\(", "(").replace("\\)", ")")
                .replace("\\n", " ").replace("\\r", " ").strip();
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private void parseExth(byte[] bytes, int offset, EbookMetadata metadata) {
        if (!range(bytes, offset, 12) || !"EXTH".equals(ascii(bytes, offset, 4))) return;
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int recordCount = buffer.getInt(offset + 8);
        int cursor = offset + 12;
        for (int index = 0; index < Math.min(recordCount, 1000); index++) {
            if (!range(bytes, cursor, 8)) return;
            int type = buffer.getInt(cursor);
            int size = buffer.getInt(cursor + 4);
            if (size < 8 || !range(bytes, cursor, size)) return;
            String value = decodeBookText(bytes, cursor + 8, size - 8).strip();
            if (type == 100 && !value.isBlank()) metadata.setAuthor(value);
            if (type == 503 && !value.isBlank()) metadata.setTitle(value);
            cursor += size;
        }
    }

    private String decodeBookText(byte[] bytes, int offset, int length) {
        if (!range(bytes, offset, length)) return "";
        byte[] value = java.util.Arrays.copyOfRange(bytes, offset, offset + length);
        try {
            return decodeText(value).replace("\u0000", "");
        } catch (CharacterCodingException exception) {
            return new String(value, StandardCharsets.ISO_8859_1).replace("\u0000", "");
        }
    }

    private boolean range(byte[] bytes, int offset, int length) {
        return offset >= 0 && length >= 0 && offset <= bytes.length - length;
    }

    private String ascii(byte[] bytes, int offset, int length) {
        return range(bytes, offset, length)
                ? new String(bytes, offset, length, StandardCharsets.US_ASCII) : "";
    }

    private String imageExtension(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) return "jpg";
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && "PNG".equals(ascii(bytes, 1, 3))) return "png";
        if (bytes.length >= 12 && "RIFF".equals(ascii(bytes, 0, 4)) && "WEBP".equals(ascii(bytes, 8, 4))) {
            return "webp";
        }
        return "";
    }

    private String originalFilename(String content) {
        int end = content.indexOf('\n');
        String firstLine = (end < 0 ? content : content.substring(0, end)).stripTrailing();
        if (!firstLine.startsWith(ORIGINAL_FILENAME_PREFIX) || firstLine.length() > 2048) return "";
        return firstLine.substring(ORIGINAL_FILENAME_PREFIX.length()).strip();
    }

    private String removeOriginalFilenameMarker(String content) {
        int end = content.indexOf('\n');
        String firstLine = (end < 0 ? content : content.substring(0, end)).stripTrailing();
        if (!firstLine.startsWith(ORIGINAL_FILENAME_PREFIX) || firstLine.length() > 2048) return content;
        return end < 0 ? "" : content.substring(end + 1);
    }

    private String titleFromFilename(String filename) {
        String value = filename == null ? "" : filename.strip();
        int extensionIndex = value.lastIndexOf('.');
        if (extensionIndex > 0) value = value.substring(0, extensionIndex);
        value = value.replace('_', ' ').replaceAll("\\s+", " ").strip();
        return value.isBlank() ? "Untitled" : value;
    }

    private boolean containsCjk(String content) {
        return content.codePoints().limit(20000).anyMatch(value -> value >= 0x4E00 && value <= 0x9FFF);
    }

    private String limitMessage(String message) {
        String value = message == null ? "metadata extraction failed" : message.toLowerCase(Locale.ROOT);
        return value.substring(0, Math.min(500, value.length()));
    }
}
