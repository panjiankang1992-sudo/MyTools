package com.yuyutian.mytools.toolbox.service.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * 会话 token 工具：生成 256 bit 随机 token、计算 SHA-256，并派生会话绑定的 CSRF token。
 * 服务端只保存 token 哈希；CSRF token 由 token 哈希与 guestId 确定性派生，会话期间保持稳定。
 */
@Component
public class SessionTokenService {

    /** token 随机字节数，保证至少 256 bit 熵。 */
    private static final int TOKEN_BYTES = 32;

    /** CSRF 派生时附加的域分隔标签，避免与其他哈希用途混用。 */
    private static final byte[] CSRF_LABEL = "toolbox-csrf-v1".getBytes(StandardCharsets.UTF_8);

    private final SecureRandom secureRandom = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    /**
     * 生成新的会话 token，使用无填充 base64url 编码。
     *
     * @return 原始 token，只在 cookie 中下发
     */
    public String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }

    /**
     * 计算 token 的 SHA-256，用于数据库唯一索引与查找。
     *
     * @param token 原始 token
     * @return 32 字节哈希
     */
    public byte[] hash(String token) {
        return digest(token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 派生会话绑定的 CSRF token。
     *
     * @param tokenHash 会话 token 哈希
     * @param guestId   访客标识
     * @return CSRF token
     */
    public String csrfToken(byte[] tokenHash, String guestId) {
        MessageDigest digest = newDigest();
        digest.update(tokenHash);
        digest.update(guestId.getBytes(StandardCharsets.UTF_8));
        digest.update(CSRF_LABEL);
        return encoder.encodeToString(digest.digest());
    }

    /**
     * 以常量时间比较 CSRF token，避免时序侧信道。
     *
     * @param expected 服务端派生值
     * @param actual   请求头携带值
     * @return 是否一致
     */
    public boolean matches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计算 SHA-256。
     *
     * @param input 输入字节
     * @return 32 字节摘要
     */
    private static byte[] digest(byte[] input) {
        return newDigest().digest(input);
    }

    /**
     * 创建 SHA-256 摘要器。
     *
     * @return 摘要器实例
     */
    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha256Unavailable", exception);
        }
    }
}
