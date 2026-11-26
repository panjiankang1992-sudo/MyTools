package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/**
 * 工具一级分类，序列化值使用小写英文业务标识。
 */
public enum ToolCategory {

    /** PDF 工具分类。 */
    PDF("pdf"),
    /** 字帖工具分类。 */
    COPYBOOK("copybook"),
    /** 英语单词工具分类。 */
    WORDS("words");

    private final String value;

    ToolCategory(String value) {
        this.value = value;
    }

    /**
     * 返回契约中的小写序列化值。
     *
     * @return 分类值
     */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * 按序列化值解析分类，未知值返回空。
     *
     * @param value 分类字符串
     * @return 分类或空
     */
    public static Optional<ToolCategory> parse(String value) {
        return Arrays.stream(values()).filter(item -> item.value.equals(value)).findFirst();
    }

    /**
     * JSON 反序列化入口，未知分类直接失败以避免静默接受非法数据。
     *
     * @param value 分类字符串
     * @return 分类
     */
    @JsonCreator
    public static ToolCategory fromJson(String value) {
        return parse(value).orElseThrow(() -> new IllegalArgumentException("unknownCategory"));
    }
}
