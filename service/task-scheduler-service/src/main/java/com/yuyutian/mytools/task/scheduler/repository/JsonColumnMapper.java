package com.yuyutian.mytools.task.scheduler.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * JSON 文本列转换器。
 */
@Component
public class JsonColumnMapper {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final ObjectMapper objectMapper;

    /**
     * 创建 JSON 文本列转换器。
     *
     * @param objectMapper JSON 映射器
     */
    public JsonColumnMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将 Map 序列化为 JSON。
     *
     * @param value Map 值
     * @return JSON 文本
     */
    public String write(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("JSON value cannot be serialized", exception);
        }
    }

    /**
     * 将 JSON 反序列化为 Map。
     *
     * @param value JSON 文本
     * @return Map 值
     */
    public Map<String, Object> read(String value) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored JSON value cannot be parsed", exception);
        }
    }
}
