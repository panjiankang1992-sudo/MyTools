package com.yuyutian.mytools.media.service.importer;

import com.yuyutian.mytools.localfile.entity.FileTag;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import com.yuyutian.mytools.localfile.mapper.FileTagMapper;
import com.yuyutian.mytools.localfile.mapper.LocalFileMapper;
import com.yuyutian.mytools.media.model.MediaPackageManifest;
import com.yuyutian.mytools.media.model.MediaTagArtifact;
import com.yuyutian.mytools.media.mapper.MediaTagArtifactAuditMapper;
import com.yuyutian.mytools.utils.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 将 DownloadBot 已生成的标签产物导入 MyTools 标签投影。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaPackageTagImportService {

    /** MyTools 本地待处理状态。 */
    public static final int TAG_PENDING = 0;

    /** MyTools 标签已完成状态。 */
    public static final int TAG_READY = 1;

    /** DownloadBot 标签仍在处理状态。 */
    public static final int TAG_EXTERNAL_PENDING = 3;

    private final MediaPackageArtifactReader artifactReader;
    private final LocalFileMapper localFileMapper;
    private final FileTagMapper fileTagMapper;
    private final MediaTagArtifactAuditMapper artifactAuditMapper;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * 尝试导入视频同目录下的 DownloadBot 标签产物。
     *
     * @param file 已写入数据库的本地文件
     * @return 是否识别为 DownloadBot 资源包
     */
    @Transactional
    public boolean reconcile(LocalFile file) {
        if (file == null || file.getId() == null || file.getFilePath() == null
                || file.getMimeType() == null || !file.getMimeType().startsWith("video/")) {
            return false;
        }
        Path videoPath = Path.of(file.getFilePath()).toAbsolutePath().normalize();
        Path packageDirectory = videoPath.getParent();
        if (packageDirectory == null || !Files.isRegularFile(packageDirectory.resolve(".ready"))
                || !Files.isRegularFile(packageDirectory.resolve("metadata.json"))) {
            return false;
        }
        try {
            MediaPackageManifest manifest = artifactReader.readManifest(packageDirectory);
            if (!videoPath.toRealPath().equals(packageDirectory.resolve(manifest.videoFile()).toRealPath())
                    || !manifest.contentSha256().equals(file.getFileHash())) {
                throw new MediaPackageArtifactException("Indexed video does not match media package manifest");
            }
            if ("PENDING".equals(manifest.tagStatus()) || "RUNNING".equals(manifest.tagStatus())) {
                // 外部任务仍在运行时排除 MyTools 本地标签队列，等待后续文件事件或补偿扫描对账。
                localFileMapper.updateTaggingStatus(file.getId(), TAG_EXTERNAL_PENDING, LocalDateTime.now());
                file.setTaggingStatus(TAG_EXTERNAL_PENDING);
                return true;
            }
            if (!"READY".equals(manifest.tagStatus())) {
                // 外部任务最终失败或跳过时保留本地待处理状态，由 MyTools 接管一次。
                localFileMapper.updateTaggingStatus(file.getId(), TAG_PENDING, LocalDateTime.now());
                file.setTaggingStatus(TAG_PENDING);
                return true;
            }

            MediaTagArtifact artifact = artifactReader.readTagArtifact(packageDirectory, manifest);
            List<FileTag> tags = toFileTags(file.getId(), artifact);
            fileTagMapper.deleteByFileId(file.getId());
            fileTagMapper.batchInsert(tags);
            LocalDateTime now = LocalDateTime.now();
            artifactAuditMapper.upsert(idGenerator.nextId(), file.getId(), artifact.contentSha256(),
                    artifact.producer(), artifact.provider(), artifact.model(), artifact.promptVersion(),
                    artifact.inputKind(), artifact.inputFingerprint(), artifact.status(),
                    OffsetDateTime.parse(artifact.generatedAt()).toLocalDateTime(), now);
            localFileMapper.updateTaggingStatus(file.getId(), TAG_READY, now);
            file.setTaggingStatus(TAG_READY);
            log.info("已复用 DownloadBot 标签：fileId={}，标签数量={}", file.getId(), tags.size());
            return true;
        } catch (MediaPackageArtifactException | java.io.IOException ex) {
            // 损坏的外部产物不能阻塞扫描，也不能阻止 MyTools 后续本地标签回退。
            localFileMapper.updateTaggingStatus(file.getId(), TAG_PENDING, LocalDateTime.now());
            file.setTaggingStatus(TAG_PENDING);
            log.warn("DownloadBot 标签产物校验失败：fileId={}", file.getId(), ex);
            return true;
        }
    }

    private List<FileTag> toFileTags(Long fileId, MediaTagArtifact artifact) {
        LocalDateTime generatedAt = OffsetDateTime.parse(artifact.generatedAt()).toLocalDateTime();
        return artifact.tags().stream().map(source -> {
            FileTag tag = new FileTag();
            tag.setFileId(fileId);
            tag.setTagName(source.name());
            tag.setTagType(source.type());
            tag.setConfidence(source.confidence());
            tag.setTaggingTime(generatedAt);
            tag.setCreateTime(LocalDateTime.now());
            return tag;
        }).toList();
    }
}
