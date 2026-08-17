package com.yuyutian.mytools.localfile.job;

import com.yuyutian.mytools.localfile.entity.LocalDirectory;
import com.yuyutian.mytools.localfile.mapper.LocalDirectoryMapper;
import com.yuyutian.mytools.localfile.service.LocalFileScanTaskService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 本地目录文件系统监听任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalDirectoryWatchJob {

    private final LocalDirectoryMapper localDirectoryMapper;
    private final LocalFileScanTaskService scanTaskService;
    private final Map<WatchKey, WatchedDirectory> watchedDirectories = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> pendingScans = new ConcurrentHashMap<>();
    private final ExecutorService watcherExecutor = Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "local-directory-watcher"));
    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor(runnable ->
            new Thread(runnable, "local-directory-watch-debounce"));

    @Value("${file.scan.watch-enabled:true}")
    private boolean watchEnabled;

    @Value("${file.scan.watch-debounce-seconds:3}")
    private long debounceSeconds;

    private WatchService watchService;

    /**
     * 启动全部已启用目录的递归监听。
     */
    @PostConstruct
    public void startWatching() {
        if (!watchEnabled) {
            log.info("本地目录监听已禁用");
            return;
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
            // 远程资源目录可能包含大量子目录，递归注册必须在后台执行，不能阻塞 Spring 启动和健康检查。
            watcherExecutor.submit(this::initializeAndWatch);
            log.info("本地目录监听初始化任务已提交");
        } catch (IOException ex) {
            log.error("启动本地目录监听失败", ex);
        }
    }

    private void initializeAndWatch() {
        try {
            for (LocalDirectory directory : localDirectoryMapper.selectAll()) {
                if (Integer.valueOf(1).equals(directory.getScanEnabled())) {
                    registerDirectoryTree(Path.of(directory.getDirectoryPath()), directory.getId());
                }
            }
            log.info("本地目录监听已启动，已注册目录数量：{}", watchedDirectories.size());
            watchLoop();
        } catch (IOException ex) {
            log.error("初始化本地目录监听失败", ex);
        }
    }

    private void registerDirectoryTree(Path root, Long directoryId) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isDirectory)
                    .filter(path -> !isIgnored(path, root))
                    .forEach(path -> registerDirectory(path, directoryId));
        }
    }

    private void registerDirectory(Path path, Long directoryId) {
        try {
            WatchKey key = path.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            watchedDirectories.put(key, new WatchedDirectory(directoryId, path));
        } catch (IOException ex) {
            log.warn("注册目录监听失败：{}", path, ex);
        }
    }

    private void watchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                WatchKey key = watchService.take();
                WatchedDirectory watchedDirectory = watchedDirectories.get(key);
                if (watchedDirectory == null) {
                    key.reset();
                    continue;
                }
                handleEvents(key, watchedDirectory);
                if (!key.reset()) {
                    watchedDirectories.remove(key);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (ClosedWatchServiceException ex) {
                return;
            } catch (Exception ex) {
                log.error("处理目录监听事件失败", ex);
            }
        }
    }

    private void handleEvents(WatchKey key, WatchedDirectory watchedDirectory) {
        boolean scanRequired = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                // 事件溢出时执行全目录增量扫描作为补偿。
                scanRequired = true;
                continue;
            }
            Path changedPath = watchedDirectory.path().resolve((Path) event.context());
            if (isIgnoredName(changedPath.getFileName())) {
                continue;
            }
            scanRequired = true;
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changedPath)) {
                try {
                    // 新目录立即递归注册，随后扫描会补齐目录创建期间写入的文件。
                    registerDirectoryTree(changedPath, watchedDirectory.directoryId());
                } catch (IOException ex) {
                    log.warn("注册新增目录监听失败：{}", changedPath, ex);
                }
            }
        }
        if (scanRequired) {
            scheduleScan(watchedDirectory.directoryId());
        }
    }

    private void scheduleScan(Long directoryId) {
        pendingScans.compute(directoryId, (id, previous) -> {
            if (previous != null) {
                previous.cancel(false);
            }
            return debounceExecutor.schedule(() -> {
                pendingScans.remove(id);
                scanTaskService.submitScan(id, false);
            }, debounceSeconds, TimeUnit.SECONDS);
        });
    }

    private boolean isIgnored(Path path, Path root) {
        Path relativePath = root.relativize(path);
        for (Path part : relativePath) {
            if (isIgnoredName(part)) {
                return true;
            }
        }
        return false;
    }

    private boolean isIgnoredName(Path fileName) {
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        return ".thumbnails".equals(name) || name.startsWith(".");
    }

    /**
     * 停止目录监听和去抖任务。
     */
    @PreDestroy
    public void stopWatching() {
        pendingScans.values().forEach(future -> future.cancel(false));
        debounceExecutor.shutdownNow();
        watcherExecutor.shutdownNow();
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ex) {
                log.warn("关闭目录监听失败", ex);
            }
        }
    }

    private record WatchedDirectory(Long directoryId, Path path) {
    }
}
