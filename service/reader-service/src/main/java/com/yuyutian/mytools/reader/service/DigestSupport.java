package com.yuyutian.mytools.reader.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 阅读领域摘要计算工具。
 */
public final class DigestSupport {

    private DigestSupport() {
    }

    /**
     * 计算 SHA-256 摘要。
     */
    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
