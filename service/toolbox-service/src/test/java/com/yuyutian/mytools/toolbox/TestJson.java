package com.yuyutian.mytools.toolbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 测试用 JSON 解析工具。
 */
final class TestJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TestJson() {
    }

    /**
     * 解析 JSON 字符串。
     *
     * @param raw JSON 文本
     * @return 根节点
     */
    static JsonNode parse(String raw) {
        try {
            return MAPPER.readTree(raw == null ? "{}" : raw);
        } catch (Exception failure) {
            throw new IllegalStateException("invalid json", failure);
        }
    }
}
