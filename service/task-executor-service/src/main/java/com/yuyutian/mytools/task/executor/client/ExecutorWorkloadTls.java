package com.yuyutian.mytools.task.executor.client;

import com.yuyutian.mytools.task.executor.config.ExecutorProperties;
import com.yuyutian.mytools.task.executor.config.ExecutorWorkloadTlsProperties;
import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** 加载专属 PKCS12 凭据，仅向宿主 HTTP 客户端提供 mTLS，不给子进程导出私钥。 */
@Component
public final class ExecutorWorkloadTls {
    private final HttpClient client;
    private final String thumbprint;

    /** 建立受限信任库、证书身份和 HTTPS 主机名校验，不启用任何信任全部证书的降级。 */
    public ExecutorWorkloadTls(ExecutorProperties executor, ExecutorWorkloadTlsProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (!properties.enabled()) {
            client = builder.build();
            thumbprint = null;
            return;
        }
        byte[] keyBytes = null;
        byte[] trustBytes = null;
        char[] keyPassword = null;
        char[] trustPassword = null;
        try {
            URI scheduler = URI.create(executor.schedulerUrl());
            if (!"https".equals(scheduler.getScheme()) || scheduler.getHost() == null
                    || scheduler.getRawUserInfo() != null || scheduler.getRawQuery() != null
                    || scheduler.getRawFragment() != null
                    || !(scheduler.getPath().isEmpty() || "/".equals(scheduler.getPath()))) {
                throw new IllegalArgumentException();
            }
            keyBytes = credential(properties.keyStoreFile(), 131072);
            trustBytes = credential(properties.trustStoreFile(), 131072);
            keyPassword = password(properties.keyStorePasswordFile());
            trustPassword = password(properties.trustStorePasswordFile());
            KeyStore keys = store(keyBytes, keyPassword);
            KeyStore trust = store(trustBytes, trustPassword);
            List<String> aliases = Collections.list(keys.aliases());
            if (aliases.size() != 1 || !keys.isKeyEntry(aliases.getFirst())) {
                throw new IllegalArgumentException();
            }
            var chain = keys.getCertificateChain(aliases.getFirst());
            if (chain == null || chain.length == 0 || chain.length > 8
                    || !(chain[0] instanceof X509Certificate leaf)) {
                throw new IllegalArgumentException();
            }
            leaf.checkValidity();
            if (leaf.getBasicConstraints() >= 0 || leaf.getExtendedKeyUsage() == null
                    || !leaf.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.2")) {
                throw new IllegalArgumentException();
            }
            var alternatives = leaf.getSubjectAlternativeNames();
            List<String> identities = alternatives == null ? List.of() : alternatives.stream()
                    .filter(value -> value.size() == 2 && Integer.valueOf(6).equals(value.getFirst()))
                    .map(value -> String.valueOf(value.get(1))).toList();
            if (!identities.equals(List.of(properties.identity()))) {
                throw new IllegalArgumentException();
            }
            List<String> trusted = Collections.list(trust.aliases());
            if (trusted.isEmpty() || trusted.size() > 16) {
                throw new IllegalArgumentException();
            }
            for (String alias : trusted) {
                // 信任库只接受证书，不允许夹带另一份私钥。
                if (!trust.isCertificateEntry(alias) || !(trust.getCertificate(alias) instanceof X509Certificate)) {
                    throw new IllegalArgumentException();
                }
            }
            KeyManagerFactory keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManager.init(keys, keyPassword);
            TrustManagerFactory trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManager.init(trust);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManager.getKeyManagers(), trustManager.getTrustManagers(), null);
            SSLParameters parameters = new SSLParameters();
            parameters.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            client = builder.sslContext(context).sslParameters(parameters).proxy(HttpClient.Builder.NO_PROXY).build();
            thumbprint = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded()));
        } catch (Exception exception) {
            // 文件路径、密码、证书和底层异常链不得进入启动诊断。
            throw new IllegalStateException("Executor workload TLS initialization failed");
        } finally {
            if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0);
            if (trustBytes != null) Arrays.fill(trustBytes, (byte) 0);
            if (keyPassword != null) Arrays.fill(keyPassword, '\0');
            if (trustPassword != null) Arrays.fill(trustPassword, '\0');
        }
    }

    /** 返回仅供宿主使用、禁用重定向的 HTTP 客户端。 */
    public HttpClient client() { return client; }

    /** 返回叶证书 DER 的 SHA-256 指纹，未启用 mTLS 时返回空值。 */
    public String thumbprint() { return thumbprint; }

    private static KeyStore store(byte[] bytes, char[] password) throws Exception {
        KeyStore result = KeyStore.getInstance("PKCS12");
        result.load(new ByteArrayInputStream(bytes), password);
        return result;
    }

    private static byte[] credential(String location, int maximum) throws Exception {
        Path path = Path.of(location);
        Set<PosixFilePermission> allowed = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        if (!path.isAbsolute() || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !allowed.containsAll(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).contains(PosixFilePermission.OWNER_READ)) {
            throw new IllegalArgumentException();
        }
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(maximum + 1);
            if (bytes.length == 0 || bytes.length > maximum) {
                Arrays.fill(bytes, (byte) 0);
                throw new IllegalArgumentException();
            }
            return bytes;
        }
    }

    private static char[] password(String location) throws Exception {
        byte[] bytes = credential(location, 1024);
        try {
            int length = bytes.length;
            if (bytes[length - 1] == '\n') length--;
            if (length < 6) throw new IllegalArgumentException();
            char[] result = new char[length];
            for (int index = 0; index < length; index++) {
                // 凭据密码使用可打印 ASCII；不通过 String 构造留下不可擦除的副本。
                if (bytes[index] < 0x21 || bytes[index] > 0x7e) {
                    Arrays.fill(result, '\0');
                    throw new IllegalArgumentException();
                }
                result[index] = (char) bytes[index];
            }
            return result;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
