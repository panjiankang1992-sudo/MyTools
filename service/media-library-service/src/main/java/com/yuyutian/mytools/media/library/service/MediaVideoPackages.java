package com.yuyutian.mytools.media.library.service;

import com.yuyutian.mytools.media.library.common.ErrorCode;
import com.yuyutian.mytools.media.library.config.MediaLibraryConfiguration.InternalToken;
import com.yuyutian.mytools.media.library.model.MediaModels.MediaView;
import com.yuyutian.mytools.media.library.repository.MediaRepository;
import jakarta.annotation.PreDestroy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 为有限大小的大视频生成独立播放副本，读取前校验最新所有权与内容版本。 */
@Service
public class MediaVideoPackages {
    private static final Logger LOG = LoggerFactory.getLogger(MediaVideoPackages.class);
    private static final long GIB = 1024L * 1024 * 1024;
    private static final Pattern FILE = Pattern.compile("mobile\\.mp4|master\\.m3u8|v[0-9]{1,4}(?:\\.m3u8|-[0-9]{5}\\.ts)");
    private final MediaRepository repository;
    private final Path root;
    private final String internalToken;
    private final int port;
    private final boolean enabled;
    private final UUID pilot;
    private final Map<UUID, Instant> failed = new LinkedHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon()
            .name("media-video-package").factory());
    private boolean working;

    /** 创建单工作线程的视频播放副本服务。 */
    public MediaVideoPackages(MediaRepository repository, InternalToken token,
            @Value("${media-library.video-package-directory:/opt/yuyutian/mytools/runtime/video-packages-v1}") Path root,
            @Value("${server.port:23300}") int port, @Value("${media-library.video-package-enabled:false}") boolean enabled,
            @Value("${media-library.video-package-pilot-id:}") String pilotId) {
        this.repository = repository;
        this.root = root;
        this.internalToken = token.value();
        this.port = port;
        this.enabled = enabled;
        // 指定试点时，任务生成与已有副本读取都限制在同一个媒体。
        this.pilot = pilotId.isBlank() ? null : UUID.fromString(pilotId);
    }

    /** 查询就绪状态，首次主动播放触发后台生成。 */
    public synchronized String prepare(long owner, UUID id) {
        var media = authorized(owner, id);
        if (!eligible(id) || !media.mimeType().startsWith("video/") || media.sizeBytes() < 100L * 1024 * 1024 ||
                media.sizeBytes() > 12 * GIB) return "original";
        Path target = directory(media);
        if (Files.isRegularFile(target.resolve("master.m3u8"))) return "master.m3u8";
        if (Files.isRegularFile(target.resolve("mobile.mp4"))) return "mobile.mp4";
        if (working || failed.getOrDefault(id, Instant.EPOCH).isAfter(Instant.now())) return "original";
        working = true;
        worker.execute(() -> generate(media, target));
        return "original";
    }

    /** 校验每次清单及片段读取，删除后持有旧票据也不能继续读取。 */
    public Path part(long owner, UUID id, String name) {
        if (!eligible(id) || !FILE.matcher(name).matches()) throw missing();
        Path file = directory(authorized(owner, id)).resolve(name);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw missing();
        return file;
    }

    private boolean eligible(UUID id) {
        return enabled && (pilot == null || pilot.equals(id));
    }

    private MediaView authorized(long owner, UUID id) {
        return repository.view(id).filter(m -> m.ownerId() == owner && "READY".equals(m.status())).orElseThrow(this::missing);
    }

    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, ErrorCode.CONTENT_NOT_FOUND.code());
    }

    private Path directory(MediaView media) {
        if (!media.contentSha256().matches("[a-fA-F0-9]{64}")) throw missing();
        return root.resolve(media.ownerId() + "-" + media.id() + "-" + media.contentSha256());
    }

    private void generate(MediaView media, Path target) {
        Path stage = null;
        Process process = null;
        boolean success = false;
        try {
            Files.createDirectories(root);
            // 单机只生成一个副本，预留源文件、各档成品和系统可用空间。
            if (Files.getFileStore(root).getUsableSpace() < media.sizeBytes() + 14 * GIB) return;
            try (var files = Files.walk(root, 2)) {
                long used = files.filter(Files::isRegularFile).mapToLong(path -> path.toFile().length()).sum();
                if (used > 24 * GIB) return;
            }
            stage = Files.createTempDirectory(root, ".work-");
            Path input = stage.resolve("source");
            copyOriginal(media, input);
            Path script = stage.resolve("package.py");
            try (var stream = new ClassPathResource("media_video_package.py").getInputStream()) { Files.copy(stream, script); }
            Path output = stage.resolve("out");
            process = new ProcessBuilder("nice", "-n", "12", "/usr/bin/python3", script.toString(),
                    input.toString(), output.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(3, TimeUnit.HOURS)) {
                LOG.warn("Video package conversion timed out");
                return;
            }
            if (process.exitValue() != 0) {
                LOG.warn("Video package converter exited with status {}", process.exitValue());
                return;
            }
            // 长任务提交前再次校验所有权、状态与摘要，过期产物不能上线。
            if (!target.equals(directory(authorized(media.ownerId(), media.id())))) return;
            Files.move(output, target, StandardCopyOption.ATOMIC_MOVE);
            success = true;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("Video package unavailable: {}", ex.getClass().getSimpleName());
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            if (stage != null) {
                try (var files = Files.walk(stage)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                } catch (Exception ex) { LOG.warn("Video staging cleanup failed"); }
            }
            synchronized (this) {
                working = false;
                if (!success) {
                    failed.put(media.id(), Instant.now().plusSeconds(3600));
                    if (failed.size() > 128) failed.remove(failed.keySet().iterator().next());
                }
            }
        }
    }

    private void copyOriginal(MediaView media, Path input) throws Exception {
        // 复用受保护的原片接口，覆盖旧文件与由资产登记的下载来源。
        var url = URI.create("http://127.0.0.1:" + port + "/internal/v1/media/items/" + media.id()
                + "/content?ownerId=" + media.ownerId());
        var connection = (HttpURLConnection) url.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Authorization", "Bearer " + internalToken);
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(30000);
        try {
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() != media.sizeBytes()) throw missing();
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var source = new java.security.DigestInputStream(connection.getInputStream(), digest);
                 var output = Files.newOutputStream(input, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[65536];
                long total = 0;
                for (int count; (count = source.read(buffer)) >= 0;) {
                    total += count;
                    if (total > media.sizeBytes() || Thread.currentThread().isInterrupted()) throw missing();
                    output.write(buffer, 0, count);
                }
                if (total != media.sizeBytes() || !java.util.HexFormat.of().formatHex(digest.digest())
                        .equalsIgnoreCase(media.contentSha256())) throw missing();
            }
        } finally { connection.disconnect(); }
    }

    /** 服务停止时中止后台转换。 */
    @PreDestroy
    public void close() { worker.shutdownNow(); }
}
