package com.yuyutian.mytools.reader.service;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.localfile.entity.LocalDirectory;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.LocalDirectoryMapper;
import com.yuyutian.mytools.localfile.mapper.FileTagMapper;
import com.yuyutian.mytools.reader.mapper.EbookCatalogMapper;
import com.yuyutian.mytools.reader.model.EbookCatalogItem;
import com.yuyutian.mytools.reader.model.EbookCatalogPage;
import com.yuyutian.mytools.reader.model.EbookCover;
import com.yuyutian.mytools.reader.model.EbookIndexResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.IOException;

/**
 * 电子书目录、详情和增量索引服务。
 */
@Service
@RequiredArgsConstructor
public class EbookCatalogService {
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_INDEX_BATCH = 500;
    private final EbookCatalogMapper ebookCatalogMapper;
    private final LocalDirectoryMapper localDirectoryMapper;
    private final FileTagMapper fileTagMapper;
    private final EbookMetadataExtractionService extractionService;

    /**
     * 分页查询电子书，搜索覆盖书名、作者和标签。
     */
    public EbookCatalogPage list(Long directoryId, String keyword, boolean excludeAdult, long page, long pageSize) {
        LocalDirectory directory = requireEbookDirectory(directoryId);
        long safePage = Math.max(1, page);
        long safePageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
        String normalizedKeyword = keyword == null ? "" : keyword.strip();
        long offset = (safePage - 1) * safePageSize;
        List<EbookCatalogItem> items = ebookCatalogMapper.selectPage(
                normalizePath(directory.getDirectoryPath()), normalizedKeyword, excludeAdult, offset, safePageSize);
        attachTags(items);
        long total = ebookCatalogMapper.count(
                normalizePath(directory.getDirectoryPath()), normalizedKeyword, excludeAdult);
        return new EbookCatalogPage(items, total, safePage, safePageSize);
    }

    /**
     * 查询电子书完整详情。
     */
    public EbookCatalogItem detail(Long directoryId, Long fileId, boolean excludeAdult) {
        LocalDirectory directory = requireEbookDirectory(directoryId);
        EbookCatalogItem item = ebookCatalogMapper.selectById(normalizePath(directory.getDirectoryPath()), fileId);
        if (item == null || excludeAdult && Integer.valueOf(1).equals(item.getAdultStatus())
                && Boolean.TRUE.equals(item.getAdultContent())) {
            throw new BusinessException(ErrorCode.FILE_001);
        }
        attachTags(List.of(item));
        return item;
    }

    /**
     * 读取单本电子书经过安全提取的真实封面。
     */
    public EbookCover cover(Long directoryId, Long fileId, boolean excludeAdult) {
        EbookCatalogItem item = detail(directoryId, fileId, excludeAdult);
        try {
            return extractionService.readCover(item.getCoverPath());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.FILE_001);
        }
    }

    /**
     * 对新增或内容发生变化的电子书执行一批确定性元数据索引。
     */
    @Transactional
    public EbookIndexResult index(Long directoryId, int limit) {
        LocalDirectory directory = requireEbookDirectory(directoryId);
        String directoryPath = normalizePath(directory.getDirectoryPath());
        int safeLimit = Math.max(1, Math.min(MAX_INDEX_BATCH, limit));
        ebookCatalogMapper.deleteOrphans();
        List<LocalFile> candidates = ebookCatalogMapper.selectIndexCandidates(
                directoryPath, EbookMetadataExtractionService.METADATA_VERSION, safeLimit);
        int indexed = 0;
        int failed = 0;
        for (LocalFile candidate : candidates) {
            // 每本书独立产出状态，单个坏文件不会阻断整个批次。
            var metadata = extractionService.extract(candidate);
            ebookCatalogMapper.upsert(metadata);
            if ("FAILED".equals(metadata.getStatus())) failed++;
            else indexed++;
        }
        extractionService.cleanupCoverCache(ebookCatalogMapper.selectActiveCoverPaths());
        long remaining = ebookCatalogMapper.countIndexCandidates(
                directoryPath, EbookMetadataExtractionService.METADATA_VERSION);
        return new EbookIndexResult(indexed, failed, remaining);
    }

    private LocalDirectory requireEbookDirectory(Long directoryId) {
        LocalDirectory directory = directoryId == null
                ? localDirectoryMapper.selectByType("EBOOK") : localDirectoryMapper.selectById(directoryId);
        if (directory == null || !"EBOOK".equals(directory.getDirectoryType())) {
            throw new BusinessException(ErrorCode.FILE_010);
        }
        return directory;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) throw new BusinessException(ErrorCode.FILE_010);
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private void attachTags(List<EbookCatalogItem> items) {
        if (items.isEmpty()) return;
        List<Long> fileIds = items.stream().map(EbookCatalogItem::getLocalFileId).toList();
        Map<Long, List<String>> tagsByFile = new LinkedHashMap<>();
        fileTagMapper.selectByFileIds(fileIds).forEach(tag -> {
            if (tag.getFileId() == null || tag.getTagName() == null || tag.getTagName().isBlank()) return;
            List<String> tags = tagsByFile.computeIfAbsent(tag.getFileId(), ignored -> new ArrayList<>());
            String name = tag.getTagName().strip();
            if (name.startsWith("R18-")) {
                tags.removeIf(existing -> existing.startsWith("R18-"));
                tags.add(0, name);
                return;
            }
            if (!tags.contains(name) && tags.size() < 12) tags.add(name);
        });
        items.forEach(item -> {
            List<String> tags = new ArrayList<>(tagsByFile.getOrDefault(item.getLocalFileId(), List.of()));
            tags.sort((left, right) -> Boolean.compare(!left.startsWith("R18-"), !right.startsWith("R18-")));
            item.setTags(List.copyOf(tags));
        });
    }
}
