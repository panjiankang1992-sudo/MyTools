package com.yuyutian.mytools.cloudfile.service;

import com.yuyutian.mytools.cloudfile.model.RemoteMediaStream;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * 把受认证的远程图片转换为有界JPEG缩略图。
 */
@Service
@RequiredArgsConstructor
public class RemoteImageThumbnailService {

    private static final int MAX_SOURCE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_DIMENSION = 20_000;
    private static final long MAX_PIXELS = 80_000_000L;
    private static final int THUMBNAIL_EDGE = 192;
    private static final int SHARE_EDGE = 2048;
    private final CloudFileService cloudFileService;

    /**
     * 读取当前用户的远程图片并生成最大192像素的JPEG缩略图。
     *
     * @param userId 当前用户ID
     * @param accountId 远程账号ID
     * @param path 规范远程图片路径
     * @return JPEG缩略图字节
     */
    public byte[] create(Long userId, Long accountId, String path) {
        return create(userId, accountId, path, THUMBNAIL_EDGE);
    }

    /**
     * 按受控边长生成JPEG预览，仅允许缩略图和系统分享两个固定档位。
     *
     * @param userId 当前用户ID
     * @param accountId 远程账号ID
     * @param path 规范远程图片路径
     * @param edge 输出最长边
     * @return JPEG预览字节
     */
    public byte[] create(Long userId, Long accountId, String path, int edge) {
        validateRequest(userId, accountId, path);
        if (edge != THUMBNAIL_EDGE && edge != SHARE_EDGE) throw new BusinessException(ErrorCode.MEDIA_005);
        RemoteMediaStream stream = cloudFileService.openDownloadStream(userId, accountId, path);
        try (InputStream input = stream.body()) {
            validateResponse(stream);
            byte[] source = readBounded(input);
            return decodeAndScale(source, edge);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.MEDIA_005);
        }
    }

    private void validateRequest(Long userId, Long accountId, String path) {
        if (userId == null || userId <= 0 || accountId == null || accountId <= 0 || path == null ||
                path.length() < 2 || path.length() > 4096 || !path.startsWith("/") || path.endsWith("/") ||
                path.contains("\\") || path.contains("//") || path.chars().anyMatch(value -> value < 32 || value == 127)) {
            throw new BusinessException(ErrorCode.MEDIA_005);
        }
        // 路径段必须保持规范，避免缩略图端点成为独立的远程路径穿越入口。
        for (String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) throw new BusinessException(ErrorCode.MEDIA_005);
        }
    }

    private void validateResponse(RemoteMediaStream stream) {
        if (stream.statusCode() < 200 || stream.statusCode() >= 300) {
            throw new BusinessException(ErrorCode.MEDIA_005);
        }
        stream.contentLength().ifPresent(value -> {
            try {
                long length = Long.parseLong(value);
                if (length < 1 || length > MAX_SOURCE_BYTES) throw new BusinessException(ErrorCode.MEDIA_005);
            } catch (NumberFormatException exception) {
                throw new BusinessException(ErrorCode.MEDIA_005);
            }
        });
        stream.contentType().ifPresent(value -> {
            String normalized = value.toLowerCase(java.util.Locale.ROOT).split(";", 2)[0].trim();
            if (!normalized.startsWith("image/")) throw new BusinessException(ErrorCode.MEDIA_005);
        });
    }

    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            total += read;
            if (total > MAX_SOURCE_BYTES) throw new BusinessException(ErrorCode.MEDIA_005);
            output.write(buffer, 0, read);
        }
        if (total == 0) throw new BusinessException(ErrorCode.MEDIA_005);
        return output.toByteArray();
    }

    private byte[] decodeAndScale(byte[] source, int edge) throws IOException {
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (imageInput == null) throw new BusinessException(ErrorCode.MEDIA_005);
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw new BusinessException(ErrorCode.MEDIA_005);
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION ||
                        (long) width * height > MAX_PIXELS) throw new BusinessException(ErrorCode.MEDIA_005);
                int sampling = Math.max(1, Math.min(width, height) / (edge * 2));
                ImageReadParam parameter = reader.getDefaultReadParam();
                parameter.setSourceSubsampling(sampling, sampling, 0, 0);
                BufferedImage decoded = reader.read(0, parameter);
                int targetWidth = Math.max(1, Math.round(width * Math.min(1f,
                        (float) edge / Math.max(width, height))));
                int targetHeight = Math.max(1, Math.round(height * Math.min(1f,
                        (float) edge / Math.max(width, height))));
                BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = target.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.drawImage(decoded, 0, 0, targetWidth, targetHeight, null);
                } finally {
                    graphics.dispose();
                    decoded.flush();
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (!ImageIO.write(target, "jpeg", output)) throw new BusinessException(ErrorCode.MEDIA_005);
                target.flush();
                return output.toByteArray();
            } finally {
                reader.dispose();
            }
        }
    }
}
