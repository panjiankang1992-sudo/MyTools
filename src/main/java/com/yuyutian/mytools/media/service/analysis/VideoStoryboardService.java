package com.yuyutian.mytools.media.service.analysis;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 根据视频时长生成主缩略图和十二张分段截图。
 */
@Service
@RequiredArgsConstructor
public class VideoStoryboardService {

    private static final int SCREENSHOT_COUNT = 12;
    private static final Duration SCREENSHOT_TIMEOUT = Duration.ofSeconds(45);
    private static final double MAX_DARK_PIXEL_RATIO = 0.96D;

    private final MediaCommandRunner commandRunner;
    private final MediaPackageFileWriter fileWriter;

    /**
     * 生成十二张均匀分布的截图和主缩略图。
     *
     * @param video 视频文件
     * @param packageDirectory 资源包目录
     * @param durationMs 视频时长
     * @return 已生成截图路径
     * @throws IOException 生成失败
     */
    public List<Path> generate(Path video, Path packageDirectory, long durationMs) throws IOException {
        if (durationMs <= 0) {
            throw new IOException("Video duration must be positive");
        }
        Path targetDirectory = packageDirectory.resolve("storyboard");
        Path stagingDirectory = packageDirectory.resolve(".analysis-staging");
        Files.createDirectories(targetDirectory);
        Files.createDirectories(stagingDirectory);
        clearGeneratedFiles(stagingDirectory);

        List<Path> stagedFiles = new ArrayList<>();
        List<Path> generated = new ArrayList<>();
        try {
            for (int index = 1; index <= SCREENSHOT_COUNT; index++) {
                long plannedTimestampMs = Math.max(0L, Math.min(durationMs - 1,
                        Math.round(durationMs * (index / (double) (SCREENSHOT_COUNT + 1)))));
                long timestampMs = plannedTimestampMs;
                String fileName = String.format(Locale.ROOT, "%02d_%012d.jpg", index, plannedTimestampMs);
                Path temporary = stagingDirectory.resolve(fileName);
                for (int attempt = 0; attempt < 4; attempt++) {
                    commandRunner.runForFile(List.of(
                            "ffmpeg", "-v", "error", "-y", "-ss", formatSeconds(timestampMs),
                            "-i", video.toString(), "-an", "-sn", "-dn", "-frames:v", "1",
                            "-vf", "scale=1280:-2:force_original_aspect_ratio=decrease:out_range=full,format=yuvj420p",
                            "-q:v", "3", temporary.toString()), SCREENSHOT_TIMEOUT, temporary);
                    if (!isBlackFrame(temporary) || attempt == 3) break;
                    // 黑场、片头或转场继续向后取样，最多推进视频时长的 12%。
                    timestampMs = Math.min(durationMs - 1,
                            plannedTimestampMs + Math.round(durationMs * (attempt + 1) * 0.03D));
                }
                Path target = targetDirectory.resolve(fileName);
                stagedFiles.add(temporary);
                generated.add(target);
            }
            // 十二张新截图全部成功后再替换旧故事板，避免失败重试留下混合时间轴或多余文件。
            clearGeneratedFiles(targetDirectory);
            for (int index = 0; index < stagedFiles.size(); index++) {
                fileWriter.copy(stagedFiles.get(index), generated.get(index));
            }
            fileWriter.copy(generated.getFirst(), packageDirectory.resolve("thumbnail.jpg"));
            return List.copyOf(generated);
        } finally {
            clearGeneratedFiles(stagingDirectory);
            Files.deleteIfExists(stagingDirectory);
        }
    }

    private String formatSeconds(long timestampMs) {
        return String.format(Locale.ROOT, "%.3f", timestampMs / 1000D);
    }

    private boolean isBlackFrame(Path imagePath) {
        try {
            BufferedImage image = ImageIO.read(imagePath.toFile());
            if (image == null || image.getWidth() == 0 || image.getHeight() == 0) return false;
            int stepX = Math.max(1, image.getWidth() / 80);
            int stepY = Math.max(1, image.getHeight() / 45);
            long sampled = 0;
            long dark = 0;
            for (int y = 0; y < image.getHeight(); y += stepY) {
                for (int x = 0; x < image.getWidth(); x += stepX) {
                    int rgb = image.getRGB(x, y);
                    int red = rgb >> 16 & 0xff;
                    int green = rgb >> 8 & 0xff;
                    int blue = rgb & 0xff;
                    if ((red * 299 + green * 587 + blue * 114) / 1000 < 18) dark++;
                    sampled++;
                }
            }
            return sampled > 0 && dark / (double) sampled >= MAX_DARK_PIXEL_RATIO;
        } catch (IOException ex) {
            return false;
        }
    }

    private void clearGeneratedFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.list(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isRegularFile(path) && path.getFileName().toString().matches("\\d{2}_\\d{12}\\.jpg")) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
