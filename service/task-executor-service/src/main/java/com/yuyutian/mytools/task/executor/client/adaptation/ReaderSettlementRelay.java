package com.yuyutian.mytools.task.executor.client.adaptation;

import com.yuyutian.mytools.task.executor.common.ErrorCode;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** 独立 AES-256-GCM/SQLite 结算日志；同步落盘后才允许发送，内容不进入通用任务 WAL。 */
public final class ReaderSettlementRelay implements AutoCloseable {
    private static final int MAXIMUM_ENTRY_BYTES = 2097152;
    private static final int MAXIMUM_ENTRIES = 16;
    private static final long MAXIMUM_TOTAL_BYTES = (MAXIMUM_ENTRY_BYTES + 28L) * MAXIMUM_ENTRIES;
    private final Map<UUID, Lease> live = new HashMap<>();
    private final Map<UUID, Instant> deferred = new HashMap<>();
    private final ReentrantLock replaying = new ReentrantLock();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private Connection database;
    private FileChannel ownership;
    private FileLock ownerLock;
    private byte[] key;
    private boolean closed;
    private boolean failed;

    /**
     * 打开专属现有 0700 目录和独立 32 字节原始 AES 密钥文件；不从 Provider 或 TLS 凭据派生密钥。
     * 单密钥首版仅允许排空日志后轮换，旧密钥丢失不能降级为明文或重新生成。
     */
    public ReaderSettlementRelay(Path directory, Path keyFile) { this(directory, keyFile, Clock.systemUTC()); }

    ReaderSettlementRelay(Path directory, Path keyFile, Clock clock) {
        this.clock = clock;
        try {
            if (clock == null || directory == null || keyFile == null || !directory.isAbsolute() || !keyFile.isAbsolute()
                    || !directory.normalize().equals(directory.toRealPath()) || !keyFile.normalize().equals(keyFile.toRealPath())
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------"))) throw failure();
            secureFile(keyFile, false);
            if (!Files.getOwner(directory).equals(Files.getOwner(keyFile))) throw failure();
            try (var stream = Files.newInputStream(keyFile, LinkOption.NOFOLLOW_LINKS)) { key = stream.readNBytes(33); }
            if (key.length != 32) throw failure();
            Path lock = directory.resolve("relay-owner.lock"); prepareFile(lock);
            ownership = FileChannel.open(lock, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            ownerLock = ownership.tryLock();
            if (ownerLock == null) throw failure();
            Path file = directory.resolve("settlement.db"); prepareFile(file);
            for (String suffix : new String[]{"-journal", "-wal", "-shm"}) {
                Path sidecar = directory.resolve("settlement.db" + suffix);
                if (Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) secureFile(sidecar, true);
            }
            // 确保持久目录项先于账本使用；数据库事务随后由 SQLite FULL 同步策略保证。
            try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
            database = DriverManager.getConnection("jdbc:sqlite:" + file);
            try (var statement = database.createStatement()) {
                statement.execute("PRAGMA busy_timeout=1000");
                statement.execute("PRAGMA journal_mode=DELETE");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA secure_delete=ON");
                statement.execute("CREATE TABLE IF NOT EXISTS relay_schema (version INTEGER PRIMARY KEY CHECK (version = 1))");
                statement.execute("INSERT OR IGNORE INTO relay_schema(version) VALUES (1)");
                statement.execute("CREATE TABLE IF NOT EXISTS settlement (id TEXT PRIMARY KEY, expires INTEGER NOT NULL, payload BLOB NOT NULL CHECK(length(payload) <= 2097180))");
                try (var rows = statement.executeQuery("SELECT version FROM relay_schema")) {
                    if (!rows.next() || rows.getInt(1) != 1 || rows.next()) throw failure();
                }
                try (var rows = statement.executeQuery("PRAGMA quick_check")) {
                    if (!rows.next() || !"ok".equals(rows.getString(1)) || rows.next()) throw failure();
                }
            }
            validateStored();
        } catch (Exception exception) {
            close();
            throw failure();
        }
    }

    /** 保存来自真实 ReaderClient 的原窄能力；返回前已经同步提交，无完整模型请求落盘。 */
    public synchronized Lease arm(ReaderAdaptationClient reader, ReaderAdaptationClient.SendCapability capability) {
        ready();
        if (reader == null) throw failure();
        ReaderRelayEnvelope entry = reader.relay(capability);
        if (!clock.instant().isBefore(entry.expiresAt) || entry.expiresAt.isAfter(clock.instant().plusSeconds(302))
                || live.containsKey(entry.providerId)) throw failure();
        try {
            ReaderRelayEnvelope existing = find(entry.providerId);
            if (existing == null) insert(entry);
            else if (existing.terminal != null || !same(existing, entry)) throw conflict();
            Lease lease = new Lease(entry.providerId);
            live.put(entry.providerId, lease);
            return lease;
        } catch (SQLException exception) { failed = true; throw failure(); }
    }

    /** 不含正文的恢复结果；DEFERRED 必须等待内部退避，不改变 Reader 的业务终态。 */
    public enum Recovery { EMPTY, REPLAYED, EXPIRED, DEFERRED }

    /**
     * 最多恢复一个非活动记录；从未保存终态的原发送只结算 UNKNOWN，绝无模型访问路径。
     * HTTP 在数据库锁外执行，当前活跃 Lease 不参与恢复；只在原窄能力有效期内重放。
     */
    public Recovery recoverOne(ReaderSettlementClient client) {
        if (client == null) throw failure();
        if (!replaying.tryLock()) return Recovery.DEFERRED;
        Lease selected = null;
        try {
            ReaderRelayEnvelope entry;
            synchronized (this) {
                ready();
                UUID id = next();
                if (id == null) return Recovery.EMPTY;
                entry = find(id);
                if (entry == null) throw failure();
                if (!clock.instant().isBefore(entry.expiresAt)) { delete(id); return Recovery.EXPIRED; }
                selected = new Lease(id); live.put(id, selected);
                if (entry.terminal == null) {
                    selected.record(ReaderRelayEnvelope.unknown());
                    entry = find(id);
                }
            }
            try { client.settle(entry); }
            catch (ReaderAdaptationException exception) {
                synchronized (this) { deferred.put(entry.providerId, clock.instant().plusSeconds(5)); }
                return Recovery.DEFERRED;
            }
            selected.acknowledge();
            return Recovery.REPLAYED;
        } catch (SQLException exception) {
            synchronized (this) { failed = true; }
            throw failure();
        } finally {
            if (selected != null) selected.close();
            replaying.unlock();
        }
    }

    /** 当前存储条数用于容量/恢复观测，不返回任务身份、正文或令牌。 */
    public synchronized int pendingCount() {
        ready();
        try (var statement = database.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM settlement")) {
            if (!rows.next()) throw failure(); return rows.getInt(1);
        } catch (SQLException exception) { failed = true; throw failure(); }
    }

    /** 活跃宿主拥有的原调用句柄；默认诊断不含可重放信息。 */
    @com.fasterxml.jackson.annotation.JsonIgnoreType
    public final class Lease implements AutoCloseable {
        private final UUID id;
        private boolean released;
        private boolean acknowledged;
        private Lease(UUID id) { this.id = id; }

        /** 保存完整、归一的同一终态；提交确认前不能向 Reader 回写，已保存载荷不可替换。 */
        public void record(NovelStageOutput.Terminal terminal) {
            synchronized (ReaderSettlementRelay.this) {
                requireLease();
                try {
                    ReaderRelayEnvelope entry = find(id);
                    if (entry == null) throw failure();
                    ReaderRelayEnvelope result = entry.withTerminal(terminal);
                    if (entry.terminal != null) {
                        if (!same(entry, result)) throw conflict();
                        return;
                    }
                    update(result);
                } catch (SQLException exception) { failed = true; throw failure(); }
            }
        }

        /** Reader 已确认同一载荷后清除记录；删除重复确认是幂等的。 */
        public void acknowledge() {
            synchronized (ReaderSettlementRelay.this) {
                if (acknowledged) return;
                requireLease();
                try {
                    ReaderRelayEnvelope entry = find(id);
                    if (entry != null && entry.terminal == null) throw failure();
                    delete(id); acknowledged = true; close();
                } catch (SQLException exception) { failed = true; throw failure(); }
            }
        }

        /** 宿主停止时让独立恢复消费者接管原记录，不删除待结算内容。 */
        @Override public void close() {
            synchronized (ReaderSettlementRelay.this) { live.remove(id, this); released = true; }
        }

        private void requireLease() { ready(); if (released || live.get(id) != this) throw failure(); }
        /** 不输出原调用身份与秘密。 */
        @Override public String toString() { return "ReaderSettlementLease[REDACTED]"; }
    }

    private void insert(ReaderRelayEnvelope entry) throws SQLException {
        byte[] encrypted = encrypt(entry);
        capacity(encrypted.length, 0, true);
        try (var statement = database.prepareStatement("INSERT INTO settlement(id, expires, payload) VALUES (?, ?, ?)")) {
            statement.setString(1, entry.providerId.toString()); statement.setLong(2, entry.expiresAt.toEpochMilli()); statement.setBytes(3, encrypted);
            if (statement.executeUpdate() != 1) throw failure();
        }
    }

    private void update(ReaderRelayEnvelope entry) throws SQLException {
        byte[] encrypted = encrypt(entry);
        int oldSize;
        try (var statement = database.prepareStatement("SELECT length(payload) FROM settlement WHERE id = ?")) {
            statement.setString(1, entry.providerId.toString());
            try (var rows = statement.executeQuery()) { if (!rows.next()) throw failure(); oldSize = rows.getInt(1); }
        }
        capacity(encrypted.length, oldSize, false);
        try (var statement = database.prepareStatement("UPDATE settlement SET payload = ? WHERE id = ? AND expires = ?")) {
            statement.setBytes(1, encrypted); statement.setString(2, entry.providerId.toString()); statement.setLong(3, entry.expiresAt.toEpochMilli());
            if (statement.executeUpdate() != 1) throw failure();
        }
    }

    private void capacity(int incoming, int replaced, boolean inserting) throws SQLException {
        try (var statement = database.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*), COALESCE(SUM(length(payload)), 0) FROM settlement")) {
            if (!rows.next() || rows.getInt(1) + (inserting ? 1 : 0) > MAXIMUM_ENTRIES
                    || rows.getLong(2) - replaced + incoming > MAXIMUM_TOTAL_BYTES) throw failure();
        }
    }

    private ReaderRelayEnvelope find(UUID id) throws SQLException {
        try (var statement = database.prepareStatement("SELECT expires, payload FROM settlement WHERE id = ?")) {
            statement.setString(1, id.toString());
            try (var rows = statement.executeQuery()) { return rows.next() ? decrypt(id, rows.getLong(1), rows.getBytes(2)) : null; }
        }
    }

    private UUID next() throws SQLException {
        try (var statement = database.createStatement(); var rows = statement.executeQuery("SELECT id, expires FROM settlement ORDER BY expires, id LIMIT 17")) {
            while (rows.next()) {
                UUID id = uuid(rows.getString(1));
                // 活跃记录即使到期也由原执行先退出，防止后台和前台互相删除现场。
                if (!live.containsKey(id) && (rows.getLong(2) <= clock.millis()
                        || !clock.instant().isBefore(deferred.getOrDefault(id, Instant.EPOCH)))) return id;
            }
            return null;
        }
    }

    private void delete(UUID id) throws SQLException {
        try (var statement = database.prepareStatement("DELETE FROM settlement WHERE id = ?")) { statement.setString(1, id.toString()); statement.executeUpdate(); }
        deferred.remove(id);
    }

    private void validateStored() throws SQLException {
        capacity(0, 0, false);
        try (var statement = database.createStatement(); var rows = statement.executeQuery("SELECT id, expires, payload FROM settlement")) {
            while (rows.next()) decrypt(uuid(rows.getString(1)), rows.getLong(2), rows.getBytes(3));
        }
    }

    private byte[] encrypt(ReaderRelayEnvelope entry) {
        byte[] plain = entry.encode();
        try {
            if (plain.length > MAXIMUM_ENTRY_BYTES) throw failure();
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(entry.providerId, entry.expiresAt.toEpochMilli()));
            byte[] encrypted = cipher.doFinal(plain);
            return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
        } catch (Exception exception) { throw failure(); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    private ReaderRelayEnvelope decrypt(UUID id, long expiry, byte[] bytes) {
        byte[] plain = null;
        try {
            if (bytes == null || bytes.length < 29 || bytes.length > MAXIMUM_ENTRY_BYTES + 28) throw failure();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, bytes, 0, 12));
            cipher.updateAAD(aad(id, expiry)); plain = cipher.doFinal(bytes, 12, bytes.length - 12);
            ReaderRelayEnvelope entry = ReaderRelayEnvelope.decode(plain);
            if (!id.equals(entry.providerId) || expiry != entry.expiresAt.toEpochMilli()) throw failure();
            return entry;
        } catch (Exception exception) { failed = true; throw failure(); }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }

    private static byte[] aad(UUID id, long expiry) {
        return ByteBuffer.allocate(40).putLong(0x52454c415947434dL).putLong(1L)
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).putLong(expiry).array();
    }
    private static boolean same(ReaderRelayEnvelope first, ReaderRelayEnvelope second) {
        byte[] left = first.encode(); byte[] right = second.encode();
        try { return java.security.MessageDigest.isEqual(left, right); }
        finally { Arrays.fill(left, (byte) 0); Arrays.fill(right, (byte) 0); }
    }
    private static UUID uuid(String value) { UUID id = UUID.fromString(value); if (!id.toString().equals(value)) throw failure(); return id; }
    private static void prepareFile(Path file) throws Exception {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        secureFile(file, true);
        if (!Files.getOwner(file).equals(Files.getOwner(file.getParent()))) throw failure();
    }
    private static void secureFile(Path file, boolean writable) throws Exception {
        var permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !(permissions.equals(PosixFilePermissions.fromString("rw-------"))
                || !writable && permissions.equals(PosixFilePermissions.fromString("r--------")))) throw failure();
    }
    private void ready() { if (closed || failed || database == null || ownerLock == null || !ownerLock.isValid()) throw failure(); }
    private static ReaderAdaptationException conflict() { return new ReaderAdaptationException(ErrorCode.IDEMPOTENCY_CONFLICT, 409); }
    private static ReaderAdaptationException failure() { return new ReaderAdaptationException(ErrorCode.PERSISTENCE_UNAVAILABLE, 0); }

    /** 释放连接和进程锁并擦除可变密钥副本；已持久记录保留给下一宿主恢复。 */
    @Override public synchronized void close() {
        closed = true; live.clear(); deferred.clear();
        try { if (database != null) database.close(); } catch (SQLException ignored) { /* 关闭失败不暴露数据库路径。 */ }
        try { if (ownerLock != null) ownerLock.release(); } catch (Exception ignored) { /* 关闭时保持诊断脱敏。 */ }
        try { if (ownership != null) ownership.close(); } catch (Exception ignored) { /* 关闭时保持诊断脱敏。 */ }
        if (key != null) Arrays.fill(key, (byte) 0);
    }

    /** 不输出密钥路径、记录身份或内容。 */
    @Override public String toString() { return "ReaderSettlementRelay[REDACTED]"; }
}
