package com.yuyutian.mytools.utils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM 加密工具类。
 *
 * @author mytools
 * @since 2026-04-22
 */
public class AesEncryptUtils {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    /**
     * 校验外部提供的AES密钥是否为合法长度。
     *
     * @param key Base64编码的密钥
     * @return 原始密钥字符串
     */
    public static String requireValidKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("MYTOOLS_ENCRYPTION_KEY is required");
        }
        try {
            int length = Base64.getDecoder().decode(key).length;
            if (length != 16 && length != 24 && length != 32) {
                throw new IllegalStateException("MYTOOLS_ENCRYPTION_KEY must contain 16, 24, or 32 bytes");
            }
            return key;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("MYTOOLS_ENCRYPTION_KEY must be valid Base64", exception);
        }
    }

    /**
     * AES-GCM 加密。
     *
     * @param plaintext 明文
     * @param key Base64编码的密钥
     * @return Base64编码的密文（包含IV）
     */
    public static String encrypt(String plaintext, String key) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(requireValidKey(key));
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");

            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            byte[] cipherText = cipher.doFinal(plaintext.getBytes());

            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);
            byteBuffer.put(iv);
            byteBuffer.put(cipherText);

            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            throw new RuntimeException("加密失败", e);
        }
    }

    /**
     * AES-GCM 解密。
     *
     * @param ciphertext Base64编码的密文（包含IV）
     * @param key Base64编码的密钥
     * @return 明文
     */
    public static String decrypt(String ciphertext, String key) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(requireValidKey(key));
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");

            byte[] decoded = Base64.getDecoder().decode(ciphertext);
            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);

            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);

            byte[] cipherText = new byte[byteBuffer.remaining()];
            byteBuffer.get(cipherText);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText);
        } catch (Exception e) {
            throw new RuntimeException("解密失败", e);
        }
    }

    /**
     * 使用当前密钥解密，失败时可使用迁移期旧密钥重试。
     *
     * @param ciphertext Base64编码密文
     * @param currentKey 当前密钥
     * @param previousKey 可选旧密钥
     * @return 解密后的明文
     */
    public static String decryptWithKeyRing(String ciphertext, String currentKey, String previousKey) {
        try {
            return decrypt(ciphertext, requireValidKey(currentKey));
        } catch (RuntimeException currentFailure) {
            if (previousKey == null || previousKey.isBlank()) {
                throw currentFailure;
            }
            return decrypt(ciphertext, requireValidKey(previousKey));
        }
    }

    /**
     * 生成随机AES密钥。
     *
     * @return Base64编码的32字节密钥
     */
    public static String generateKey() {
        byte[] keyBytes = new byte[32];
        SecureRandom random = new SecureRandom();
        random.nextBytes(keyBytes);
        return Base64.getEncoder().encodeToString(keyBytes);
    }

    /**
     * 命令行工具。
     */
    public static void main(String[] args) {
        if (args.length > 0 && "generate-key".equals(args[0])) {
            System.out.println(generateKey());
            return;
        }
        if (args.length >= 2) {
            if ("decrypt".equals(args[0])) {
                System.out.println(decrypt(args[1], args[2]));
            } else {
                System.out.println(encrypt(args[0], args[1]));
            }
            return;
        }
        System.out.println("用法:");
        System.out.println("  AesEncryptUtils generate-key");
        System.out.println("  AesEncryptUtils <明文> <密钥>");
        System.out.println("  AesEncryptUtils decrypt <密文> <密钥>");
    }
}
