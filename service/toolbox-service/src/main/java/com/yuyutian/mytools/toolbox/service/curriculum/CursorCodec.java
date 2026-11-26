package com.yuyutian.mytools.toolbox.service.curriculum;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyutian.mytools.toolbox.model.ListType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 条目分页游标编解码器：游标是不透明 base64url 字符串，内部绑定版本与规范化的过滤集合。
 * 任何来自其他版本或不同过滤条件的游标都必须被拒绝，而不是被静默接受。
 */
public final class CursorCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private CursorCodec() {
    }

    /**
     * 游标负载。
     *
     * @param versionKey 版本键，形如 editionId@contentVersion
     * @param filterHash 规范化过滤集合的 SHA-256
     * @param offset     已消费的条目数量
     */
    public record CursorPayload(String versionKey, String filterHash, int offset) {
    }

    /**
     * 计算过滤集合的规范化哈希：同维度先排序再拼接，保证并集语义下游标稳定。
     *
     * @param unitIds   单元过滤
     * @param lessonIds 课文过滤
     * @param listTypes 清单类别过滤
     * @return 十六进制 SHA-256
     */
    public static String filterHash(List<String> unitIds, List<String> lessonIds, List<ListType> listTypes) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("units=").append(String.join(",", sorted(unitIds))).append(';');
        canonical.append("lessons=").append(String.join(",", sorted(lessonIds))).append(';');
        List<String> types = new ArrayList<>();
        if (listTypes != null) {
            for (ListType listType : listTypes) {
                types.add(listType.value());
            }
        }
        canonical.append("listTypes=").append(String.join(",", sorted(types)));
        return hex(canonical.toString());
    }

    /**
     * 编码游标。
     *
     * @param versionKey 版本键
     * @param filterHash 过滤哈希
     * @param offset     偏移量
     * @return 不透明游标
     */
    public static String encode(String versionKey, String filterHash, int offset) {
        try {
            CursorPayload payload = new CursorPayload(versionKey, filterHash, offset);
            return ENCODER.encodeToString(MAPPER.writeValueAsBytes(payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("cursorEncodeFailed", exception);
        }
    }

    /**
     * 解码游标；格式非法时返回空，由调用方转换为参数错误。
     *
     * @param cursor 不透明游标
     * @return 游标负载或空
     */
    public static Optional<CursorPayload> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] raw = DECODER.decode(cursor);
            CursorPayload payload = MAPPER.readValue(raw, CursorPayload.class);
            if (payload.versionKey() == null || payload.filterHash() == null || payload.offset() < 0) {
                return Optional.empty();
            }
            return Optional.of(payload);
        } catch (IllegalArgumentException | java.io.IOException exception) {
            return Optional.empty();
        }
    }

    /**
     * 生成稳定排序后的不可变列表。
     *
     * @param values 原始取值，可为空
     * @return 去重且升序的列表
     */
    private static List<String> sorted(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new TreeSet<>(values));
    }

    /**
     * 计算十六进制 SHA-256。
     *
     * @param input 输入字符串
     * @return 十六进制摘要
     */
    private static String hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha256Unavailable", exception);
        }
    }
}
