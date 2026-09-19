package com.yuyutian.mytools.task.executor.runtime.adaptation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.task.executor.client.adaptation.NovelAdaptationRun;
import com.yuyutian.mytools.task.executor.client.adaptation.ReaderAdaptationException;
import com.yuyutian.mytools.task.executor.common.ErrorCode;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 每执行独立的单次 UDS 入口，只接受固定 run 操作，永不把正文或工作负载令牌交给脚本。 */
public final class NovelAdaptationBroker implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(256).maxNumberLength(8).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Path directory;
    private final Path socket;
    private final Path handleFile;
    private final byte[] handle;
    private final ServerSocketChannel server;
    private final Supplier<NovelAdaptationRun.Result> execution;
    private final BooleanSupplier permitted;
    private final Instant deadline;
    private final AtomicReference<SocketChannel> connected = new AtomicReference<>();
    private final AtomicReference<NovelAdaptationRun.Result> result = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean consumed = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;

    /** 只在可信短路径 0700 根目录下创建随机执行目录，避免 Unix socket 路径截断和跨执行复用。 */
    public NovelAdaptationBroker(Path root, Supplier<NovelAdaptationRun.Result> execution, BooleanSupplier permitted, Instant deadline) {
        Path created = null; ServerSocketChannel channel = null; byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
        try {
            if (root == null || !root.isAbsolute() || !root.normalize().equals(root.toRealPath()) || root.toString().getBytes(StandardCharsets.UTF_8).length > 48
                    || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------"))
                    || execution == null || permitted == null || deadline == null) throw invalid();
            created = Files.createTempDirectory(root, "e-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            directory = created; socket = created.resolve("b.sock"); handleFile = created.resolve("handle");
            handle = Base64.getUrlEncoder().withoutPadding().encode(secret);
            Files.createFile(handleFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(handleFile, handle);
            channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX); channel.bind(UnixDomainSocketAddress.of(socket));
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
            server = channel; this.execution = execution; this.permitted = permitted; this.deadline = deadline;
            Thread.ofVirtual().name("novel-adaptation-broker").start(this::serve);
        } catch (Exception exception) {
            try { if (channel != null) channel.close(); } catch (Exception ignored) { /* 失败清理不泄露路径。 */ }
            cleanup(created);
            throw invalid();
        } finally { java.util.Arrays.fill(secret, (byte) 0); }
    }

    /** 返回本次脚本唯一可写挂载目录；该目录不包含 Provider/TLS/Reader 凭据或章节正文。 */
    public Path directory() { return directory; }
    /** 返回宿主已经确认的结果；不能用脚本退出码代替 Reader 的采用结果。 */
    public Optional<NovelAdaptationRun.Result> result() { return Optional.ofNullable(result.get()); }

    private void serve() {
        for (int request = 0; request < 8 && !closed; request++) {
            try (SocketChannel client = server.accept()) {
                connected.set(client); client.configureBlocking(false);
                if (!authorized(client)) { reply(client, new NovelAdaptationRun.Result("FAILED", ErrorCode.REQUEST_INVALID)); continue; }
                // 合法 handle 只消费一次，后续连接无法重新运行或进入其他资源。
                if (!consumed.compareAndSet(false, true)) return;
                server.close();
                Files.deleteIfExists(handleFile);
                NovelAdaptationRun.Result completed;
                try {
                    if (closed || !Instant.now().isBefore(deadline) || !permitted.getAsBoolean()) completed = new NovelAdaptationRun.Result("CANCELLED", ErrorCode.FENCED);
                    else completed = execution.get();
                } catch (RuntimeException ignored) {
                    // 先发布宿主失败结果，再关闭连接，避免子进程退出与结果读取竞态。
                    completed = new NovelAdaptationRun.Result("FAILED", ErrorCode.UNKNOWN);
                }
                if (completed == null) completed = new NovelAdaptationRun.Result("FAILED", ErrorCode.UNKNOWN);
                result.set(completed); reply(client, completed); return;
            } catch (Exception ignored) {
                if (closed) return;
                if (consumed.get()) { result.compareAndSet(null, new NovelAdaptationRun.Result("FAILED", ErrorCode.UNKNOWN)); return; }
                // 非法协议、断连和外部异常均不输出消息、handle 或路径。
            } finally { connected.set(null); }
        }
    }

    private boolean authorized(SocketChannel client) {
        try (Selector selector = Selector.open()) {
            client.register(selector, SelectionKey.OP_READ);
            ByteBuffer buffer = ByteBuffer.allocate(2049);
            long cutoff = System.nanoTime() + 2_000_000_000L;
            while (!closed && Instant.now().isBefore(deadline) && System.nanoTime() < cutoff) {
                int read = client.read(buffer);
                if (read < 0 || buffer.position() > 2048) return false;
                if (read > 0) {
                    int end = buffer.position(); byte[] bytes = buffer.array();
                    for (int index = end - read; index < end; index++) {
                        if (bytes[index] != '\n') continue;
                        if (index != end - 1) return false;
                        var value = JSON.readTree(java.util.Arrays.copyOf(bytes, index));
                        return value != null && value.isObject() && value.size() == 2 && value.path("op").isTextual()
                                && "run".equals(value.get("op").textValue()) && value.path("handle").isTextual()
                                && value.get("handle").textValue().matches("[A-Za-z0-9_-]{43}")
                                && MessageDigest.isEqual(handle, value.get("handle").textValue().getBytes(StandardCharsets.US_ASCII));
                    }
                }
                selector.select(100); selector.selectedKeys().clear();
            }
        } catch (Exception ignored) { /* 解析失败只返回固定拒绝，不泄露报文。 */ }
        return false;
    }

    private void reply(SocketChannel client, NovelAdaptationRun.Result outcome) throws Exception {
        var value = JSON.createObjectNode().put("status", outcome.status()).put("errorCode", outcome.errorCode() == null ? null : outcome.errorCode().code());
        byte[] bytes = (JSON.writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        try (Selector selector = Selector.open()) {
            client.register(selector, SelectionKey.OP_WRITE); long cutoff = System.nanoTime() + 2_000_000_000L;
            while (!closed && buffer.hasRemaining() && System.nanoTime() < cutoff) {
                client.write(buffer); if (buffer.hasRemaining()) { selector.select(100); selector.selectedKeys().clear(); }
            }
        }
    }

    /** 关闭当前执行入口并清理本工具创建的固定文件，不扫描或递归删除调用方目录。 */
    @Override public void close() {
        closed = true;
        try { server.close(); } catch (Exception ignored) { /* 关闭时不输出 socket 路径。 */ }
        SocketChannel client = connected.getAndSet(null);
        try { if (client != null) client.close(); } catch (Exception ignored) { /* 关闭时不输出协议内容。 */ }
        java.util.Arrays.fill(handle, (byte) 0);
        cleanup(directory);
    }

    private static void cleanup(Path directory) {
        if (directory == null) return;
        for (String file : new String[]{"b.sock", "handle"}) {
            try { Files.deleteIfExists(directory.resolve(file)); } catch (Exception ignored) { /* 保留非空失败现场，不递归删除。 */ }
        }
        try { Files.deleteIfExists(directory); } catch (Exception ignored) { /* 脚本额外文件不由本入口递归清理。 */ }
    }
    private static ReaderAdaptationException invalid() { return new ReaderAdaptationException(ErrorCode.DISABLED, 0); }
}
