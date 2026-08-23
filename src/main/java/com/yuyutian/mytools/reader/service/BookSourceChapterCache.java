package com.yuyutian.mytools.reader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.reader.config.ReaderRuntimeProperties;
import com.yuyutian.mytools.reader.model.BookSourceRuntimeReaderModels;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 用户级网络书源章节正文磁盘缓存。
 */
@Slf4j
@Component
public class BookSourceChapterCache {
    private static final int MAX_CACHE_TEXT_CHARACTERS = 2 * 1024 * 1024;
    private final ObjectMapper objectMapper;
    private final Path root;
    private final Duration ttl;
    private final int maxEntries;

    /**
     * 创建章节缓存。
     *
     * @param objectMapper JSON转换器
     * @param properties 阅读执行器配置
     */
    public BookSourceChapterCache(ObjectMapper objectMapper, ReaderRuntimeProperties properties) {
        this.objectMapper = objectMapper;
        this.root = safeRoot(properties.getChapterCacheDir());
        this.ttl = Duration.ofHours(Math.max(1, properties.getChapterCacheTtlHours()));
        this.maxEntries = Math.max(100, properties.getChapterCacheMaxEntries());
    }

    /**
     * 读取仍在有效期内的章节正文。
     *
     * @param userId 用户ID
     * @param sourceUrl 书源地址
     * @param chapterUrl 章节地址
     * @param chapterIndex 章节序号
     * @return 缓存正文
     */
    public synchronized Optional<BookSourceRuntimeReaderModels.Content> get(Long userId, String sourceUrl,
                                                                            String chapterUrl,
                                                                            int chapterIndex) {
        Path path = cachePath(userId, sourceUrl, chapterUrl, chapterIndex);
        try {
            if (!Files.isRegularFile(path)) return Optional.empty();
            CacheEntry entry = objectMapper.readValue(path.toFile(), CacheEntry.class);
            if (entry.cachedAt() <= 0 || Instant.ofEpochMilli(entry.cachedAt()).plus(ttl).isBefore(Instant.now())
                    || !valid(entry.content())) {
                Files.deleteIfExists(path);
                return Optional.empty();
            }
            Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(Instant.now()));
            return Optional.of(entry.content());
        } catch (Exception exception) {
            deleteQuietly(path);
            return Optional.empty();
        }
    }

    /**
     * 原子写入章节正文缓存。
     *
     * @param userId 用户ID
     * @param sourceUrl 书源地址
     * @param chapterUrl 章节地址
     * @param chapterIndex 章节序号
     * @param content 章节正文
     */
    public synchronized void put(Long userId, String sourceUrl, String chapterUrl, int chapterIndex,
                                 BookSourceRuntimeReaderModels.Content content) {
        if (!valid(content)) return;
        Path path = cachePath(userId, sourceUrl, chapterUrl, chapterIndex);
        Path temporary = path.resolveSibling(path.getFileName() + ".part");
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writeValue(temporary.toFile(), new CacheEntry(Instant.now().toEpochMilli(), content));
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            deleteQuietly(temporary);
            log.warn("Failed to persist reader chapter cache", exception);
        }
    }

    /**
     * 清理过期缓存并限制全局条目数量。
     */
    public synchronized void prune() {
        if (!Files.isDirectory(root)) return;
        Instant expiredBefore = Instant.now().minus(ttl);
        try (Stream<Path> stream = Files.walk(root, 2)) {
            stream.filter(Files::isRegularFile).filter(this::cacheFile).forEach(path -> {
                try {
                    if (Files.getLastModifiedTime(path).toInstant().isBefore(expiredBefore)) Files.deleteIfExists(path);
                } catch (Exception exception) {
                    log.debug("Failed to inspect reader chapter cache entry", exception);
                }
            });
        } catch (Exception exception) {
            log.warn("Failed to prune expired reader chapter cache", exception);
            return;
        }
        List<Path> entries;
        try (Stream<Path> stream = Files.walk(root, 2)) {
            entries = stream.filter(Files::isRegularFile).filter(this::cacheFile)
                    .sorted(Comparator.comparingLong(this::lastModified)).toList();
        } catch (Exception exception) {
            log.warn("Failed to enumerate reader chapter cache", exception);
            return;
        }
        int removeCount = Math.max(0, entries.size() - maxEntries);
        for (int index = 0; index < removeCount; index++) deleteQuietly(entries.get(index));
    }

    private Path cachePath(Long userId, String sourceUrl, String chapterUrl, int chapterIndex) {
        if (userId == null || userId <= 0 || sourceUrl == null || sourceUrl.isBlank()
                || chapterUrl == null || chapterUrl.isBlank() || chapterIndex < 0) {
            throw new IllegalArgumentException("Invalid reader chapter cache identity");
        }
        String digest = digest(sourceUrl.trim() + '\0' + chapterUrl.trim() + '\0' + chapterIndex);
        return root.resolve(String.valueOf(userId)).resolve(digest + ".json");
    }

    private Path safeRoot(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("Reader chapter cache directory is required");
        }
        Path candidate = Path.of(configured).toAbsolutePath().normalize();
        if (candidate.getNameCount() < 2) throw new IllegalArgumentException("Reader chapter cache root is unsafe");
        return candidate;
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean valid(BookSourceRuntimeReaderModels.Content content) {
        return content != null && content.kind() != null && !content.kind().isBlank()
                && content.text() != null && !content.text().isBlank()
                && content.text().length() <= MAX_CACHE_TEXT_CHARACTERS
                && content.imageUrls() != null && content.imageUrls().size() <= 2000;
    }

    private boolean cacheFile(Path path) {
        return path.getFileName().toString().matches("[a-f0-9]{64}\\.json");
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception exception) {
            return Long.MIN_VALUE;
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception exception) {
            log.debug("Failed to delete reader chapter cache entry", exception);
        }
    }

    private record CacheEntry(long cachedAt, BookSourceRuntimeReaderModels.Content content) {
    }
}
