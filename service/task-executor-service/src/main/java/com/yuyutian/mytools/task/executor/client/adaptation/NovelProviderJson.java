package com.yuyutian.mytools.task.executor.client.adaptation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyutian.mytools.task.executor.common.ErrorCode;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeSet;

/** 严格 UTF-8、重复键拒绝及有界 JSON 的公共实现。 */
final class NovelProviderJson {
    static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20)
                    .maxStringLength(524288).maxNumberLength(16).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private NovelProviderJson() { }

    static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (Exception exception) {
            throw new NovelProviderException(ErrorCode.PROTOCOL);
        }
    }

    static JsonNode parse(String value) {
        try {
            JsonNode result = MAPPER.readTree(value);
            if (result == null || !result.isObject()) throw new IllegalArgumentException();
            return result;
        } catch (Exception exception) {
            throw new NovelProviderException(ErrorCode.PROTOCOL);
        }
    }

    static String encode(JsonNode value) {
        try {
            return MAPPER.writeValueAsString(sorted(value));
        } catch (Exception exception) {
            throw new NovelProviderException(ErrorCode.PROTOCOL);
        }
    }

    private static JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            TreeSet<String> fields = new TreeSet<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.forEach(field -> {
                text("x" + field, 524289);
                result.set(field, sorted(value.get(field)));
            });
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            value.forEach(item -> result.add(sorted(item)));
            return result;
        }
        if (value.isTextual()) text("x" + value.textValue(), 524289);
        return value;
    }

    static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    static void text(String value, int maximum) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maximum) {
            throw new NovelProviderException(ErrorCode.TOO_LARGE);
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            // JSON 转义也可能携带孤立代理项，不能靠 UTF-8 解码器替代此检查。
            if (Character.isHighSurrogate(current)) {
                if (++index == value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new NovelProviderException(ErrorCode.PROTOCOL);
                }
            } else if (Character.isLowSurrogate(current) || current == 0) {
                throw new NovelProviderException(ErrorCode.PROTOCOL);
            }
        }
    }
}
