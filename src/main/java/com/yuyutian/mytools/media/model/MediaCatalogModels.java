package com.yuyutian.mytools.media.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * App 多媒体目录的稳定响应模型集合。
 */
public final class MediaCatalogModels {

    private MediaCatalogModels() {
    }

    /** 目录筛选项。 */
    public record DirectoryFilter(String directoryId, String name, long fileCount,
                                  LocalDateTime latestModifiedAt) {
    }

    /** 标签筛选项。 */
    public record TagFilter(String name, long fileCount) {
    }

    /** 筛选项响应。 */
    public record FilterResponse(List<DirectoryFilter> directories, List<TagFilter> tags) {
    }

    /** 不含真实路径的媒体文件条目。 */
    public record CatalogItem(String itemId, String name, String kind, String mimeType, long sizeBytes,
                              LocalDateTime modifiedAt, String thumbnailUrl, List<String> tags,
                              String directoryId, String directoryName) {
    }

    /** 图库分页响应。 */
    public record GalleryResponse(List<CatalogItem> list, long total, int page, int pageSize) {
    }

    /** 视频目录聚合项。 */
    public record VideoDirectory(String directoryId, String name, long fileCount, long totalSizeBytes,
                                 LocalDateTime latestModifiedAt, List<CatalogItem> topItems) {
    }

    /** 视频目录分页响应。 */
    public record VideoDirectoryResponse(List<VideoDirectory> list, String nextPageToken) {
    }

    /** 视频截图。 */
    public record StoryboardFrame(int sequence, long timestampMs, String imageUrl) {
    }

    /** 视频详情响应。 */
    public record VideoDetail(String videoId, String name, long sizeBytes, String format,
                              String videoCodec, String audioCodec, long durationMs, int width, int height,
                              String directoryName, String summary, String description, String descriptionStatus,
                              String thumbnailUrl, List<StoryboardFrame> storyboard) {
    }
}
