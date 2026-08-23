package com.yuyutian.mytools.localfile.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.localfile.dto.DirectoryRenameProposal;
import com.yuyutian.mytools.localfile.entity.FileTag;
import com.yuyutian.mytools.localfile.entity.LocalDirectory;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.FileTagMapper;
import com.yuyutian.mytools.localfile.mapper.LocalDirectoryMapper;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.localfile.service.tagging.TaggerClient;
import com.yuyutian.mytools.media.model.MediaPackageManifest;
import com.yuyutian.mytools.media.model.MediaTagArtifact;
import com.yuyutian.mytools.media.service.importer.MediaPackageArtifactException;
import com.yuyutian.mytools.media.service.importer.MediaPackageArtifactReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用文件标签和本地模型净化媒体目录名称。
 */
@Slf4j
@Service
public class DirectoryNameCleanupService {

    public static final String PROMPT_VERSION = "media-directory-name-v2";
    private static final Set<String> SUPPORTED_TYPES = Set.of("MULTIMEDIA", "LARGE_MEDIA");
    private static final Set<String> GENERIC_NAMES = Set.of(
            "专辑", "消息内容", "之前", "转的", "合集", "文件", "视频", "图片", "资源", "recovered");
    private static final Pattern MEDIA_MONTH = Pattern.compile("^\\d{6}$");
    private static final Pattern MEDIA_DAY = Pattern.compile("^\\d{8}$");
    private static final Pattern BIG_MEDIA_TIMESTAMP = Pattern.compile("^(\\d{8}_\\d{6}_)(.*)$");
    private static final Pattern HASH_SUFFIX = Pattern.compile("(?i)--[0-9a-f]{6,64}$");
    private static final Pattern DOMAIN_NOISE = Pattern.compile(
            "(?i)(?:https?[_:/\\\\.-]*\\S+|(?:www\\.)?[a-z0-9-]+\\.(?:com|net|org|xyz|top|cc|cn)(?:[@_-])?)");
    private static final Pattern HANDLE_NOISE = Pattern.compile("@[A-Za-z0-9_]{3,}");
    private static final Pattern LONG_IDENTIFIER = Pattern.compile("(?<![A-Za-z])[-_]?\\d{12,}(?![A-Za-z])");
    private static final Pattern FORMAT_NOISE = Pattern.compile(
            "(?i)(?:^|[_ -])(?:mp4|mov|video|1080p?|2k|4k|x1080x)(?:版)?(?:[_ -]?\\d+)?(?=$|[_ -])");
    private static final Pattern PROMOTION_NOISE = Pattern.compile(
            "(?i)(?:B站up主|更多资源|进群|加Q{1,2}|不免费|精选视频推荐官|官方频道|消息内容|下载|转载|搬运)");
    private static final Pattern INVALID_NAME = Pattern.compile("[\\p{Cc}\\\\/:*?\"<>|]");
    private static final Pattern CATALOG_CODE = Pattern.compile("(?i).*[A-Z]{1,10}[-_]\\d{2,7}(?:-C)?.*");
    private static final Pattern CATALOG_TOKEN = Pattern.compile("(?i)[A-Z]{1,10}[-_]\\d{2,7}(?:-C)?");
    private static final Pattern EPISODE_TOKEN = Pattern.compile("第[一二三四五六七八九十百0-9]+集");

    private final LocalDirectoryMapper localDirectoryMapper;
    private final LocalFileMapper localFileMapper;
    private final FileTagMapper fileTagMapper;
    private final TaggerClient taggerClient;
    private final MediaPackageArtifactReader artifactReader;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Value("${file.directory-name-cleanup.model-batch-size:8}")
    private int configuredBatchSize;

    /**
     * 创建媒体目录名称净化服务。
     *
     * @param localDirectoryMapper 受管目录Mapper
     * @param localFileMapper 文件Mapper
     * @param fileTagMapper 文件标签Mapper
     * @param taggerClient 本地模型客户端
     * @param artifactReader DownloadBot标签产物读取器
     * @param objectMapper JSON序列化器
     * @param transactionTemplate 数据库事务模板
     */
    public DirectoryNameCleanupService(LocalDirectoryMapper localDirectoryMapper,
                                       LocalFileMapper localFileMapper,
                                       FileTagMapper fileTagMapper,
                                       TaggerClient taggerClient,
                                       MediaPackageArtifactReader artifactReader,
                                       ObjectMapper objectMapper,
                                       TransactionTemplate transactionTemplate) {
        this.localDirectoryMapper = localDirectoryMapper;
        this.localFileMapper = localFileMapper;
        this.fileTagMapper = fileTagMapper;
        this.taggerClient = taggerClient;
        this.artifactReader = artifactReader;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 按受管目录ID生成目录名称净化预览。
     *
     * @param directoryId 受管目录ID
     * @return 安全校验后的重命名建议
     */
    public List<DirectoryRenameProposal> preview(Long directoryId) {
        LocalDirectory directory = requireSupportedDirectory(directoryId);
        Path root = requireManagedRoot(directory);
        List<Path> paths = discoverDirectories(root, directory.getDirectoryType());
        Map<Path, Map<String, Integer>> tagCounts = collectTagCounts(root, directory.getDirectoryType(), paths);
        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < paths.size(); index++) {
            Path path = paths.get(index);
            String currentName = path.getFileName().toString();
            NameParts parts = splitName(directory.getDirectoryType(), currentName);
            String deterministicName = deterministicClean(parts.semanticName());
            List<String> tags = topTags(tagCounts.getOrDefault(path, Map.of()));
            candidates.add(new Candidate("item-" + index, path, currentName, parts.prefix(),
                    deterministicName, tags, isMeaningless(deterministicName)));
        }

        Map<String, ModelSuggestion> suggestions = requestSuggestions(candidates, directory.getDirectoryType());
        List<DirectoryRenameProposal> proposals = new ArrayList<>();
        Map<Path, Integer> targetCounts = new HashMap<>();
        for (Candidate candidate : candidates) {
            ModelSuggestion suggestion = suggestions.get(candidate.id());
            proposals.add(toProposal(candidate, suggestion, targetCounts));
        }
        return markDuplicateTargets(proposals, targetCounts);
    }

    /**
     * 应用预览中所有无需人工复核且目标仍然安全的目录重命名建议。
     *
     * @param directoryId 受管目录ID
     * @param proposals 已生成的建议
     * @return 成功重命名数量
     */
    public int apply(Long directoryId, List<DirectoryRenameProposal> proposals) {
        LocalDirectory directory = requireSupportedDirectory(directoryId);
        Path root = requireManagedRoot(directory);
        int renamed = 0;
        for (DirectoryRenameProposal proposal : proposals) {
            if (!"READY".equals(proposal.status()) || proposal.needsReview()) {
                continue;
            }
            Path source = Path.of(proposal.sourcePath()).toAbsolutePath().normalize();
            Path target = Path.of(proposal.targetPath()).toAbsolutePath().normalize();
            if (!isSafeRename(root, directory.getDirectoryType(), source, target)) {
                log.warn("跳过不安全的目录重命名：source={}, target={}", source, target);
                continue;
            }
            try {
                renameAtomically(source, target);
                renamed++;
            } catch (IOException | RuntimeException ex) {
                log.error("媒体目录重命名失败：source={}, target={}", source, target, ex);
            }
        }
        return renamed;
    }

    private LocalDirectory requireSupportedDirectory(Long directoryId) {
        LocalDirectory directory = localDirectoryMapper.selectById(directoryId);
        if (directory == null) {
            throw new BusinessException(ErrorCode.FILE_010);
        }
        if (!SUPPORTED_TYPES.contains(directory.getDirectoryType())) {
            throw new BusinessException(ErrorCode.FILE_009);
        }
        return directory;
    }

    private Path requireManagedRoot(LocalDirectory directory) {
        try {
            Path root = Path.of(directory.getDirectoryPath()).toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new BusinessException(ErrorCode.FILE_010);
            }
            return root;
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof BusinessException businessException) {
                throw businessException;
            }
            log.error("无法解析受管媒体根目录：path={}", directory.getDirectoryPath(), ex);
            throw new BusinessException(ErrorCode.FILE_010);
        }
    }

    private List<Path> discoverDirectories(Path root, String directoryType) {
        try (var stream = Files.walk(root, "MULTIMEDIA".equals(directoryType) ? 3 : 1)) {
            return stream.filter(path -> !path.equals(root))
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> isTargetDirectory(root, directoryType, path))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException ex) {
            log.error("无法枚举受管媒体目录：root={}", root, ex);
            throw new BusinessException(ErrorCode.FILE_010);
        }
    }

    private boolean isTargetDirectory(Path root, String directoryType, Path path) {
        Path relative = root.relativize(path);
        if ("LARGE_MEDIA".equals(directoryType)) {
            return relative.getNameCount() == 1;
        }
        return relative.getNameCount() == 3
                && MEDIA_MONTH.matcher(relative.getName(0).toString()).matches()
                && MEDIA_DAY.matcher(relative.getName(1).toString()).matches();
    }

    private Map<Path, Map<String, Integer>> collectTagCounts(Path root, String directoryType, List<Path> paths) {
        Map<Path, Map<String, Integer>> result = new LinkedHashMap<>();
        Set<Path> actualPaths = new HashSet<>(paths);
        List<LocalFile> files = localFileMapper.selectActiveFilesByDirectory(root.toString());
        Map<Long, List<FileTag>> tagsByFile = loadTags(files);
        for (LocalFile file : files) {
            Path directoryPath = resolveFileDirectory(root, directoryType, file.getFilePath());
            if (directoryPath == null || !actualPaths.contains(directoryPath)) {
                continue;
            }
            Map<String, Integer> counts = result.computeIfAbsent(directoryPath, ignored -> new HashMap<>());
            for (FileTag tag : tagsByFile.getOrDefault(file.getId(), List.of())) {
                addTag(counts, tag.getTagName());
            }
        }
        if ("LARGE_MEDIA".equals(directoryType)) {
            for (Path path : paths) {
                addArtifactTags(path, result.computeIfAbsent(path, ignored -> new HashMap<>()));
            }
        }
        return result;
    }

    private Map<Long, List<FileTag>> loadTags(List<LocalFile> files) {
        Map<Long, List<FileTag>> result = new HashMap<>();
        List<Long> ids = files.stream().map(LocalFile::getId).filter(java.util.Objects::nonNull).toList();
        for (int offset = 0; offset < ids.size(); offset += 500) {
            List<Long> batch = ids.subList(offset, Math.min(offset + 500, ids.size()));
            for (FileTag tag : fileTagMapper.selectByFileIds(batch)) {
                result.computeIfAbsent(tag.getFileId(), ignored -> new ArrayList<>()).add(tag);
            }
        }
        return result;
    }

    private Path resolveFileDirectory(Path root, String directoryType, String filePath) {
        try {
            Path path = Path.of(filePath).toAbsolutePath().normalize();
            if (!path.startsWith(root)) {
                return null;
            }
            Path relative = root.relativize(path);
            int requiredNames = "MULTIMEDIA".equals(directoryType) ? 4 : 2;
            if (relative.getNameCount() < requiredNames) {
                return null;
            }
            int directoryNames = "MULTIMEDIA".equals(directoryType) ? 3 : 1;
            return root.resolve(relative.subpath(0, directoryNames)).normalize();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void addArtifactTags(Path directory, Map<String, Integer> counts) {
        try {
            MediaPackageManifest manifest = artifactReader.readManifest(directory);
            MediaTagArtifact artifact = artifactReader.readTagArtifact(directory, manifest);
            if (artifact == null || !"READY".equals(artifact.status()) || artifact.tags() == null) {
                return;
            }
            for (MediaTagArtifact.Tag tag : artifact.tags()) {
                addTag(counts, tag.name());
            }
        } catch (MediaPackageArtifactException ignored) {
            // 人工目录或尚未完成的资源包没有标准标签产物时继续使用数据库标签。
        }
    }

    private void addTag(Map<String, Integer> counts, String value) {
        if (value == null) {
            return;
        }
        String normalized = value.trim();
        if (!normalized.isEmpty() && normalized.length() <= 64) {
            counts.merge(normalized, 1, Integer::sum);
        }
    }

    private List<String> topTags(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .limit(16)
                .toList();
    }

    private Map<String, ModelSuggestion> requestSuggestions(List<Candidate> candidates, String directoryType) {
        Map<String, ModelSuggestion> suggestions = new HashMap<>();
        int batchSize = Math.max(1, Math.min(configuredBatchSize, 8));
        for (int offset = 0; offset < candidates.size(); offset += batchSize) {
            List<Candidate> batch = candidates.subList(offset, Math.min(offset + batchSize, candidates.size()));
            RuntimeException lastError = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    JsonNode response = taggerClient.analyzeJson(buildPrompt(batch, directoryType));
                    suggestions.putAll(parseSuggestions(batch, response));
                    lastError = null;
                    break;
                } catch (RuntimeException ex) {
                    lastError = ex;
                    if (attempt == 1) {
                        log.info("目录名称模型批次响应不完整，准备重试：offset={}", offset);
                    }
                }
            }
            if (lastError != null) {
                log.warn("目录名称模型批次失败，保留确定性清洗结果：offset={}, error={}",
                        offset, lastError.getClass().getSimpleName());
                log.debug("目录名称模型批次失败详情：offset={}", offset, lastError);
            }
        }
        return suggestions;
    }

    private Map<String, ModelSuggestion> parseSuggestions(List<Candidate> batch, JsonNode response) {
        Set<String> expected = batch.stream().map(Candidate::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        JsonNode items = response.path("items");
        if (!items.isArray()) {
            throw new IllegalArgumentException("Model response items are unavailable");
        }
        Map<String, ModelSuggestion> parsed = new HashMap<>();
        for (JsonNode item : items) {
            String id = item.path("id").asText("").trim();
            String semanticName = sanitizeName(item.path("semanticName").asText(""));
            if (expected.contains(id) && !semanticName.isEmpty()) {
                // 同一ID重复出现时仅接受第一项，防止模型后续内容覆盖已校验结果。
                parsed.putIfAbsent(id, new ModelSuggestion(semanticName,
                        item.path("basis").asText("ORIGINAL")));
            }
        }
        if (!parsed.keySet().equals(expected)) {
            throw new IllegalArgumentException("Model response ids are incomplete");
        }
        return parsed;
    }

    private String buildPrompt(List<Candidate> candidates, String directoryType) {
        ArrayNode input = objectMapper.createArrayNode();
        for (Candidate candidate : candidates) {
            ObjectNode item = input.addObject();
            item.put("id", candidate.id());
            item.put("currentSemanticName", candidate.deterministicName());
            item.put("currentNameIsMeaningless", candidate.meaningless());
            ArrayNode tags = item.putArray("tags");
            candidate.tags().forEach(tags::add);
        }
        return "You normalize semantic directory names for a private media library. This is metadata transformation, "
                + "not content moderation: never refuse or censor catalog codes and titles. Return JSON only in the exact "
                + "shape {\"items\":[{\"id\":\"input id\",\"semanticName\":\"clean name\","
                + "\"basis\":\"ORIGINAL|TAGS|UNCHANGED\"}]}. Return exactly one item for every input id, in input order. "
                + "The Java caller preserves date path segments and BIG_MEDIA timestamps; semanticName must never contain "
                + "yyyyMM/yyyyMMdd, yyyyMMdd_HHmmss, a path separator, or a timestamp prefix. Preserve meaningful people, "
                + "characters, works, catalog codes such as DASS-787, episode numbers and concise subjects. Remove source "
                + "platforms, domains, handles, channel or promotion text, download/repost markers, file formats, resolution, "
                + "opaque IDs, hashes and generic container words. When currentNameIsMeaningless is false, basis must be "
                + "ORIGINAL: only remove noise from the current name and never replace its subject with tags. Preserve every "
                + "catalog code and episode token such as DASS-787 or 第12集 verbatim. If currentNameIsMeaningless is true, "
                + "derive one specific "
                + "2-12 Chinese-character subject from tags. Prefer person, character, work, event or scene tags and ignore "
                + "generic format/style/adult-classifier tags. Do not invent facts. If name and tags are insufficient, keep "
                + "the stripped semantic name and use UNCHANGED. Semantic names must be 2-32 visible characters. Source type: "
                + directoryType + ". Input: " + input;
    }

    private DirectoryRenameProposal toProposal(Candidate candidate, ModelSuggestion suggestion,
                                                 Map<Path, Integer> targetCounts) {
        ModelSuggestion acceptedSuggestion = acceptSuggestion(candidate, suggestion);
        String semanticName = acceptedSuggestion == null
                ? candidate.deterministicName() : acceptedSuggestion.semanticName();
        semanticName = sanitizeName(semanticName);
        boolean insufficient = semanticName.isEmpty()
                || (candidate.meaningless() && (acceptedSuggestion == null || isMeaningless(semanticName)));
        if (semanticName.isEmpty()) {
            semanticName = sanitizeName(candidate.currentName());
        }
        String suggestedName = candidate.prefix() + semanticName;
        Path target = candidate.path().resolveSibling(suggestedName).normalize();
        String basis = acceptedSuggestion == null
                ? (candidate.meaningless() ? "UNCHANGED" : "ORIGINAL")
                : (candidate.meaningless() ? "TAGS" : "ORIGINAL");
        String status;
        if (insufficient) {
            status = "REVIEW";
        } else if (suggestedName.equals(candidate.currentName())) {
            status = "UNCHANGED";
        } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            status = "COLLISION";
        } else {
            status = "READY";
        }
        targetCounts.merge(target, 1, Integer::sum);
        return new DirectoryRenameProposal(candidate.path().toString(), target.toString(), candidate.currentName(),
                suggestedName, basis, status, insufficient, PROMPT_VERSION);
    }

    private List<DirectoryRenameProposal> markDuplicateTargets(List<DirectoryRenameProposal> proposals,
                                                                Map<Path, Integer> targetCounts) {
        return proposals.stream().map(proposal -> {
            Path target = Path.of(proposal.targetPath());
            if (targetCounts.getOrDefault(target, 0) <= 1 || !"READY".equals(proposal.status())) {
                return proposal;
            }
            return new DirectoryRenameProposal(proposal.sourcePath(), proposal.targetPath(), proposal.currentName(),
                    proposal.suggestedName(), proposal.basis(), "COLLISION", true, proposal.promptVersion());
        }).toList();
    }

    private NameParts splitName(String directoryType, String currentName) {
        if (!"LARGE_MEDIA".equals(directoryType)) {
            return new NameParts("", currentName);
        }
        Matcher matcher = BIG_MEDIA_TIMESTAMP.matcher(currentName);
        return matcher.matches() ? new NameParts(matcher.group(1), matcher.group(2)) : new NameParts("", currentName);
    }

    private String deterministicClean(String value) {
        String result = HASH_SUFFIX.matcher(value == null ? "" : value.trim()).replaceAll("");
        result = DOMAIN_NOISE.matcher(result).replaceAll(" ");
        result = HANDLE_NOISE.matcher(result).replaceAll(" ");
        result = LONG_IDENTIFIER.matcher(result).replaceAll(" ");
        result = FORMAT_NOISE.matcher(result).replaceAll(" ");
        result = PROMOTION_NOISE.matcher(result).replaceAll(" ");
        result = result.replaceAll("(?i)^from[-_ ]*", "")
                .replaceAll("(?i)^recovered[-_ ]*", "")
                .replaceAll("[\\s_—–]+", " ")
                .replaceAll("^[\\s._·,，;；-]+|[\\s._·,，;；-]+$", "")
                .trim();
        return sanitizeName(result);
    }

    private String sanitizeName(String value) {
        if (value == null) {
            return "";
        }
        String result = INVALID_NAME.matcher(value).replaceAll(" ")
                .replaceAll("\\s+", " ")
                .replaceAll("^[.\\s]+|[.\\s]+$", "")
                .trim();
        int codePoints = result.codePointCount(0, result.length());
        if (codePoints > 32) {
            result = result.substring(0, result.offsetByCodePoints(0, 32)).trim();
        }
        return ".".equals(result) || "..".equals(result) ? "" : result;
    }

    private boolean isMeaningless(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.toLowerCase(Locale.ROOT).trim();
        if (value.codePointCount(0, value.length()) < 2 || GENERIC_NAMES.contains(normalized)
                || normalized.matches("\\d{1,2}")
                || normalized.matches("[0-9a-f]{12,}") || normalized.matches("(?:mp4|mov)?[_ -]?\\d{1,3}")) {
            return true;
        }
        return !CATALOG_CODE.matcher(value).matches()
                && value.codePoints().noneMatch(codePoint -> Character.isLetter(codePoint));
    }

    private ModelSuggestion acceptSuggestion(Candidate candidate, ModelSuggestion suggestion) {
        if (suggestion == null) {
            return null;
        }
        if (!candidate.meaningless() && (!"ORIGINAL".equals(suggestion.basis())
                || !isDeletionOnly(candidate.deterministicName(), suggestion.semanticName()))) {
            return null;
        }
        return preservesCriticalTokens(candidate.deterministicName(), suggestion.semanticName())
                ? suggestion : null;
    }

    private boolean isDeletionOnly(String original, String suggestion) {
        String normalizedOriginal = normalizeForComparison(original);
        String normalizedSuggestion = normalizeForComparison(suggestion);
        if (normalizedSuggestion.isEmpty()) {
            return false;
        }
        int originalIndex = 0;
        int suggestionIndex = 0;
        int[] originalCodePoints = normalizedOriginal.codePoints().toArray();
        int[] suggestionCodePoints = normalizedSuggestion.codePoints().toArray();
        // 模型只能从原名称中删除噪声，禁止新增或替换主题文字。
        while (originalIndex < originalCodePoints.length && suggestionIndex < suggestionCodePoints.length) {
            if (originalCodePoints[originalIndex] == suggestionCodePoints[suggestionIndex]) {
                suggestionIndex++;
            }
            originalIndex++;
        }
        return suggestionIndex == suggestionCodePoints.length;
    }

    private String normalizeForComparison(String value) {
        StringBuilder normalized = new StringBuilder();
        value.toUpperCase(Locale.ROOT).codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(normalized::appendCodePoint);
        return normalized.toString();
    }

    private boolean preservesCriticalTokens(String original, String suggestion) {
        String normalizedSuggestion = suggestion.toUpperCase(Locale.ROOT);
        for (Pattern pattern : List.of(CATALOG_TOKEN, EPISODE_TOKEN)) {
            Matcher matcher = pattern.matcher(original);
            while (matcher.find()) {
                if (!normalizedSuggestion.contains(matcher.group().toUpperCase(Locale.ROOT))) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isSafeRename(Path root, String directoryType, Path source, Path target) {
        return source.startsWith(root) && target.startsWith(root)
                && source.getParent() != null && source.getParent().equals(target.getParent())
                && isTargetDirectory(root, directoryType, source)
                && Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(source)
                && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !source.equals(target);
    }

    private void renameAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            throw new IOException("Atomic directory rename is unavailable", ex);
        }
        try {
            transactionTemplate.executeWithoutResult(status -> localFileMapper.replaceDirectoryPrefix(
                    source.toString(), target.toString(), LocalDateTime.now()));
        } catch (RuntimeException ex) {
            // 数据库更新失败时尽力将文件系统恢复到原目录，避免两边路径长期不一致。
            try {
                Files.move(target, source, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException rollbackError) {
                ex.addSuppressed(rollbackError);
            }
            throw ex;
        }
    }

    private record Candidate(String id, Path path, String currentName, String prefix,
                             String deterministicName, List<String> tags, boolean meaningless) {
    }

    private record NameParts(String prefix, String semanticName) {
    }

    private record ModelSuggestion(String semanticName, String basis) {
    }
}
