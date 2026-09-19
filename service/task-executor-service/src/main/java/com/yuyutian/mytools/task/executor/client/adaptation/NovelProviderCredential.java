package com.yuyutian.mytools.task.executor.client.adaptation;

import com.yuyutian.mytools.task.executor.common.ErrorCode;

import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/** 只在宿主边界加载凭据；文件权限不能替代子进程账号与挂载隔离。 */
final class NovelProviderCredential implements AutoCloseable {
    private final String header;
    private final String prefix;
    private final char[] secret;
    private final int[] fallback;
    private boolean closed;

    NovelProviderCredential(Path file, String header, String prefix) {
        byte[] bytes = null;
        try {
            if (header == null || !(header.equalsIgnoreCase("Authorization") || header.equalsIgnoreCase("api-key")
                    || header.toLowerCase(Locale.ROOT).matches("x-(api-key|auth-[a-z0-9-]{1,40})"))
                    || prefix == null || !prefix.matches("[A-Za-z0-9_-]{0,32}")) throw new IllegalArgumentException();
            Set<PosixFilePermission> permissions = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (file == null || !file.isAbsolute() || !file.normalize().equals(file.toRealPath())
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !permissions.containsAll(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS))
                    || !Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).contains(PosixFilePermission.OWNER_READ)) {
                throw new IllegalArgumentException();
            }
            try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = stream.readNBytes(4097); }
            if (bytes.length > 4096 || bytes.length < 16) throw new IllegalArgumentException();
            int length = bytes.length;
            if (bytes[length - 1] == '\n') length--;
            if (length < 16) throw new IllegalArgumentException();
            for (int index = 0; index < length; index++) {
                if (bytes[index] < 0x21 || bytes[index] > 0x7e) throw new IllegalArgumentException();
            }
            this.header = header;
            this.prefix = prefix.isEmpty() ? "" : prefix + " ";
            secret = new char[length];
            for (int index = 0; index < length; index++) secret[index] = (char) bytes[index];
            fallback = new int[length];
            for (int index = 1, matched = 0; index < length; index++) {
                while (matched > 0 && secret[index] != secret[matched]) matched = fallback[matched - 1];
                if (secret[index] == secret[matched]) matched++;
                fallback[index] = matched;
            }
        } catch (Exception exception) {
            throw new NovelProviderException(ErrorCode.DISABLED);
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    synchronized void apply(HttpRequest.Builder request) {
        if (closed) throw new NovelProviderException(ErrorCode.DISABLED);
        request.header(header, prefix + new String(secret));
    }

    synchronized boolean reflectedBy(String value) {
        if (value == null || closed) return closed;
        // 线性匹配避免大响应与长凭据形成二次方扫描。
        for (int index = 0, matched = 0; index < value.length(); index++) {
            while (matched > 0 && value.charAt(index) != secret[matched]) matched = fallback[matched - 1];
            if (value.charAt(index) == secret[matched]) matched++;
            if (matched == secret.length) return true;
        }
        return false;
    }

    /** 释放宿主可擦除副本；JDK 内部请求头副本不具备可证明的立即擦除保证。 */
    @Override public synchronized void close() { closed = true; Arrays.fill(secret, '\0'); Arrays.fill(fallback, 0); }

    /** 不暴露文件路径、认证格式或密钥。 */
    @Override public String toString() { return "NovelProviderCredential[REDACTED]"; }
}
