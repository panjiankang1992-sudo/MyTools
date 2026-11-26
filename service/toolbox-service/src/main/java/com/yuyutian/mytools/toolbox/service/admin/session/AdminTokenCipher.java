package com.yuyutian.mytools.toolbox.service.admin.session;

import com.yuyutian.mytools.toolbox.config.AdminProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 上游访问／刷新令牌的对称加密。
 * 使用 AES-GCM，随机 12 字节 IV 随密文一起保存；密钥只从运行环境注入，缺失时直接拒绝加解密，
 * 绝不以明文或固定密钥兜底写库。
 */
@Component
public final class AdminTokenCipher {

    /** GCM 认证标签长度（位）。 */
    private static final int TAG_BITS = 128;
    /** 随机 IV 长度（字节）。 */
    private static final int IV_BYTES = 12;

    private final AdminProperties properties;
    private final SecureRandom random = new SecureRandom();

    /**
     * 创建令牌加密器。
     *
     * @param properties 管理员门面配置
     */
    public AdminTokenCipher(AdminProperties properties) {
        this.properties = properties;
    }

    /**
     * 判断是否已配置可用密钥；未配置时管理写入不可用。
     *
     * @return 可用时为 true
     */
    public boolean isConfigured() {
        return resolveKey() != null;
    }

    /**
     * 加密明文令牌。
     *
     * @param plaintext 明文；允许为空表示没有该令牌
     * @return Base64(IV || 密文)；入参为空时返回 null
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(resolveKey(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(sealed, 0, combined, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception failure) {
            throw new IllegalStateException("admin token encryption failed", failure);
        }
    }

    /**
     * 解密令牌。
     *
     * @param stored Base64(IV || 密文)；为空时返回 null
     * @return 明文令牌
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(resolveKey(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(combined, IV_BYTES, combined.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new IllegalStateException("admin token decryption failed", failure);
        }
    }

    /** 解析配置中的密钥；未配置或长度非法时返回 null（失败关闭）。 */
    private byte[] resolveKey() {
        String configured = properties.encryptionKey();
        if (configured == null || configured.isBlank()) {
            return null;
        }
        try {
            byte[] key = Base64.getDecoder().decode(configured);
            return key.length == 16 || key.length == 24 || key.length == 32 ? key : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
