package com.yuyutian.mytools.media.service.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.localfile.entity.FileTag;
import com.yuyutian.mytools.localfile.entity.LocalDirectory;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.FileTagMapper;
import com.yuyutian.mytools.localfile.mapper.LocalDirectoryMapper;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.media.model.MediaCatalogModels.CatalogItem;
import com.yuyutian.mytools.media.model.MediaCatalogModels.DirectoryFilter;
import com.yuyutian.mytools.media.model.MediaCatalogModels.FilterResponse;
import com.yuyutian.mytools.media.model.MediaCatalogModels.GalleryResponse;
import com.yuyutian.mytools.media.model.MediaCatalogModels.StoryboardFrame;
import com.yuyutian.mytools.media.model.MediaCatalogModels.TagFilter;
import com.yuyutian.mytools.media.model.MediaCatalogModels.VideoDetail;
import com.yuyutian.mytools.media.model.MediaCatalogModels.VideoDirectory;
import com.yuyutian.mytools.media.model.MediaCatalogModels.VideoDirectoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 将 DownloadBot 与人工扫描产生的本地文件投影为 App 多媒体目录。
 */
@Service
@RequiredArgsConstructor
public class MediaCatalogService {

    private static final Set<String> MEDIA_DIRECTORY_TYPES = Set.of("MULTIMEDIA", "LARGE_MEDIA");
    private static final int FILTER_LIMIT = 500;
    private static final int MAX_ITEM_TAGS = 64;
    private static final int MAX_TAG_LENGTH = 64;
    private static final long MAX_METADATA_BYTES = 256 * 1024L;
    private static final long MAX_DESCRIPTION_BYTES = 16 * 1024L;
    private static final long FILE_INDEX_CACHE_MILLIS = 30_000L;
    private static final String GALLERY_MODE = "gallery";
    private static final String VIDEO_MODE = "video";

    private final LocalDirectoryMapper directoryMapper;
    private final LocalFileMapper fileMapper;
    private final FileTagMapper tagMapper;
    private final ObjectMapper objectMapper;
    private volatile CachedFiles cachedFiles = new CachedFiles(List.of(), 0L);

    /**
     * 查询准确计数的 Top 500 目录和标签。
     *
     * @param keyword 模糊搜索词
     * @param mode 页面模式
     * @param excludeAdult 是否过滤成人内容
     * @return 筛选项
     */
    public FilterResponse filters(String keyword, String mode, boolean excludeAdult) {
        List<IndexedFile> files = matchingFiles(keyword, "", "", excludeAdult, validateMode(mode));
        Map<String, List<IndexedFile>> directories = files.stream()
                .collect(Collectors.groupingBy(IndexedFile::directoryKey));
        List<DirectoryFilter> directoryFilters = directories.entrySet().stream()
                .map(entry -> new DirectoryFilter(directoryId(entry.getKey()), entry.getValue().getFirst().directoryName(),
                        entry.getValue().size(), latest(entry.getValue())))
                // 日期型目录名按字典序倒序即为从近到远，同时保持普通目录的确定性排序。
                .sorted(Comparator.comparing(DirectoryFilter::name,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(FILTER_LIMIT).toList();
        Map<String, Long> tagCounts = files.stream().flatMap(file -> file.tags().stream())
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
        List<TagFilter> tags = tagCounts.entrySet().stream().map(entry -> new TagFilter(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(TagFilter::fileCount).reversed().thenComparing(TagFilter::name))
                .limit(FILTER_LIMIT).toList();
        return new FilterResponse(directoryFilters, tags);
    }

    /** 兼容不启用成人内容过滤的筛选查询。 */
    public FilterResponse filters(String keyword) {
        return filters(keyword, GALLERY_MODE, false);
    }

    /**
     * 查询图库视图；该视图包含所有媒体类型而不只图片。
     *
     * @param directoryId 目录筛选ID
     * @param tag 标签筛选
     * @param keyword 模糊搜索词
     * @param page 页码
     * @param pageSize 页大小
     * @return 图库分页
     */
    public GalleryResponse gallery(String directoryId, String tag, String keyword, int page, int pageSize,
                                   boolean excludeAdult) {
        validatePage(page, pageSize);
        List<IndexedFile> files = matchingFiles(keyword, directoryId, tag, excludeAdult, GALLERY_MODE).stream()
                // 先按完整目录名倒序，再在目录内按文件更新时间倒序，保证分页不会打乱目录顺序。
                .sorted(Comparator.comparing(IndexedFile::directoryName,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing((IndexedFile file) -> file.file().getUpdateTime(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(file -> file.file().getId()))
                .toList();
        int from = Math.min(files.size(), (page - 1) * pageSize);
        int to = Math.min(files.size(), from + pageSize);
        return new GalleryResponse(files.subList(from, to).stream().map(this::item).toList(),
                files.size(), page, pageSize);
    }

    /** 兼容不启用成人内容过滤的图库查询。 */
    public GalleryResponse gallery(String directoryId, String tag, String keyword, int page, int pageSize) {
        return gallery(directoryId, tag, keyword, page, pageSize, false);
    }

    /**
     * 按更新时间倒序查询包含视频的目录及其 Top 3 内容。
     *
     * @param directoryId 目录筛选ID
     * @param tag 标签筛选
     * @param keyword 模糊搜索词
     * @return 视频目录
     */
    public VideoDirectoryResponse videoDirectories(String directoryId, String tag, String keyword,
                                                    boolean excludeAdult) {
        Map<String, List<IndexedFile>> groups = matchingFiles(keyword, directoryId, tag, excludeAdult,
                VIDEO_MODE).stream()
                .collect(Collectors.groupingBy(IndexedFile::directoryKey));
        List<VideoDirectory> values = groups.values().stream()
                .map(files -> {
                    List<IndexedFile> sorted = files.stream().sorted(Comparator.comparing(
                            (IndexedFile file) -> file.file().getUpdateTime(),
                            Comparator.nullsLast(Comparator.reverseOrder()))).toList();
                    return new VideoDirectory(directoryId(sorted.getFirst().directoryKey()),
                            sorted.getFirst().directoryName(), sorted.size(),
                            sorted.stream().mapToLong(file -> safeSize(file.file())).sum(), latest(sorted),
                            sorted.stream().limit(3).map(this::item).toList());
                })
                .sorted(Comparator.comparing(VideoDirectory::latestModifiedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return new VideoDirectoryResponse(values, null);
    }

    /** 兼容不启用成人内容过滤的视频目录查询。 */
    public VideoDirectoryResponse videoDirectories(String directoryId, String tag, String keyword) {
        return videoDirectories(directoryId, tag, keyword, false);
    }

    /**
     * 查询视频目录中的全部文件。
     *
     * @param directoryId 目录ID
     * @return 目录文件
     */
    public List<CatalogItem> videoDirectoryItems(String directoryId, boolean excludeAdult) {
        if (directoryId == null || !directoryId.matches("[0-9a-f]{24}")) {
            throw new BusinessException(ErrorCode.MEDIA_006);
        }
        List<IndexedFile> files = loadFiles().stream()
                .filter(file -> "LARGE_MEDIA".equals(file.directoryType()))
                .filter(file -> directoryId(file.directoryKey()).equals(directoryId))
                .filter(file -> !excludeAdult || !Boolean.TRUE.equals(file.file().getAdultContent()))
                .sorted(Comparator.comparing((IndexedFile file) -> file.file().getUpdateTime(),
                        Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        if (files.isEmpty() || files.stream().noneMatch(file -> kind(file.file()).equals("VIDEO"))) {
            throw new BusinessException(ErrorCode.MEDIA_006);
        }
        return files.stream().map(this::item).toList();
    }

    /** 兼容不启用成人内容过滤的视频目录文件查询。 */
    public List<CatalogItem> videoDirectoryItems(String directoryId) {
        return videoDirectoryItems(directoryId, false);
    }

    /**
     * 读取视频基础信息以及资源包简介和十二张截图。
     *
     * @param videoId 本地文件ID
     * @return 视频详情
     */
    public VideoDetail videoDetail(Long videoId) {
        IndexedFile indexed = requireVideo(videoId);
        LocalFile file = indexed.file();
        Path packageDirectory = optionalPackageDirectory(indexed);
        JsonNode metadata = packageDirectory == null ? objectMapper.missingNode() : readMetadata(packageDirectory);
        JsonNode details = metadata.path("videoMetadata");
        String status = bounded(metadata.path("analysisStatus").asText("PENDING"), 32);
        String summary = bounded(metadata.path("summary").asText(""), 500);
        String description = packageDirectory == null ? "" :
                readDescription(packageDirectory, metadata.path("descriptionFile").asText(""));
        List<StoryboardFrame> storyboard = packageDirectory == null ? List.of() :
                storyboard(metadata, packageDirectory, file.getId());
        return new VideoDetail(String.valueOf(file.getId()), file.getFilename(), safeSize(file),
                bounded(details.path("format").asText(extension(file)), 64),
                bounded(details.path("videoCodec").asText(""), 64),
                bounded(details.path("audioCodec").asText(""), 64),
                Math.max(0L, details.path("durationMs").asLong(0L)),
                Math.max(0, details.path("width").asInt(0)), Math.max(0, details.path("height").asInt(0)),
                indexed.directoryName(), summary, description, status,
                "/api/localfiles/" + file.getId() + "/thumbnail", storyboard);
    }

    /**
     * 安全解析某张视频截图。
     *
     * @param videoId 视频ID
     * @param sequence 截图序号
     * @return 截图路径
     */
    public Path storyboardPath(Long videoId, int sequence) {
        if (sequence < 1 || sequence > 12) throw new BusinessException(ErrorCode.MEDIA_006);
        IndexedFile indexed = requireVideo(videoId);
        Path packageDirectory = safePackageDirectory(indexed);
        JsonNode metadata = readMetadata(packageDirectory);
        JsonNode values = metadata.path("storyboardFiles");
        if (!values.isArray() || values.size() < sequence) throw new BusinessException(ErrorCode.MEDIA_006);
        return requirePackageFile(packageDirectory,
                normalizeStoryboardPath(values.get(sequence - 1).asText("")), true);
    }

    private List<IndexedFile> matchingFiles(String keyword, String directoryId, String tag, boolean excludeAdult,
                                            String mode) {
        String search = normalizeSearch(keyword);
        String selectedDirectory = directoryId == null ? "" : directoryId.trim();
        String selectedTag = tag == null ? "" : tag.trim();
        if (!selectedDirectory.isEmpty() && !selectedDirectory.matches("[0-9a-f]{24}")) {
            throw new BusinessException(ErrorCode.MEDIA_006);
        }
        if (selectedTag.length() > 64) throw new BusinessException(ErrorCode.MEDIA_006);
        List<IndexedFile> visibleFiles = loadFiles().stream()
                .filter(file -> !excludeAdult || !Boolean.TRUE.equals(file.file().getAdultContent())).toList();
        return visibleFiles.stream()
                // 图片页只投影media，视频页只投影big_media，禁止根据文件类型跨数据源归类。
                .filter(file -> VIDEO_MODE.equals(mode)
                        ? "LARGE_MEDIA".equals(file.directoryType())
                        : "MULTIMEDIA".equals(file.directoryType()))
                .filter(file -> selectedDirectory.isEmpty()
                        || directoryId(file.directoryKey()).equals(selectedDirectory))
                .filter(file -> selectedTag.isEmpty() || file.tags().contains(selectedTag))
                .filter(searchable(search)).toList();
    }

    private String validateMode(String mode) {
        String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (!GALLERY_MODE.equals(normalized) && !VIDEO_MODE.equals(normalized)) {
            throw new BusinessException(ErrorCode.MEDIA_006);
        }
        return normalized;
    }

    private Predicate<IndexedFile> searchable(String search) {
        if (search.isEmpty()) return ignored -> true;
        return file -> file.file().getFilename().toLowerCase(Locale.ROOT).contains(search)
                || file.directoryName().toLowerCase(Locale.ROOT).contains(search)
                || file.tags().stream().anyMatch(tag -> tag.toLowerCase(Locale.ROOT).contains(search));
    }

    private List<IndexedFile> loadFiles() {
        long now = System.currentTimeMillis();
        CachedFiles snapshot = cachedFiles;
        if (snapshot.expiresAt() > now) return snapshot.files();
        synchronized (this) {
            // 图片、视频和筛选请求会并发到达，只允许一个请求重建全量索引。
            snapshot = cachedFiles;
            if (snapshot.expiresAt() > now) return snapshot.files();
            List<IndexedFile> files = loadFilesFromDatabase();
            cachedFiles = new CachedFiles(files, System.currentTimeMillis() + FILE_INDEX_CACHE_MILLIS);
            return files;
        }
    }

    private List<IndexedFile> loadFilesFromDatabase() {
        Map<Long, IndexedFileSeed> seeds = new LinkedHashMap<>();
        for (LocalDirectory directory : directoryMapper.selectAll()) {
            if (!MEDIA_DIRECTORY_TYPES.contains(directory.getDirectoryType())) continue;
            Path root = Path.of(directory.getDirectoryPath()).toAbsolutePath().normalize();
            for (LocalFile file : fileMapper.selectActiveFilesByDirectory(root.toString())) {
                if (file.getId() == null || file.getFilePath() == null) continue;
                Path path = Path.of(file.getFilePath()).toAbsolutePath().normalize();
                if (!path.startsWith(root) || path.getParent() == null) continue;
                // 生成的缩略图和故事板仅属于视频详情附件，不作为用户媒体重复展示。
                if (isGeneratedPackageAsset(path)) continue;
                String relative = root.relativize(path.getParent()).toString().replace('\\', '/');
                // 大文件资源包只以根目录下的一级目录作为目录入口，包内子目录不重复出现在目录列表中。
                if ("LARGE_MEDIA".equals(directory.getDirectoryType()) && !relative.isBlank()) {
                    relative = firstPathSegment(relative);
                }
                String directoryKey = root + "\n" + relative;
                String directoryName = relative.isBlank() ? directory.getDirectoryName()
                        : displayDirectoryName(directory.getDirectoryType(), relative);
                seeds.putIfAbsent(file.getId(), new IndexedFileSeed(file, root, directoryKey, directoryName,
                        directory.getDirectoryType()));
            }
        }
        if (seeds.isEmpty()) return List.of();
        Map<Long, List<String>> tags = loadTags(new ArrayList<>(seeds.keySet()));
        return seeds.values().stream().map(seed -> new IndexedFile(seed.file(), seed.root(), seed.directoryKey(),
                seed.directoryName(), seed.directoryType(),
                List.copyOf(tags.getOrDefault(seed.file().getId(), List.of())))).toList();
    }

    private String firstPathSegment(String relativePath) {
        int separator = relativePath.indexOf('/');
        return separator < 0 ? relativePath : relativePath.substring(0, separator);
    }

    private String displayDirectoryName(String directoryType, String relativePath) {
        // 普通媒体目录保留相对根目录的完整层级，便于区分不同月份和日期下的同名目录。
        if ("MULTIMEDIA".equals(directoryType)) return relativePath;
        return Path.of(relativePath).getFileName().toString();
    }

    private Map<Long, List<String>> loadTags(List<Long> fileIds) {
        List<FileTag> values = new ArrayList<>();
        for (int offset = 0; offset < fileIds.size(); offset += 500) {
            values.addAll(tagMapper.selectByFileIds(fileIds.subList(offset, Math.min(fileIds.size(), offset + 500))));
        }
        Map<Long, List<String>> grouped = new LinkedHashMap<>();
        for (FileTag value : values) {
            if (value.getFileId() == null || value.getTagName() == null) continue;
            String name = value.getTagName().trim();
            if (name.isEmpty() || name.length() > MAX_TAG_LENGTH
                    || name.chars().anyMatch(character -> character < 32 || character == 127)) continue;
            List<String> fileTags = grouped.computeIfAbsent(value.getFileId(), ignored -> new ArrayList<>());
            if (name.startsWith("R18-")) {
                fileTags.removeIf(existing -> existing.startsWith("R18-"));
                fileTags.add(0, name);
                if (fileTags.size() > MAX_ITEM_TAGS) fileTags.remove(fileTags.size() - 1);
                continue;
            }
            // 同一文件只返回有限数量的唯一标签，避免历史脏数据导致整个媒体分页无法解析。
            if (fileTags.size() < MAX_ITEM_TAGS && !fileTags.contains(name)) fileTags.add(name);
        }
        // R18结论属于全局安全标签，必须在所有普通业务标签之前展示。
        grouped.values().forEach(fileTags -> fileTags.sort((left, right) -> {
            boolean leftAdult = left.startsWith("R18-");
            boolean rightAdult = right.startsWith("R18-");
            if (leftAdult == rightAdult) return 0;
            return leftAdult ? -1 : 1;
        }));
        return grouped;
    }

    private IndexedFile requireVideo(Long videoId) {
        if (videoId == null || videoId <= 0) throw new BusinessException(ErrorCode.MEDIA_006);
        LocalFile file = fileMapper.selectById(videoId);
        if (file == null) {
            // 兼容只实现目录批量查询的旧 Mapper 适配器，生产数据库始终走单行查询快路径。
            return loadFiles().stream().filter(indexed -> videoId.equals(indexed.file().getId()))
                    .filter(indexed -> "VIDEO".equals(kind(indexed.file()))).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.MEDIA_006));
        }
        if (file.getFilePath() == null || !"VIDEO".equals(kind(file))) {
            throw new BusinessException(ErrorCode.MEDIA_006);
        }
        Path path = Path.of(file.getFilePath()).toAbsolutePath().normalize();
        for (LocalDirectory directory : directoryMapper.selectAll()) {
            if (!MEDIA_DIRECTORY_TYPES.contains(directory.getDirectoryType())) continue;
            Path root = Path.of(directory.getDirectoryPath()).toAbsolutePath().normalize();
            if (!path.startsWith(root) || path.getParent() == null) continue;
            String relative = root.relativize(path.getParent()).toString().replace('\\', '/');
            String directoryKey = root + "\n" + relative;
            String directoryName = relative.isBlank() ? directory.getDirectoryName()
                    : Path.of(relative).getFileName().toString();
            // 详情页不依赖标签，避免单次点击触发整个媒体库及标签表扫描。
            return new IndexedFile(file, root, directoryKey, directoryName, directory.getDirectoryType(), List.of());
        }
        throw new BusinessException(ErrorCode.MEDIA_006);
    }

    private CatalogItem item(IndexedFile indexed) {
        LocalFile file = indexed.file();
        String kind = kind(file);
        String thumbnailUrl = ("IMAGE".equals(kind) || "VIDEO".equals(kind))
                ? "/api/localfiles/" + file.getId() + "/thumbnail" : "";
        return new CatalogItem(String.valueOf(file.getId()), file.getFilename(), kind,
                file.getMimeType() == null ? "" : file.getMimeType(), safeSize(file), file.getUpdateTime(),
                thumbnailUrl, indexed.tags(), directoryId(indexed.directoryKey()), indexed.directoryName());
    }

    private String kind(LocalFile file) {
        String mime = file.getMimeType() == null ? "" : file.getMimeType().toLowerCase(Locale.ROOT);
        if (mime.startsWith("video/")) return "VIDEO";
        if (mime.startsWith("image/")) return "IMAGE";
        if (mime.startsWith("audio/")) return "AUDIO";
        if (mime.equals("text/html") || Set.of("html", "htm").contains(extension(file))) return "WEB";
        if (mime.startsWith("text/")) return "TEXT";
        return "OTHER";
    }

    private Path safePackageDirectory(IndexedFile indexed) {
        Path directory = optionalPackageDirectory(indexed);
        if (directory == null) throw new BusinessException(ErrorCode.MEDIA_006);
        return directory;
    }

    private Path optionalPackageDirectory(IndexedFile indexed) {
        try {
            Path root = indexed.root().toRealPath();
            Path directory = Path.of(indexed.file().getFilePath()).toRealPath().getParent();
            if (directory == null || !directory.startsWith(root) || !Files.isRegularFile(
                    directory.resolve(".ready"), LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            return directory;
        } catch (IOException ex) {
            return null;
        }
    }

    private JsonNode readMetadata(Path packageDirectory) {
        Path path = requirePackageFile(packageDirectory, "metadata.json", false);
        try {
            if (Files.size(path) <= 0 || Files.size(path) > MAX_METADATA_BYTES) {
                throw new BusinessException(ErrorCode.MEDIA_006);
            }
            return objectMapper.readTree(path.toFile());
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.MEDIA_006);
        }
    }

    private String readDescription(Path packageDirectory, String fileName) {
        if (fileName.isBlank()) return "";
        Path path = requirePackageFile(packageDirectory, fileName, false);
        try {
            if (Files.size(path) > MAX_DESCRIPTION_BYTES) throw new BusinessException(ErrorCode.MEDIA_006);
            return bounded(Files.readString(path, StandardCharsets.UTF_8).trim(), 2000);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.MEDIA_006);
        }
    }

    private List<StoryboardFrame> storyboard(JsonNode metadata, Path packageDirectory, Long videoId) {
        JsonNode values = metadata.path("storyboardFiles");
        if (!values.isArray()) return List.of();
        List<StoryboardFrame> result = new ArrayList<>();
        for (int index = 0; index < Math.min(12, values.size()); index++) {
            String fileName = normalizeStoryboardPath(values.get(index).asText(""));
            requirePackageFile(packageDirectory, fileName, true);
            result.add(new StoryboardFrame(index + 1, timestamp(fileName),
                    "/api/app/v1/videos/" + videoId + "/storyboard/" + (index + 1)));
        }
        return List.copyOf(result);
    }

    private boolean isGeneratedPackageAsset(Path path) {
        Path parent = path.getParent();
        if (parent == null) return false;
        if (path.getFileName().toString().equals("thumbnail.jpg")
                && Files.isRegularFile(parent.resolve(".ready"), LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        Path packageDirectory = parent.getParent();
        return parent.getFileName().toString().equals("storyboard") && packageDirectory != null
                && Files.isRegularFile(packageDirectory.resolve(".ready"), LinkOption.NOFOLLOW_LINKS);
    }

    private String normalizeStoryboardPath(String value) {
        if (value != null && value.matches("[0-9]{2}_[0-9]{12}\\.jpg")) {
            // 兼容已经由旧版分析器生成、仅保存文件名的资源包。
            return "storyboard/" + value;
        }
        return value;
    }

    private Path requirePackageFile(Path packageDirectory, String relative, boolean storyboard) {
        if (relative == null || !relative.matches(storyboard
                ? "storyboard/[0-9]{2}_[0-9]{12}\\.jpg" : "[A-Za-z0-9._-]{1,128}")) {
            throw new BusinessException(ErrorCode.MEDIA_006);
        }
        try {
            Path realRoot = packageDirectory.toRealPath();
            Path candidate = realRoot.resolve(relative).toRealPath();
            if (!candidate.startsWith(realRoot) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new BusinessException(ErrorCode.MEDIA_006);
            }
            return candidate;
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.MEDIA_006);
        }
    }

    private long timestamp(String fileName) {
        try {
            int underscore = fileName.lastIndexOf('_');
            return Long.parseLong(fileName.substring(underscore + 1, fileName.length() - 4));
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    private LocalDateTime latest(List<IndexedFile> files) {
        return files.stream().map(file -> file.file().getUpdateTime()).filter(value -> value != null)
                .max(LocalDateTime::compareTo).orElse(null);
    }

    private String directoryId(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String normalizeSearch(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 100 || normalized.chars().anyMatch(character -> character < 32)) {
            throw new BusinessException(ErrorCode.MEDIA_006);
        }
        return normalized;
    }

    private void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new BusinessException(ErrorCode.MEDIA_006);
    }

    private long safeSize(LocalFile file) {
        return file.getFileSize() == null ? 0L : Math.max(0L, file.getFileSize());
    }

    private String extension(LocalFile file) {
        return file.getExtension() == null ? "" : file.getExtension().toLowerCase(Locale.ROOT);
    }

    private String bounded(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private record IndexedFileSeed(LocalFile file, Path root, String directoryKey, String directoryName,
                                   String directoryType) {
    }

    private record IndexedFile(LocalFile file, Path root, String directoryKey, String directoryName,
                               String directoryType, List<String> tags) {
    }

    private record CachedFiles(List<IndexedFile> files, long expiresAt) {
    }
}
