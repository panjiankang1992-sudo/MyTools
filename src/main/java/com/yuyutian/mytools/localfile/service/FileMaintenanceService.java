package com.yuyutian.mytools.localfile.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.localfile.dto.FileMaintenanceTask;
import com.yuyutian.mytools.localfile.entity.LocalDirectory;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.LocalDirectoryMapper;
import com.yuyutian.mytools.localfile.mapper.FileMaintenanceLogMapper;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.localfile.service.tagging.TaggerClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * 文件去重和电子书智能整理服务。
 */
@Slf4j
@Service
public class FileMaintenanceService {

    public static final String MODE_EXACT_DEDUP = "EXACT_DEDUP";
    public static final String MODE_EBOOK_ORGANIZE = "EBOOK_ORGANIZE";
    private static final Pattern HASH_SUFFIX = Pattern.compile("--[0-9a-fA-F]{10,64}(?=\\.[^.]+$)");
    private static final Pattern INVALID_FILENAME = Pattern.compile("[\\p{Cc}\\\\/:*?\"<>|]");
    private static final Pattern SOURCE_PREFIX = Pattern.compile(
            "(?i)^(?:downloadbot(?:-pikpak)?-|[0-9]{2,6}[._-])?(?:soushu20[0-9]{2}[_-]com@|sxsy[_-]org)?");
    private static final Pattern SOURCE_NOISE = Pattern.compile(
            "(?i)(?:[_@-]?(?:www\\.)?soushu20[0-9]{2}(?:[_-]com)?|[_@-]?sosdbot|搜书吧)+");
    private static final Pattern TAG_METADATA = Pattern.compile("(?i)_tags_.+?(?:_user)?(?=\\.[^.]+$)");
    private static final Pattern EDITION_NOISE = Pattern.compile(
            "(?i)(?:排版|精校(?:版|全本)?|精排|整理版|多看版|TXT版|加料版?|去毒版|下载版)");
    private static final Pattern QUOTED_TITLE = Pattern.compile("《([^》]{1,180})》");
    private static final String ORIGINAL_FILENAME_MARKER = "[Original filename] ";

    private final LocalFileMapper localFileMapper;
    private final LocalDirectoryMapper localDirectoryMapper;
    private final FileMaintenanceLogMapper maintenanceLogMapper;
    private final TaggerClient taggerClient;
    private final Executor executor;
    private final ResourceStorageGuard resourceStorageGuard;
    private final Map<String, FileMaintenanceTask> tasks = new ConcurrentHashMap<>();
    private final Map<Long, String> activeTasks = new ConcurrentHashMap<>();

    @Value("${file.maintenance.trash-path:/opt/extend/resource/.trash/file-maintenance}")
    private String trashPath;

    /**
     * 创建文件维护服务。
     *
     * @param localFileMapper 文件Mapper
     * @param localDirectoryMapper 目录Mapper
     * @param maintenanceLogMapper 维护操作记录Mapper
     * @param taggerClient 本地模型客户端
     * @param executor 文件扫描执行器
     * @param resourceStorageGuard 资源盘清理保护器
     */
    public FileMaintenanceService(LocalFileMapper localFileMapper,
                                  LocalDirectoryMapper localDirectoryMapper,
                                  FileMaintenanceLogMapper maintenanceLogMapper,
                                  TaggerClient taggerClient,
                                  @Qualifier("localFileScanExecutor") Executor executor,
                                  ResourceStorageGuard resourceStorageGuard) {
        this.localFileMapper = localFileMapper;
        this.localDirectoryMapper = localDirectoryMapper;
        this.maintenanceLogMapper = maintenanceLogMapper;
        this.taggerClient = taggerClient;
        this.executor = executor;
        this.resourceStorageGuard = resourceStorageGuard;
    }

    /**
     * 提交文件维护后台任务。
     *
     * @param directoryId 目录ID
     * @param mode 维护模式
     * @return 任务状态
     */
    public synchronized FileMaintenanceTask submit(Long directoryId, String mode) {
        LocalDirectory directory = localDirectoryMapper.selectById(directoryId);
        if (directory == null) {
            throw new BusinessException(ErrorCode.FILE_010);
        }
        if (!MODE_EXACT_DEDUP.equals(mode) && !MODE_EBOOK_ORGANIZE.equals(mode)) {
            throw new BusinessException(ErrorCode.FILE_009);
        }
        if (MODE_EBOOK_ORGANIZE.equals(mode) && !"EBOOK".equals(directory.getDirectoryType())) {
            throw new BusinessException(ErrorCode.FILE_009);
        }
        // 去重和整理会移动文件并修改数据库，执行前必须确认资源盘可用。
        resourceStorageGuard.requireAvailableForCleanup(Path.of(directory.getDirectoryPath()));

        String activeTaskId = activeTasks.get(directoryId);
        if (activeTaskId != null) {
            FileMaintenanceTask activeTask = tasks.get(activeTaskId);
            if (activeTask != null && ("PENDING".equals(activeTask.getStatus())
                    || "RUNNING".equals(activeTask.getStatus()))) {
                return activeTask;
            }
        }

        FileMaintenanceTask task = new FileMaintenanceTask();
        task.setTaskId(UUID.randomUUID().toString());
        task.setDirectoryId(directoryId);
        task.setMode(mode);
        task.setStatus("PENDING");
        task.setCheckedCount(0);
        task.setDuplicateCount(0);
        task.setRenamedCount(0);
        task.setCreateTime(LocalDateTime.now());
        tasks.put(task.getTaskId(), task);
        activeTasks.put(directoryId, task.getTaskId());
        executor.execute(() -> execute(task, directory));
        return task;
    }

    /**
     * 获取文件维护任务状态。
     *
     * @param taskId 任务ID
     * @return 任务状态
     */
    public FileMaintenanceTask getTask(String taskId) {
        FileMaintenanceTask task = tasks.get(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.FILE_001);
        }
        return task;
    }

    /**
     * 为所有启用目录提交增量MD5去重任务。
     * 仅当目录存在尚未计算MD5的新文件时才创建任务。
     *
     * @return 提交的任务数量
     */
    public int submitIncrementalExactDeduplication() {
        int submittedCount = 0;
        for (LocalDirectory directory : localDirectoryMapper.selectAll()) {
            if (directory.getScanEnabled() == null || directory.getScanEnabled() != 1) {
                continue;
            }
            String directoryPath = Path.of(directory.getDirectoryPath()).toAbsolutePath().normalize().toString();
            // 历史文件已有MD5时不重复读取，只调度真正新增的文件。
            if (localFileMapper.countFilesWithoutMd5(directoryPath) == 0) {
                continue;
            }
            submit(directory.getId(), MODE_EXACT_DEDUP);
            submittedCount++;
        }
        return submittedCount;
    }

    private void execute(FileMaintenanceTask task, LocalDirectory directory) {
        task.setStatus("RUNNING");
        try {
            List<LocalFile> files = localFileMapper.selectActiveFilesByDirectory(
                    Path.of(directory.getDirectoryPath()).toAbsolutePath().normalize().toString());
            exactDeduplicate(task, directory, files);
            if (MODE_EBOOK_ORGANIZE.equals(task.getMode())) {
                List<LocalFile> remaining = localFileMapper.selectActiveFilesByDirectory(
                        Path.of(directory.getDirectoryPath()).toAbsolutePath().normalize().toString());
                smartDeduplicateEbooks(task, directory, remaining);
                remaining = localFileMapper.selectActiveFilesByDirectory(
                        Path.of(directory.getDirectoryPath()).toAbsolutePath().normalize().toString());
                cleanEbookFilenames(task, remaining);
            }
            task.setStatus("COMPLETED");
        } catch (Exception ex) {
            log.error("文件维护任务失败：taskId={}", task.getTaskId(), ex);
            task.setStatus("FAILED");
            task.setErrorMessage(ex.getMessage());
        } finally {
            task.setFinishTime(LocalDateTime.now());
            activeTasks.remove(task.getDirectoryId(), task.getTaskId());
        }
    }

    private void exactDeduplicate(FileMaintenanceTask task, LocalDirectory directory,
                                  List<LocalFile> files) throws Exception {
        Map<String, List<LocalFile>> groups = new LinkedHashMap<>();
        Path managedRoot = Path.of(directory.getDirectoryPath()).toAbsolutePath().normalize();
        for (LocalFile file : files) {
            Path path = Path.of(file.getFilePath());
            if (!Files.isRegularFile(path) || isPublishedMediaPackageAsset(path, managedRoot)) {
                continue;
            }
            String md5 = file.getMd5Hash();
            if (md5 == null || md5.isBlank()) {
                // 仅首次维护读取文件，后续任务直接复用MD5。
                md5 = calculateMd5(path);
                localFileMapper.updateMd5Hash(file.getId(), md5, LocalDateTime.now());
                file.setMd5Hash(md5);
            }
            groups.computeIfAbsent(md5, ignored -> new ArrayList<>()).add(file);
            task.setCheckedCount(task.getCheckedCount() + 1);
        }

        for (List<LocalFile> duplicates : groups.values()) {
            if (duplicates.size() < 2) {
                continue;
            }
            duplicates.sort(Comparator.comparing(LocalFile::getId));
            LocalFile retained = duplicates.get(0);
            for (int index = 1; index < duplicates.size(); index++) {
                LocalFile duplicate = duplicates.get(index);
                quarantine(task, directory, duplicate,
                        "MD5 duplicate of file " + retained.getId(), 1D);
            }
        }
    }

    private boolean isPublishedMediaPackageAsset(Path file, Path managedRoot) {
        Path current = file.toAbsolutePath().normalize().getParent();
        while (current != null && current.startsWith(managedRoot)) {
            if (Files.isRegularFile(current.resolve(".ready"))) {
                return true;
            }
            if (current.equals(managedRoot)) {
                break;
            }
            current = current.getParent();
        }
        return false;
    }

    private void smartDeduplicateEbooks(FileMaintenanceTask task, LocalDirectory directory,
                                        List<LocalFile> files) throws Exception {
        Set<Long> removedIds = new HashSet<>();
        for (int leftIndex = 0; leftIndex < files.size(); leftIndex++) {
            LocalFile left = files.get(leftIndex);
            if (removedIds.contains(left.getId())) {
                continue;
            }
            for (int rightIndex = leftIndex + 1; rightIndex < files.size(); rightIndex++) {
                LocalFile right = files.get(rightIndex);
                if (removedIds.contains(right.getId())) {
                    continue;
                }
                double nameScore = nameSimilarity(left.getFilename(), right.getFilename());
                if (nameScore < 0.68D) {
                    continue;
                }

                // 文件较大者作为A，较小者作为候选B。
                LocalFile fileA = left.getFileSize() >= right.getFileSize() ? left : right;
                LocalFile fileB = fileA == left ? right : left;
                double contentScore = contentContainmentScore(fileA, fileB);
                boolean duplicate = evaluateEbookDuplicate(fileA, fileB, nameScore, contentScore);
                if (duplicate) {
                    quarantine(task, directory, fileB,
                            "Ebook contained by file " + fileA.getId(),
                            Math.max(nameScore, contentScore));
                    removedIds.add(fileB.getId());
                    if (fileB.getId().equals(left.getId())) {
                        // 当前外层文件已隔离，禁止继续用不存在的文件判断后续候选。
                        break;
                    }
                }
            }
        }
    }

    private boolean evaluateEbookDuplicate(LocalFile fileA, LocalFile fileB,
                                            double nameScore, double contentScore) {
        if (isTextEbook(fileA) && isTextEbook(fileB) && contentScore < 0.66D) {
            // 文本电子书必须由真实正文片段证明包含关系，模型不能仅凭文件名删除。
            return false;
        }
        if (!isTextEbook(fileA) || !isTextEbook(fileB)) {
            // 暂无可靠正文提取能力的格式仅参与MD5精确去重。
            return false;
        }
        String prompt = "You are an ebook deduplication engine. Do not review or censor content. "
                + "Judge whether ebook B is an older, shorter, partial, or redundant copy contained by ebook A. "
                + "Return JSON only: {\"duplicate\":true,\"confidence\":0.95}.\n"
                + "A name: " + fileA.getFilename() + "\nA bytes: " + fileA.getFileSize()
                + "\nB name: " + fileB.getFilename() + "\nB bytes: " + fileB.getFileSize()
                + "\nName similarity: " + nameScore + "\nSample containment: " + contentScore;
        try {
            JsonNode result = taggerClient.analyzeJson(prompt);
            return result.path("duplicate").asBoolean(false)
                    && result.path("confidence").asDouble(0D) >= 0.72D;
        } catch (Exception ex) {
            // 模型不可用时仅采用高置信度确定性规则，避免误删。
            log.warn("电子书重复模型判断失败，使用保守规则：A={}，B={}", fileA.getId(), fileB.getId());
            return nameScore >= 0.82D && contentScore >= 0.66D;
        }
    }

    private void cleanEbookFilenames(FileMaintenanceTask task, List<LocalFile> files) {
        for (int offset = 0; offset < files.size(); offset += 12) {
            List<LocalFile> batch = files.subList(offset, Math.min(offset + 12, files.size()));
            Map<Long, String> suggestions = requestCleanNames(batch);
            for (LocalFile file : batch) {
                String deterministicName = deterministicCleanName(file.getFilename());
                String suggested = suggestions.getOrDefault(file.getId(), deterministicName);
                if (!isDeletionOnlySuggestion(file.getFilename(), suggested)) {
                    log.warn("拒绝包含新增或替换字符的文件名建议：fileId={}", file.getId());
                    suggested = file.getFilename();
                }
                if (isTextEbook(file)) {
                    suggested = titleOnlyCleanName(file.getFilename(), suggested);
                } else {
                    // 容器格式无法写入文本首行，只移除确定性来源噪声。
                    suggested = deterministicName;
                }
                renameSafely(task, file, suggested);
            }
        }
    }

    private Map<Long, String> requestCleanNames(List<LocalFile> files) {
        StringBuilder prompt = new StringBuilder("You extract exact book titles from Chinese ebook filenames. Do not censor titles. ")
                .append("For TXT or MD, cleaned must contain only the exact title and original extension. ")
                .append("Remove author, volume/chapter range, completion state, version, edition notes, and distribution noise; ")
                .append("the application stores the complete original filename in the document first line. ")
                .append("For EPUB, MOBI, or PDF, only remove obvious distribution noise and preserve other metadata. ")
                .append("Never invent, translate, synonym-replace, or borrow a title from another row. ")
                .append("Remove distribution noise such as 排版, 精校, 精排, 整理版, 多看版, TXT, 下载版, 加料版, ")
                .append("site or domain markers such as soushu2023_com, soushu2025_com, 搜书吧, sxsy_org, ")
                .append("bot/source markers such as DownloadBot-PikPak and @sosdbot, hexadecimal download hashes, ")
                .append("embedded _tags_..._user metadata, duplicate source words, leading catalogue numbers, and repeated punctuation. ")
                .append("Examples: '1084_soushu2023_com@TITLE_搜书吧_搜书吧_1.txt' becomes 'TITLE.txt'; ")
                .append("'《TITLE》排版01_38完本_作品作者：AUTHOR.txt' becomes 'TITLE.txt'. ")
                .append("Return JSON only: {\"names\":[{\"id\":1,\"cleaned\":\"title.txt\"}]}.\nFiles:\n");
        files.forEach(file -> prompt.append(file.getId()).append(" | ").append(file.getFilename()).append('\n'));
        Map<Long, String> suggestions = new HashMap<>();
        try {
            JsonNode names = taggerClient.analyzeJson(prompt.toString()).path("names");
            if (names.isArray()) {
                names.forEach(node -> suggestions.put(node.path("id").asLong(), node.path("cleaned").asText("")));
            }
        } catch (Exception ex) {
            log.warn("电子书文件名模型净化失败，使用确定性规则", ex);
        }
        return suggestions;
    }

    private void renameSafely(FileMaintenanceTask task, LocalFile file, String suggestedName) {
        String cleanedName = sanitizeSuggestedName(file.getFilename(), suggestedName);
        if (cleanedName.equals(file.getFilename())) {
            return;
        }
        Path source = Path.of(file.getFilePath()).toAbsolutePath().normalize();
        Path target = source.resolveSibling(cleanedName).normalize();
        if (!target.getParent().equals(source.getParent()) || Files.exists(target)) {
            return;
        }
        try {
            boolean contentChanged = false;
            if (isTextEbook(file)) {
                // 先保存原始完整文件名，写入失败时不执行重命名。
                contentChanged = prependOriginalFilename(source, file.getFilename());
            }
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(source, target);
            }
            localFileMapper.updateFileLocation(file.getId(), cleanedName, target.toString(), LocalDateTime.now());
            if (contentChanged) {
                localFileMapper.updateContentIdentity(file.getId(), Files.size(target),
                        calculateHash(target, "SHA-256"), calculateMd5(target), LocalDateTime.now());
            }
            maintenanceLogMapper.insert(task.getTaskId(), file.getId(), "RENAME", source.toString(),
                    target.toString(), "MODEL_FILENAME_CLEANUP", null, LocalDateTime.now());
            task.setRenamedCount(task.getRenamedCount() + 1);
            log.info("电子书文件名已净化：{} -> {}", source, target);
        } catch (Exception ex) {
            log.warn("电子书文件名净化失败：{}", source, ex);
        }
    }

    private void quarantine(FileMaintenanceTask task, LocalDirectory directory, LocalFile file,
                            String reason, double score) throws Exception {
        Path root = Path.of(directory.getDirectoryPath()).toAbsolutePath().normalize();
        Path source = Path.of(file.getFilePath()).toAbsolutePath().normalize();
        if (!source.startsWith(root) || !Files.isRegularFile(source)) {
            return;
        }
        Path relativePath = root.relativize(source);
        Path target = Path.of(trashPath, task.getTaskId(), directory.getDirectoryType())
                .toAbsolutePath().normalize().resolve(relativePath).normalize();
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            target = target.resolveSibling(file.getId() + "-" + target.getFileName());
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target);
        }
        localFileMapper.markDeletedByIds(List.of(file.getId()), LocalDateTime.now());
        maintenanceLogMapper.insert(task.getTaskId(), file.getId(), "QUARANTINE", source.toString(),
                target.toString(), reason, score, LocalDateTime.now());
        task.setDuplicateCount(task.getDuplicateCount() + 1);
        log.info("重复文件已移入隔离区：fileId={}，reason={}，score={}，target={}",
                file.getId(), reason, score, target);
    }

    private String calculateMd5(Path path) throws Exception {
        return calculateHash(path, "MD5");
    }

    private String calculateHash(Path path, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int length;
            while ((length = input.read(buffer)) != -1) {
                digest.update(buffer, 0, length);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private double nameSimilarity(String left, String right) {
        String normalizedLeft = normalizeBookName(left);
        String normalizedRight = normalizeBookName(right);
        if (normalizedLeft.isEmpty() || normalizedRight.isEmpty()) {
            return 0D;
        }
        if (normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft)) {
            return (double) Math.min(normalizedLeft.length(), normalizedRight.length())
                    / Math.max(normalizedLeft.length(), normalizedRight.length());
        }
        Set<String> leftBigrams = bigrams(normalizedLeft);
        Set<String> rightBigrams = bigrams(normalizedRight);
        long intersection = leftBigrams.stream().filter(rightBigrams::contains).count();
        return (2D * intersection) / (leftBigrams.size() + rightBigrams.size());
    }

    private Set<String> bigrams(String text) {
        Set<String> values = new HashSet<>();
        if (text.length() == 1) {
            values.add(text);
            return values;
        }
        for (int index = 0; index < text.length() - 1; index++) {
            values.add(text.substring(index, index + 2));
        }
        return values;
    }

    private String normalizeBookName(String filename) {
        String name = filename.replaceFirst("(?i)\\.[^.]+$", "");
        name = name.replaceAll("--[0-9a-fA-F]{10,64}$", "");
        name = name.replaceAll("(?i)(作品)?作者[：:]?[^_\\-—]+", "");
        name = name.replaceAll("(?i)(排版|整理版|全本|完本|连载中?|章节?|卷|番外|修改|加料|无删减)", "");
        name = name.replaceAll("[0-9０-９]+(?:[_～~·.\\-][0-9０-９]+)*", "");
        return name.replaceAll("[^\\p{IsHan}a-zA-Z]", "").toLowerCase(Locale.ROOT);
    }

    private double contentContainmentScore(LocalFile fileA, LocalFile fileB) {
        try {
            String textA = readTextSample(Path.of(fileA.getFilePath()), fileA.getExtension());
            String textB = readTextSample(Path.of(fileB.getFilePath()), fileB.getExtension());
            if (textA.length() < 600 || textB.length() < 600) {
                return 0D;
            }
            int matches = 0;
            int[] positions = {textB.length() / 10, textB.length() / 2, textB.length() * 8 / 10};
            for (int position : positions) {
                int start = Math.max(0, Math.min(position, textB.length() - 180));
                String fragment = textB.substring(start, start + 180);
                if (textA.contains(fragment)) {
                    matches++;
                }
            }
            return matches / 3D;
        } catch (Exception ex) {
            return 0D;
        }
    }

    private String readTextSample(Path path, String extension) throws IOException {
        if (!"txt".equalsIgnoreCase(extension) && !"md".equalsIgnoreCase(extension)) {
            return "";
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) {
            bytes = input.readNBytes(16 * 1024 * 1024);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (Exception ex) {
            text = Charset.forName("GB18030").decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        }
        return text.replaceAll("\\s+", "");
    }

    private String deterministicCleanName(String filename) {
        String cleaned = HASH_SUFFIX.matcher(filename).replaceAll("");
        cleaned = SOURCE_PREFIX.matcher(cleaned).replaceFirst("");
        cleaned = TAG_METADATA.matcher(cleaned).replaceAll("");
        cleaned = SOURCE_NOISE.matcher(cleaned).replaceAll("");
        cleaned = EDITION_NOISE.matcher(cleaned).replaceAll("");
        return cleaned
                .replaceAll("(?i)(?<![a-z.])TXT(?=[_ .-])", "")
                .replaceAll("[_-]{2,}", "_")
                .replaceAll("[ _-]+(?=\\.[^.]+$)", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private String titleOnlyCleanName(String originalName, String modelSuggestion) {
        String extension = originalName.substring(originalName.lastIndexOf('.'));
        java.util.regex.Matcher quotedTitle = QUOTED_TITLE.matcher(originalName);
        if (quotedTitle.find()) {
            return quotedTitle.group(1).trim() + extension;
        }
        return deterministicCleanName(modelSuggestion);
    }

    private boolean prependOriginalFilename(Path source, String originalFilename) throws IOException {
        byte[] prefix;
        try (InputStream input = Files.newInputStream(source)) {
            prefix = input.readNBytes(4096);
        }
        Charset charset = detectTextCharset(prefix);
        String prefixText = charset.decode(ByteBuffer.wrap(prefix)).toString();
        if (prefixText.contains(ORIGINAL_FILENAME_MARKER)) {
            return false;
        }

        Path temporary = Files.createTempFile(source.getParent(), ".ebook-metadata-", ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary);
             InputStream input = Files.newInputStream(source)) {
            output.write((ORIGINAL_FILENAME_MARKER + originalFilename + System.lineSeparator()).getBytes(charset));
            input.transferTo(output);
        } catch (Exception ex) {
            Files.deleteIfExists(temporary);
            throw ex;
        }
        try {
            Files.move(temporary, source, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, source, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    private Charset detectTextCharset(byte[] prefix) {
        for (int trim = 0; trim <= 3 && prefix.length > trim; trim++) {
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(prefix, 0, prefix.length - trim));
                return StandardCharsets.UTF_8;
            } catch (Exception ignored) {
                // 尝试忽略采样末尾被截断的UTF-8字符。
            }
        }
        return Charset.forName("GB18030");
    }

    private boolean isDeletionOnlySuggestion(String originalName, String suggestedName) {
        if (suggestedName == null || suggestedName.isBlank()) {
            return false;
        }
        String original = comparableFilename(originalName);
        String suggested = comparableFilename(suggestedName);
        if (suggested.length() < Math.max(2, (int) Math.ceil(original.length() * 0.35D))) {
            return false;
        }
        int originalIndex = 0;
        for (int suggestedIndex = 0; suggestedIndex < suggested.length(); suggestedIndex++) {
            char expected = suggested.charAt(suggestedIndex);
            while (originalIndex < original.length() && original.charAt(originalIndex) != expected) {
                originalIndex++;
            }
            if (originalIndex == original.length()) {
                return false;
            }
            originalIndex++;
        }
        return true;
    }

    private String comparableFilename(String filename) {
        return filename.replaceFirst("(?i)\\.[^.]+$", "")
                .replaceAll("[^\\p{IsHan}a-zA-Z0-9]", "")
                .toLowerCase(Locale.ROOT);
    }

    private boolean isTextEbook(LocalFile file) {
        return "txt".equalsIgnoreCase(file.getExtension()) || "md".equalsIgnoreCase(file.getExtension());
    }

    private String sanitizeSuggestedName(String originalName, String suggestedName) {
        String originalExtension = originalName.substring(originalName.lastIndexOf('.'));
        String candidate = suggestedName == null ? "" : suggestedName.trim();
        candidate = INVALID_FILENAME.matcher(candidate).replaceAll(" ").replaceAll("\\s{2,}", " ").trim();
        if (!candidate.toLowerCase(Locale.ROOT).endsWith(originalExtension.toLowerCase(Locale.ROOT))) {
            candidate = candidate.replaceFirst("(?i)\\.[^.]+$", "") + originalExtension;
        }
        if (candidate.isBlank() || candidate.startsWith(".") || candidate.length() > 220) {
            return deterministicCleanName(originalName);
        }
        return candidate;
    }
}
