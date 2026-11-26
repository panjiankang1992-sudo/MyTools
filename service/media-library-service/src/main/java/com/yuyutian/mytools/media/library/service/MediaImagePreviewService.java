package com.yuyutian.mytools.media.library.service;

import com.yuyutian.mytools.media.library.repository.MediaRepository;
import com.yuyutian.mytools.media.library.common.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 按所有者和内容版本缓存有限尺寸预览，避免将原图当缩略图传输。 */
@Service
public class MediaImagePreviewService {
    private final MediaRepository repository;
    private final LegacyMediaContentService legacy;
    private final DerivedThumbnailContentService assets;
    private final Path directory;
    private final String python;
    private final String converter;
    private final Object[] locks = new Object[32];
    private final Semaphore decoders = new Semaphore(2);

    /** 创建预览服务。 @param repository 仓储。 @param legacy 旧内容。 @param assets 资产内容。 @param directory 缓存目录。 @param python 转换解释器。 */
    public MediaImagePreviewService(MediaRepository repository, LegacyMediaContentService legacy,
            DerivedThumbnailContentService assets,
            @Value("${media-library.preview-cache-directory:/opt/yuyutian/mytools/runtime/media-previews-v1}") String directory,
            @Value("${media-library.preview-python:/usr/bin/python3}") String python) throws java.io.IOException {
        this.repository = repository;
        this.legacy = legacy;
        this.assets = assets;
        this.directory = Path.of(directory);
        this.python = python;
        try (var input = new ClassPathResource("media_preview.py").getInputStream()) {
            this.converter = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (int index = 0; index < locks.length; index++) locks[index] = new Object();
    }

    /** 校验实时所有权后返回缓存预览，首次请求合并生成。 @param ownerId 所有者。 @param id 媒体标识。 @param edge 最长边。 @return JPEG 预览。 */
    public LegacyMediaContentService.Content preview(long ownerId, UUID id, int edge) {
        if (edge != 512 && edge != 2048) throw new IllegalArgumentException(ErrorCode.PREVIEW_INVALID_INPUT.code());
        // 缓存命中也必须实时核对所有者与状态，不能绕过撤销或删除。
        var media = repository.view(id).filter(value -> value.ownerId() == ownerId && "READY".equals(value.status()))
                .orElseThrow(() -> new IllegalArgumentException(ErrorCode.PREVIEW_UNAVAILABLE.code()));
        String hash = media.contentSha256();
        if (!hash.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException(ErrorCode.PREVIEW_INVALID_INPUT.code());
        Path target = directory.resolve(ownerId + "-" + id + "-" + hash + "-" + edge + ".jpg");
        synchronized (locks[Math.floorMod(id.hashCode(), locks.length)]) {
            try {
                if (Files.isRegularFile(target) && Files.size(target) > 2) return content(target);
                if (!decoders.tryAcquire(5, TimeUnit.SECONDS)) throw new IllegalStateException(ErrorCode.PREVIEW_BUSY.code());
                try {
                    // 高清预览优先原始资产；列表优先已有派生图，二者都经过受限缩放。
                    var source = edge == 2048 && media.mimeType().startsWith("image/")
                            ? assets.originalImage(ownerId, id).orElseGet(() -> legacy.content(ownerId, id, false))
                            : legacy.content(ownerId, id, true);
                    render(source, target, edge);
                    return content(target);
                } finally {
                    decoders.release();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ErrorCode.PREVIEW_UNAVAILABLE.code(), exception);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(ErrorCode.PREVIEW_UNAVAILABLE.code(), exception);
            }
        }
    }

    private LegacyMediaContentService.Content content(Path target) throws java.io.IOException {
        return new LegacyMediaContentService.Content(new FileSystemResource(target), "image/jpeg", Files.size(target));
    }

    private void render(LegacyMediaContentService.Content source, Path target, int edge)
            throws java.io.IOException, InterruptedException {
        if (!source.mimeType().startsWith("image/") || source.sizeBytes() < 1 || source.sizeBytes() > 20 * 1024 * 1024) {
            throw new IllegalArgumentException(ErrorCode.PREVIEW_INVALID_INPUT.code());
        }
        Files.createDirectories(directory);
        Path input = Files.createTempFile(directory, "source-", ".part");
        Path output = Files.createTempFile(directory, "preview-", ".part");
        Process process = null;
        try {
            try (var stream = source.resource().getInputStream(); var file = Files.newOutputStream(input)) {
                byte[] buffer = new byte[16384];
                long total = 0;
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    total += count;
                    if (total > source.sizeBytes()) throw new java.io.IOException(ErrorCode.PREVIEW_CONVERSION_FAILED.code());
                    file.write(buffer, 0, count);
                }
                if (total != source.sizeBytes()) throw new java.io.IOException(ErrorCode.PREVIEW_CONVERSION_FAILED.code());
            }
            // 文件名仅用于重定向，不进入命令解释器；限制并发与执行时间。
            process = new ProcessBuilder(python, "-c", converter, Integer.toString(edge))
                    .redirectInput(input.toFile()).redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0
                    || Files.size(output) < 3 || Files.size(output) > (edge == 512 ? 192 * 1024 : 1024 * 1024)) {
                throw new java.io.IOException(ErrorCode.PREVIEW_CONVERSION_FAILED.code());
            }
            Files.move(output, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }
}
