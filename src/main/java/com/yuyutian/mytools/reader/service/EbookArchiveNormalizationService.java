package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.localfile.entity.LocalFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 为包含跨平台保留路径的EPUB生成安全且可缓存的等价归档。
 */
@Service
public class EbookArchiveNormalizationService {
    private static final long MAX_ARCHIVE_BYTES = 100L * 1024L * 1024L;
    private static final long MAX_EXPANDED_BYTES = 300L * 1024L * 1024L;
    private static final long MAX_ENTRY_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_ENTRY_COUNT = 5000;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final String CACHE_VERSION = "v4";
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "xml", "opf", "xhtml", "html", "htm", "css", "ncx", "svg", "smil", "js");
    private static final Pattern WINDOWS_DEVICE_NAME = Pattern.compile(
            "(?i)^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\.|$)");
    private final Path archiveCacheDirectory;

    /**
     * 创建EPUB归一化服务。
     *
     * @param archiveCacheDirectory 归一化归档缓存目录。
     */
    public EbookArchiveNormalizationService(
            @Value("$" + "{mytools.ebook.archive-cache-dir:/opt/extend/resource/.mytools/ebook-archives}")
            String archiveCacheDirectory) {
        this.archiveCacheDirectory = Path.of(archiveCacheDirectory).toAbsolutePath().normalize();
    }

    /**
     * 返回可供App读取的原始或归一化EPUB路径。
     *
     * @param file 数据库文件记录。
     * @param source 已通过本地目录边界校验的源文件。
     * @return 不需要改写时返回源文件，否则返回缓存副本。
     * @throws IOException EPUB结构不安全、损坏或无法生成副本。
     */
    public Path prepareReadableArchive(LocalFile file, Path source) throws IOException {
        String extension = file.getExtension() == null ? "" : file.getExtension().toLowerCase(Locale.ROOT);
        if (!"epub".equals(extension)) return source;
        if (!Files.isRegularFile(source) || Files.size(source) <= 0 || Files.size(source) > MAX_ARCHIVE_BYTES) {
            throw new IOException("EPUB archive size is invalid");
        }
        try (ZipFile archive = new ZipFile(source.toFile(), StandardCharsets.UTF_8)) {
            ArchivePlan plan = plan(archive);
            if (!plan.requiresRewrite()) return source;
            Path target = cachePath(file);
            Files.createDirectories(archiveCacheDirectory);
            cleanupObsoleteVersions(file.getId(), target);
            if (validCachedArchive(target)) return target;
            Path temporary = Files.createTempFile(archiveCacheDirectory, "ebook-archive-", ".part");
            try {
                writeNormalizedArchive(archive, plan, temporary);
                moveAtomically(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return target;
        }
    }

    /**
     * 解析票据中已经生成的缓存路径并再次校验目录边界。
     *
     * @param value 票据保存的缓存路径。
     * @return 可读取的规范化缓存文件。
     * @throws IOException 路径越界或文件已经失效。
     */
    public Path requireCachedArchive(String value) throws IOException {
        if (value == null || value.isBlank()) throw new IOException("Ebook archive cache path is missing");
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!path.startsWith(archiveCacheDirectory) || !Files.isRegularFile(path)
                || !path.getFileName().toString().matches("[1-9]\\d{0,18}-v4-[a-f0-9]{16,64}\\.epub")
                || Files.size(path) <= 0 || Files.size(path) > MAX_ARCHIVE_BYTES) {
            throw new IOException("Ebook archive cache path is invalid");
        }
        return path;
    }

    private ArchivePlan plan(ZipFile archive) throws IOException {
        if (archive.size() <= 0 || archive.size() > MAX_ENTRY_COUNT) {
            throw new IOException("EPUB entry count is invalid");
        }
        Map<String, String> paths = new LinkedHashMap<>();
        Set<String> folded = new java.util.HashSet<>();
        long expandedBytes = 0;
        var entries = archive.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String sourceName = normalizedSourceName(entry.getName(), entry.isDirectory());
            if (paths.containsKey(sourceName)) throw new IOException("EPUB contains duplicate paths");
            if (!entry.isDirectory()) {
                long size = entry.getSize();
                long compressedSize = entry.getCompressedSize();
                if (size < 0 || size > MAX_ENTRY_BYTES || compressedSize < 0
                        || size > 0 && (compressedSize == 0 || size / Math.max(1, compressedSize) > 1000)) {
                    throw new IOException("EPUB entry size or compression ratio is invalid");
                }
                expandedBytes += size;
                if (expandedBytes > MAX_EXPANDED_BYTES) throw new IOException("EPUB expanded size is too large");
            }
            String safeName = safeEntryName(sourceName, entry.isDirectory());
            String foldedName = safeName.toLowerCase(Locale.ROOT);
            if (!folded.add(foldedName)) {
                safeName = disambiguate(safeName, sourceName, entry.isDirectory());
                foldedName = safeName.toLowerCase(Locale.ROOT);
                if (!folded.add(foldedName)) throw new IOException("EPUB normalized paths conflict");
            }
            paths.put(sourceName, safeName);
        }
        if (!paths.containsKey("mimetype") || archive.getEntry("mimetype") == null) {
            throw new IOException("EPUB mimetype entry is missing");
        }
        boolean rewrite = paths.entrySet().stream().anyMatch(entry -> !entry.getKey().equals(entry.getValue()));
        return new ArchivePlan(paths, rewrite);
    }

    private String normalizedSourceName(String name, boolean directory) throws IOException {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0 || name.startsWith("/")
                || name.startsWith("\\") || name.matches("^[A-Za-z]:.*") || name.contains("\\")) {
            throw new IOException("EPUB contains an absolute or invalid path");
        }
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFC);
        if (!normalized.equals(name) || normalized.contains("//")) {
            throw new IOException("EPUB path normalization is invalid");
        }
        StringBuilder result = new StringBuilder();
        for (String segment : normalized.split("/")) {
            if (segment.isBlank()) continue;
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IOException("EPUB contains a traversal path");
            }
            if (!result.isEmpty()) result.append('/');
            result.append(segment);
        }
        if (result.isEmpty()) throw new IOException("EPUB contains an empty path");
        if (directory) result.append('/');
        return result.toString();
    }

    private String safeEntryName(String sourceName, boolean directory) {
        String value = directory ? sourceName.substring(0, sourceName.length() - 1) : sourceName;
        List<String> safeSegments = new ArrayList<>();
        for (String segment : value.split("/")) safeSegments.add(safeSegment(segment));
        return String.join("/", safeSegments) + (directory ? "/" : "");
    }

    private String safeSegment(String segment) {
        boolean unsafe = segment.endsWith(".") || segment.endsWith(" ") || segment.indexOf(':') >= 0
                || segment.codePoints().anyMatch(value -> value < 32 || value == 127)
                || WINDOWS_DEVICE_NAME.matcher(segment).find();
        if (!unsafe) return segment;
        int dot = segment.lastIndexOf('.');
        String suffix = dot > 0 && dot < segment.length() - 1 ? segment.substring(dot) : "";
        String stem = suffix.isEmpty() ? segment : segment.substring(0, dot);
        stem = stem.replaceAll("[:\\p{Cntrl}]+", "_").replaceAll("[. ]+$", "");
        if (stem.isBlank() || WINDOWS_DEVICE_NAME.matcher(stem + suffix).find()) stem = "resource";
        return stem + "-" + shortHash(segment) + suffix;
    }

    private String disambiguate(String safeName, String sourceName, boolean directory) {
        String value = directory ? safeName.substring(0, safeName.length() - 1) : safeName;
        int slash = value.lastIndexOf('/');
        String parent = slash < 0 ? "" : value.substring(0, slash + 1);
        String filename = slash < 0 ? value : value.substring(slash + 1);
        int dot = filename.lastIndexOf('.');
        String renamed = dot > 0
                ? filename.substring(0, dot) + "-" + shortHash(sourceName) + filename.substring(dot)
                : filename + "-" + shortHash(sourceName);
        return parent + renamed + (directory ? "/" : "");
    }

    private void writeNormalizedArchive(ZipFile source, ArchivePlan plan, Path target) throws IOException {
        try (OutputStream fileOutput = Files.newOutputStream(target);
             ZipOutputStream output = new ZipOutputStream(fileOutput, StandardCharsets.UTF_8)) {
            // EPUB规范要求mimetype是首个条目且不压缩。
            writeMimetype(source, output);
            List<Map.Entry<String, String>> files = plan.paths().entrySet().stream()
                    .filter(entry -> !entry.getKey().endsWith("/") && !"mimetype".equals(entry.getKey()))
                    .toList();
            for (Map.Entry<String, String> mapping : files) {
                ZipEntry sourceEntry = source.getEntry(mapping.getKey());
                if (sourceEntry == null) throw new IOException("EPUB entry disappeared during normalization");
                ZipEntry targetEntry = new ZipEntry(mapping.getValue());
                output.putNextEntry(targetEntry);
                if (textEntry(mapping.getKey())) {
                    byte[] bytes = readBounded(source, sourceEntry, MAX_ENTRY_BYTES);
                    output.write(rewriteReferences(bytes, mapping.getKey(), mapping.getValue(), plan.paths()));
                } else {
                    copyBounded(source, sourceEntry, output);
                }
                output.closeEntry();
            }
        }
    }

    private void writeMimetype(ZipFile source, ZipOutputStream output) throws IOException {
        ZipEntry entry = source.getEntry("mimetype");
        byte[] content = readBounded(source, entry, 64);
        byte[] expected = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
        if (!java.util.Arrays.equals(content, expected)) throw new IOException("EPUB mimetype is invalid");
        CRC32 crc = new CRC32();
        crc.update(content);
        ZipEntry target = new ZipEntry("mimetype");
        target.setMethod(ZipEntry.STORED);
        target.setSize(content.length);
        target.setCompressedSize(content.length);
        target.setCrc(crc.getValue());
        output.putNextEntry(target);
        output.write(content);
        output.closeEntry();
    }

    private byte[] rewriteReferences(byte[] bytes, String sourcePath, String safeSourcePath,
                                     Map<String, String> paths) throws IOException {
        if (bytes.length > MAX_ENTRY_BYTES) throw new IOException("EPUB text entry is too large");
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<Replacement> replacements = new ArrayList<>();
        for (Map.Entry<String, String> entry : paths.entrySet()) {
            if (entry.getKey().equals(entry.getValue()) || entry.getKey().endsWith("/")) continue;
            String oldRelative = relativePath(sourcePath, entry.getKey());
            String newRelative = relativePath(safeSourcePath, entry.getValue());
            replacements.add(new Replacement(oldRelative, newRelative));
            replacements.add(new Replacement(encodeUriPath(oldRelative), encodeUriPath(newRelative)));
            replacements.add(new Replacement(entry.getKey(), entry.getValue()));
            replacements.add(new Replacement(encodeUriPath(entry.getKey()), encodeUriPath(entry.getValue())));
        }
        List<Replacement> ordered = replacements.stream()
                .filter(value -> !value.source().equals(value.target()))
                .sorted(Comparator.comparingInt((Replacement value) -> value.source().length()).reversed())
                .toList();
        for (Replacement replacement : ordered) {
            text = text.replace(replacement.source(), replacement.target());
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private String relativePath(String document, String target) {
        Path parent = Path.of(document).getParent();
        Path base = parent == null ? Path.of("") : parent;
        return base.relativize(Path.of(target)).toString().replace('\\', '/');
    }

    private String encodeUriPath(String value) {
        return java.util.Arrays.stream(value.split("/", -1))
                .map(segment -> java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8)
                        .replace("+", "%20").replace("*", "%2A").replace("%7E", "~"))
                .collect(java.util.stream.Collectors.joining("/"));
    }

    private boolean textEntry(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 && TEXT_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private byte[] readBounded(ZipFile archive, ZipEntry entry, long limit) throws IOException {
        if (entry == null || entry.isDirectory() || entry.getSize() < 0 || entry.getSize() > limit) {
            throw new IOException("EPUB entry is missing or too large");
        }
        try (InputStream input = archive.getInputStream(entry)) {
            byte[] bytes = input.readNBytes((int) limit + 1);
            if (bytes.length > limit) throw new IOException("EPUB entry is too large");
            return bytes;
        }
    }

    private void copyBounded(ZipFile archive, ZipEntry entry, ZipOutputStream output) throws IOException {
        long remaining = MAX_ENTRY_BYTES + 1;
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (InputStream input = archive.getInputStream(entry)) {
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) return;
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
        throw new IOException("EPUB entry is too large");
    }

    private boolean validCachedArchive(Path path) {
        try (ZipFile archive = new ZipFile(requireCachedArchive(path.toString()).toFile(), StandardCharsets.UTF_8)) {
            ArchivePlan plan = plan(archive);
            return !plan.requiresRewrite()
                    && java.util.Arrays.equals(readBounded(archive, archive.getEntry("mimetype"), 64),
                    "application/epub+zip".getBytes(StandardCharsets.US_ASCII));
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // 无效缓存清理失败时仍按未命中处理。
            }
            return false;
        }
    }

    private Path cachePath(LocalFile file) throws IOException {
        if (file.getId() == null || file.getId() <= 0 || file.getFileHash() == null
                || !file.getFileHash().matches("[a-fA-F0-9]{16,128}")) {
            throw new IOException("EPUB cache identity is invalid");
        }
        String hash = file.getFileHash().toLowerCase(Locale.ROOT);
        return archiveCacheDirectory.resolve(file.getId() + "-" + CACHE_VERSION + "-"
                + hash.substring(0, Math.min(64, hash.length())) + ".epub").normalize();
    }

    private void cleanupObsoleteVersions(Long fileId, Path activePath) {
        try (var paths = Files.list(archiveCacheDirectory)) {
            String pattern = fileId + "-(?:v[1-9]\\d*-)?[a-f0-9]{16,64}\\.epub";
            for (Path path : paths.limit(10000).toList()) {
                if (!path.equals(activePath) && path.getFileName().toString().matches(pattern)
                        && path.toAbsolutePath().normalize().startsWith(archiveCacheDirectory)) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException ignored) {
            // 旧版本回收失败不能阻断当前图书打开。
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ArchivePlan(Map<String, String> paths, boolean requiresRewrite) {
    }

    private record Replacement(String source, String target) {
    }
}
