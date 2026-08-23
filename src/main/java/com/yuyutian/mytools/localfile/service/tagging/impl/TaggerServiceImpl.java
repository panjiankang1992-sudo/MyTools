package com.yuyutian.mytools.localfile.service.tagging.impl;

import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.localfile.entity.FileTag;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.FileTagMapper;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.localfile.service.tagging.TaggerClient;
import com.yuyutian.mytools.localfile.service.tagging.TaggerException;
import com.yuyutian.mytools.localfile.service.tagging.TaggerService;
import com.yuyutian.mytools.localfile.service.tagging.MediaTagSidecarTaskRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 打标签服务实现。
 *
 * @author mytools
 * @since 2026-05-04
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaggerServiceImpl implements TaggerService {

    private final LocalFileMapper localFileMapper;
    private final FileTagMapper fileTagMapper;
    private final TaggerClient taggerClient;
    private final ApplicationEventPublisher applicationEventPublisher;

    /** 大文件阈值：100MB */
    private static final long LARGE_FILE_THRESHOLD = 100 * 1024 * 1024;

    /** 每个文本采样片段的字节数。 */
    private static final int TEXT_SAMPLE_BYTES = 48 * 1024;

    @Override
    @Transactional
    public List<FileTag> tagFile(LocalFile file) {
        File physicalFile = new File(file.getFilePath());
        if (!physicalFile.exists()) {
            log.warn("文件不存在: {}", file.getFilePath());
            throw new TaggerException(ErrorCode.FILE_001);
        }

        String mimeType = file.getMimeType();
        List<TaggerClient.TagResult> tagResults;

        try {
            if (isTextFile(mimeType, file.getExtension())) {
                // 文本文件直接读取内容
                tagResults = tagTextFile(physicalFile, file.getFilename());
            } else if (mimeType != null && mimeType.startsWith("image/")) {
                // 图片使用视觉模型识别。
                tagResults = tagMediaFile(physicalFile, file.getThumbnailPath(), mimeType);
            } else if (isVideoOrAudio(mimeType) && hasReadableThumbnail(file.getThumbnailPath())) {
                // 视频存在缩略图时使用视觉模型识别。
                tagResults = tagMediaFile(physicalFile, file.getThumbnailPath(), mimeType);
            } else {
                // 音频、文档及无缩略图视频使用文件元数据生成标签。
                tagResults = tagFileMetadata(file);
            }
        } catch (TaggerException e) {
            // 更新状态为失败
            localFileMapper.updateTaggingStatus(file.getId(), 2, LocalDateTime.now());
            throw e;
        }

        if (tagResults == null || tagResults.isEmpty()) {
            // 空结果不能标记为成功，保留失败状态供后台任务重试。
            localFileMapper.updateTaggingStatus(file.getId(), 2, LocalDateTime.now());
            throw new TaggerException(ErrorCode.FILE_008);
        }

        // 保存标签
        List<FileTag> savedTags = saveTags(file.getId(), tagResults);

        // 更新状态为成功
        localFileMapper.updateTaggingStatus(file.getId(), 1, LocalDateTime.now());

        // 旧标签成功后只发出旁路事件，监听器失败不会改变当前事务的权威结果。
        applicationEventPublisher.publishEvent(new MediaTagSidecarTaskRequested(
                file.getId(), file.getFilename(), file.getFilePath(), file.getThumbnailPath(),
                file.getMimeType(), file.getFileHash()));

        return savedTags;
    }

    @Override
    @Transactional
    public int processUntaggedFiles(int batchSize) {
        List<LocalFile> untaggedFiles = localFileMapper.selectUntaggedFiles(batchSize);
        if (untaggedFiles.isEmpty()) {
            return 0;
        }

        int successCount = 0;
        for (LocalFile file : untaggedFiles) {
            try {
                tagFile(file);
                successCount++;
            } catch (Exception e) {
                log.error("处理文件失败: {} - {}", file.getFilePath(), e.getMessage());
                // 继续处理下一个文件
            }
        }

        return successCount;
    }

    @Override
    @Transactional
    public int processAdultClassifications(int batchSize) {
        int successCount = 0;
        for (LocalFile file : localFileMapper.selectAdultClassificationCandidates(batchSize)) {
            try {
                TaggerClient.AdultResult result = classifyAdult(file);
                localFileMapper.updateAdultClassification(file.getId(), 1, result.adult(), result.confidence());
                saveAdultClassificationTag(file.getId(), result);
                successCount++;
            } catch (Exception ex) {
                localFileMapper.updateAdultClassification(file.getId(), 2, null, null);
                log.error("成人内容识别失败: {} - {}", file.getFilePath(), ex.getMessage());
            }
        }
        return successCount;
    }

    private void saveAdultClassificationTag(Long fileId, TaggerClient.AdultResult result) {
        LocalDateTime now = LocalDateTime.now();
        FileTag tag = new FileTag();
        tag.setFileId(fileId);
        tag.setTagName(Boolean.TRUE.equals(result.adult()) ? "R18-是" : "R18-否");
        tag.setTagType("adult");
        tag.setConfidence(result.confidence());
        tag.setTaggingTime(now);
        tag.setCreateTime(now);
        // 每个资源只能存在一个R18结论，重跑模型时原子替换旧结论。
        fileTagMapper.deleteAdultClassificationByFileId(fileId);
        fileTagMapper.insert(tag);
    }

    private TaggerClient.AdultResult classifyAdult(LocalFile file) throws IOException {
        File physicalFile = new File(file.getFilePath());
        if (!physicalFile.isFile()) throw new TaggerException(ErrorCode.FILE_001);
        if (isTextFile(file.getMimeType(), file.getExtension())) {
            return taggerClient.classifyAdultText(file.getFilename(), file.getMimeType(),
                    readTextSamples(physicalFile.toPath()));
        }
        if (file.getMimeType() != null && (file.getMimeType().startsWith("image/")
                || file.getMimeType().startsWith("video/"))) {
            if (hasReadableThumbnail(file.getThumbnailPath())) {
                return taggerClient.classifyAdult(new File(file.getThumbnailPath()), file.getMimeType());
            }
            if (file.getMimeType().startsWith("image/") && physicalFile.length() <= 5L * 1024 * 1024) {
                return taggerClient.classifyAdult(physicalFile, file.getMimeType());
            }
            // 视频和超大图片必须等待标准缩略图，不能仅凭文件名提前写入成功结论。
            throw new TaggerException(ErrorCode.FILE_002);
        }
        String metadata = String.format("File name: %s%nExtension: %s%nMIME type: %s",
                file.getFilename(), file.getExtension(), file.getMimeType());
        return taggerClient.classifyAdultText(file.getFilename(), file.getMimeType(), metadata);
    }

    /**
     * 处理媒体文件（图片/视频/音频）。
     */
    private List<TaggerClient.TagResult> tagMediaFile(File file, String thumbnailPath, String mimeType) {
        // 大文件或视频/音频使用缩略图
        if (file.length() > LARGE_FILE_THRESHOLD || isVideoOrAudio(mimeType)) {
            if (thumbnailPath == null || thumbnailPath.isEmpty()) {
                throw new TaggerException(ErrorCode.FILE_002);
            }
            File thumbnailFile = new File(thumbnailPath);
            if (!thumbnailFile.exists()) {
                throw new TaggerException(ErrorCode.FILE_001);
            }
            return taggerClient.tagMediaFile(thumbnailFile, null, mimeType);
        }

        return taggerClient.tagMediaFile(file, thumbnailPath, mimeType);
    }

    /**
     * 处理文本文件。
     */
    private List<TaggerClient.TagResult> tagTextFile(File file, String filename) {
        try {
            // 只读取首、中、尾片段，避免大型电子书占用过多内存并覆盖更多正文范围。
            String content = readTextSamples(file.toPath());
            return taggerClient.tagTextFile(content, filename);
        } catch (IOException e) {
            throw new TaggerException(ErrorCode.FILE_005, e);
        }
    }

    private String readTextSamples(Path path) throws IOException {
        long size = Files.size(path);
        if (size <= TEXT_SAMPLE_BYTES * 3L) {
            byte[] bytes = Files.readAllBytes(path);
            return decodeText(bytes, detectCharset(bytes));
        }

        List<Long> offsets = List.of(0L, Math.max(0L, size / 2L - TEXT_SAMPLE_BYTES / 2L),
                Math.max(0L, size - TEXT_SAMPLE_BYTES));
        List<byte[]> chunks = new ArrayList<>();
        StringBuilder samples = new StringBuilder();
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            for (long offset : offsets) {
                // 分别读取首部、中部和尾部，片段之间增加明确分隔。
                ByteBuffer buffer = ByteBuffer.allocate(TEXT_SAMPLE_BYTES);
                channel.position(offset);
                int readLength = channel.read(buffer);
                if (readLength <= 0) {
                    continue;
                }
                buffer.flip();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                chunks.add(bytes);
            }
        }
        Charset charset = chunks.isEmpty() ? StandardCharsets.UTF_8 : detectCharset(chunks.get(0));
        for (int index = 0; index < chunks.size(); index++) {
            samples.append("\n--- sample ").append(index + 1).append(" ---\n")
                    .append(decodeText(chunks.get(index), charset));
        }
        return samples.toString();
    }

    private Charset detectCharset(byte[] bytes) {
        for (int trim = 0; trim <= 3 && bytes.length > trim; trim++) {
            try {
                // 允许采样片段末尾截断一个UTF-8字符。
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, bytes.length - trim));
                return StandardCharsets.UTF_8;
            } catch (CharacterCodingException ignored) {
                // 尝试移除下一个可能被截断的尾部字节。
            }
        }
        return Charset.forName("GB18030");
    }

    private String decodeText(byte[] bytes, Charset charset) {
        return charset.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private List<TaggerClient.TagResult> tagFileMetadata(LocalFile file) {
        String metadata = String.format(
                "File name: %s%nExtension: %s%nMIME type: %s%nSize: %d bytes",
                file.getFilename(), file.getExtension(), file.getMimeType(), file.getFileSize());
        return taggerClient.tagTextFile(metadata, file.getFilename());
    }

    private boolean hasReadableThumbnail(String thumbnailPath) {
        return thumbnailPath != null && !thumbnailPath.isBlank() && new File(thumbnailPath).isFile();
    }

    /**
     * 保存标签到数据库。
     */
    private List<FileTag> saveTags(Long fileId, List<TaggerClient.TagResult> tagResults) {
        if (tagResults == null || tagResults.isEmpty()) {
            return new ArrayList<>();
        }

        LocalDateTime now = LocalDateTime.now();
        List<FileTag> tags = new ArrayList<>();

        for (TaggerClient.TagResult result : tagResults) {
            FileTag tag = new FileTag();
            tag.setFileId(fileId);
            tag.setTagName(result.getTagName());
            tag.setTagType(result.getTagType());
            tag.setConfidence(result.getConfidence());
            tag.setTaggingTime(now);
            tag.setCreateTime(now);
            tags.add(tag);
        }

        fileTagMapper.batchInsert(tags);
        return tags;
    }

    /**
     * 判断是否为文本文件。
     */
    private boolean isTextFile(String mimeType, String extension) {
        if (mimeType != null && mimeType.startsWith("text/")) {
            return true;
        }
        if (extension != null) {
            String ext = extension.toLowerCase();
            return ext.equals("txt") || ext.equals("md") || ext.equals("json") ||
                   ext.equals("xml") || ext.equals("csv") || ext.equals("log");
        }
        return false;
    }

    /**
     * 判断是否为视频或音频文件。
     */
    private boolean isVideoOrAudio(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        return mimeType.startsWith("video/") || mimeType.startsWith("audio/");
    }
}
