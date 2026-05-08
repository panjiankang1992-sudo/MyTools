package com.yuyutian.mytools.localfile.service.tagging.impl;

import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.localfile.entity.FileTag;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.FileTagMapper;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.localfile.service.tagging.TaggerClient;
import com.yuyutian.mytools.localfile.service.tagging.TaggerException;
import com.yuyutian.mytools.localfile.service.tagging.TaggerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** 大文件阈值：100MB */
    private static final long LARGE_FILE_THRESHOLD = 100 * 1024 * 1024;

    /** 文本文件大小阈值：10MB */
    private static final long TEXT_FILE_THRESHOLD = 10 * 1024 * 1024;

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
            } else {
                // 图片/视频/音频使用缩略图或文件本身
                tagResults = tagMediaFile(physicalFile, file.getThumbnailPath(), mimeType);
            }
        } catch (TaggerException e) {
            // 更新状态为失败
            localFileMapper.updateTaggingStatus(file.getId(), 2, LocalDateTime.now());
            throw e;
        }

        // 保存标签
        List<FileTag> savedTags = saveTags(file.getId(), tagResults);

        // 更新状态为成功
        localFileMapper.updateTaggingStatus(file.getId(), 1, LocalDateTime.now());

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
        // 大文本文件跳过
        if (file.length() > TEXT_FILE_THRESHOLD) {
            log.warn("文本文件过大，跳过打标签: {} ({} bytes)", filename, file.length());
            throw new TaggerException(ErrorCode.FILE_009);
        }

        try {
            String content = Files.readString(Path.of(file.toURI()));
            return taggerClient.tagTextFile(content, filename);
        } catch (IOException e) {
            throw new TaggerException(ErrorCode.FILE_005, e);
        }
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
