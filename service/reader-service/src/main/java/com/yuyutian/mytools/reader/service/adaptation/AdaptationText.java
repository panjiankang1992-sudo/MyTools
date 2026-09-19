package com.yuyutian.mytools.reader.service.adaptation;

import com.yuyutian.mytools.reader.model.ErrorCode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 改编输入的 Unicode 校验与无歧义摘要计算。 */
public final class AdaptationText {

    private AdaptationText() {
    }

    /** 校验合法 Unicode 及控制字符并返回码点数量。 */
    public static int requireText(String value, int minimum, int maximum, ErrorCode errorCode) {
        if (value == null) {
            throw new ChapterAdaptationException(errorCode);
        }
        int count = 0;
        for (int offset = 0; offset < value.length();) {
            char unit = value.charAt(offset);
            // UTF-8 编码前拒绝孤立代理项，避免不同原文被替换为同一摘要。
            if (Character.isLowSurrogate(unit) || (Character.isHighSurrogate(unit)
                    && (offset + 1 == value.length() || !Character.isLowSurrogate(value.charAt(offset + 1))))) {
                throw new ChapterAdaptationException(errorCode);
            }
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\r' && codePoint != '\t') {
                throw new ChapterAdaptationException(errorCode);
            }
            offset += Character.charCount(codePoint);
            if (++count > maximum) {
                throw new ChapterAdaptationException(errorCode);
            }
        }
        if (count < minimum) {
            throw new ChapterAdaptationException(errorCode);
        }
        return count;
    }

    /** 去除意图首尾 Unicode 空白，保留内部换行和原有含义。 */
    public static String normalizeIntent(String intent) {
        // 先限制原始载荷，不能通过添加大量首尾空白消耗无界资源。
        requireText(intent, 0, 4096, ErrorCode.ADAPTATION_INTENT_INVALID);
        int start = 0;
        int end = intent.length();
        while (start < end && blank(intent.codePointAt(start))) {
            start += Character.charCount(intent.codePointAt(start));
        }
        while (end > start && blank(intent.codePointBefore(end))) {
            end -= Character.charCount(intent.codePointBefore(end));
        }
        String normalized = intent.substring(start, end);
        requireText(normalized, 5, 2000, ErrorCode.ADAPTATION_INTENT_INVALID);
        return normalized;
    }

    /** 校验区分大小写的幂等键，不做大小写或 Unicode 归一。 */
    public static void requireIdempotencyKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 128) {
            throw new ChapterAdaptationException(ErrorCode.ADAPTATION_IDEMPOTENCY_CONFLICT);
        }
        for (int index = 0; index < key.length(); index++) {
            if (key.charAt(index) < 0x21 || key.charAt(index) > 0x7e) {
                // 不接受空白键及非 ASCII 字节，数据库使用 VARBINARY 比较。
                throw new ChapterAdaptationException(ErrorCode.ADAPTATION_IDEMPOTENCY_CONFLICT);
            }
        }
    }

    /** 校验小写十六进制 SHA-256，拒绝空摘要。 */
    public static void requireSha256(String value, ErrorCode errorCode) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new ChapterAdaptationException(errorCode);
        }
    }

    /** 对未经文本归一的 UTF-8 原文计算摘要。 */
    public static String sha256(String value) {
        return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /** 使用版本与逐项字节长度前缀计算摘要，区分空值、空串和含分隔符的文本。 */
    public static String fingerprint(String version, List<String> fields) {
        MessageDigest hash = digest();
        append(hash, version);
        for (String field : fields) {
            append(hash, field);
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static void append(MessageDigest hash, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) {
            hash.update(bytes);
        }
    }

    private static boolean blank(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 是 Java 必需算法，该分支只代表运行环境不符合规范。
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
