package com.yuyutian.mytools.pikpak.service;

import static com.yuyutian.mytools.pikpak.model.PikPakModels.*;

import com.yuyutian.mytools.pikpak.repository.PikPakRepository;
import com.yuyutian.mytools.pikpak.repository.PikPakWatchRepository;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PikPak 固定目录扫描、稳定观察与归档状态机。 */
@Service
public class PikPakWatchService {
    private final PikPakRepository accounts;
    private final PikPakWatchRepository watches;
    private final RclonePikPakClient connector;
    private final boolean enabled;
    private final Clock clock;

    /** 创建服务。 @param accounts 账户仓储 @param watches watcher 仓储 @param connector rclone 客户端 @param enabled 总开关 */
    @Autowired
    public PikPakWatchService(PikPakRepository accounts, PikPakWatchRepository watches,
            RclonePikPakClient connector, @Value("${pikpak.enabled:false}") boolean enabled) {
        this(accounts, watches, connector, enabled, Clock.systemUTC());
    }

    PikPakWatchService(PikPakRepository accounts, PikPakWatchRepository watches,
            RclonePikPakClient connector, boolean enabled, Clock clock) {
        this.accounts = accounts; this.watches = watches; this.connector = connector;
        this.enabled = enabled; this.clock = clock;
    }

    /** 登记固定目录 watcher。 @param request 请求 @return 配置 */
    public Watcher register(RegisterWatcherRequest request) {
        accounts.requireAccount(request.accountId());
        validateRoot(request.watchRoot()); validateRoot(request.backupRoot());
        if (request.watchRoot().equals(request.backupRoot()))
            throw new IllegalArgumentException("PIKPAK_WATCH_ROOT_CONFLICT");
        return watches.register(request);
    }

    /** 扫描一次并返回达到稳定窗口的批次。 @param accountId 账户 @return 稳定批次 */
    @Transactional
    public WatchScanView scan(UUID accountId) {
        requireEnabled();
        Account account = accounts.requireAccount(accountId);
        Watcher watcher = watches.requireWatcher(accountId);
        if (!account.enabled() || !watcher.enabled()) throw new IllegalStateException("PIKPAK_WATCHER_DISABLED");
        Map<String, List<RemoteItem>> groups = group(connector.list(account.remoteKey(), watcher.watchRoot()));
        List<WatchBatchView> ready = new ArrayList<>();
        Instant now = clock.instant();
        for (Map.Entry<String, List<RemoteItem>> entry : groups.entrySet()) {
            String signature = signature(entry.getValue());
            WatchBatch batch = watches.find(accountId, entry.getKey()).orElseGet(() ->
                watches.insert(accountId, entry.getKey(), signature, now));
            boolean changed = !signature.equals(batch.signature()) || List.of("ARCHIVED", "FAILED").contains(batch.phase());
            if (changed) {
                batch = watches.transition(batch, signature, now, "OBSERVING", null, null);
                watches.replaceItems(batch.id(), entry.getValue());
            } else if ("OBSERVING".equals(batch.phase())
                    && !now.isBefore(batch.stableSince().plusSeconds(watcher.stableSeconds()))) {
                watches.replaceItems(batch.id(), entry.getValue());
                batch = watches.transition(batch, signature, batch.stableSince(), "READY", null, null);
            }
            if ("READY".equals(batch.phase())) ready.add(view(batch, account, watcher));
        }
        return new WatchScanView(accountId, List.copyOf(ready));
    }

    /** 扫描全部启用 watcher。 @return 各账户稳定批次 */
    public List<WatchScanView> scanAll() {
        requireEnabled();
        return watches.enabledWatchers().stream().map(watcher -> scan(watcher.accountId())).toList();
    }

    /** 下载成功后推进远端批次归档。 @param batchId 批次 @return 状态 */
    @Transactional
    public WatchBatchView archive(UUID batchId) {
        requireEnabled();
        WatchBatch batch = watches.requireBatch(batchId);
        Account account = accounts.requireAccount(batch.accountId());
        Watcher watcher = watches.requireWatcher(batch.accountId());
        if ("READY".equals(batch.phase())) {
            List<RemoteItem> items = watches.items(batch.id());
            String batchPath = batch.batchPath();
            boolean directory = items.stream().anyMatch(item -> item.relativePath().startsWith(batchPath + "/"));
            String source = join(watcher.watchRoot(), batchPath);
            String target = join(watcher.backupRoot(), batchPath);
            long job = directory ? connector.startMove(account.remoteKey(), source, target)
                : connector.startMoveFile(account.remoteKey(), source, target);
            batch = watches.transition(batch, batch.signature(), batch.stableSince(), "MOVING", job, null);
        } else if ("MOVING".equals(batch.phase())) {
            RemoteJob job = connector.job(batch.remoteJobId());
            if (job.finished()) batch = watches.transition(batch, batch.signature(), batch.stableSince(),
                job.success() ? "ARCHIVED" : "FAILED", batch.remoteJobId(),
                job.success() ? null : "PIKPAK_WATCH_ARCHIVE_FAILED");
        }
        return view(batch, account, watcher);
    }

    /** 查询 watcher 批次。 @param batchId 批次 @return 状态 */
    public WatchBatchView get(UUID batchId) {
        WatchBatch batch = watches.requireBatch(batchId);
        return view(batch, accounts.requireAccount(batch.accountId()), watches.requireWatcher(batch.accountId()));
    }

    private WatchBatchView view(WatchBatch batch, Account account, Watcher watcher) {
        List<WatchItem> items = watches.items(batch.id()).stream().map(item -> new WatchItem(
            item.remoteFileId(), item.relativePath(), item.sizeBytes(), item.modifiedAt(),
            account.storageProviderId(), join(watcher.watchRoot(), item.relativePath()))).toList();
        return new WatchBatchView(batch.id(), batch.batchPath(), batch.phase(), batch.errorCode(),
            List.of("ARCHIVED", "FAILED").contains(batch.phase()) ? 0 : 10, items);
    }

    private Map<String, List<RemoteItem>> group(List<RemoteItem> items) {
        Map<String, List<RemoteItem>> groups = new LinkedHashMap<>();
        items.stream().sorted(Comparator.comparing(RemoteItem::relativePath)).forEach(item -> {
            String path = validPath(item.relativePath());
            String batch = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
            if (batch.length() > 512) throw new IllegalArgumentException("PIKPAK_WATCH_BATCH_PATH_INVALID");
            groups.computeIfAbsent(batch, ignored -> new ArrayList<>()).add(item);
        });
        return groups;
    }

    private String signature(List<RemoteItem> items) {
        MessageDigest digest = digest();
        for (RemoteItem item : items) for (String value : List.of(item.remoteFileId(), item.relativePath(),
                Long.toString(item.sizeBytes()), item.modifiedAt())) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private void validateRoot(String value) { validPath(value); }
    private String join(String root, String path) { return validPath(root) + "/" + validPath(path); }
    private String validPath(String value) {
        String path = value == null ? "" : value.strip().replace('\\', '/').replaceAll("/+$", "");
        if (path.isBlank() || path.startsWith("/") || path.length() > 1024 || path.contains("\u0000"))
            throw new IllegalArgumentException("PIKPAK_WATCH_PATH_INVALID");
        for (String part : path.split("/", -1)) if (part.isBlank() || ".".equals(part) || "..".equals(part))
            throw new IllegalArgumentException("PIKPAK_WATCH_PATH_INVALID");
        return path;
    }

    private void requireEnabled() { if (!enabled) throw new IllegalStateException("PIKPAK_CONNECTOR_DISABLED"); }
}
