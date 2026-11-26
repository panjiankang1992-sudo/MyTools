package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/**
 * 覆盖状态，missing 表示该册尚未发布任何内容。
 * 序列化值使用契约约定的小写英文标识。
 */
public enum CoverageStatus {

    /** 缺失。 */
    MISSING("missing"),
    /** 部分覆盖。 */
    PARTIAL("partial"),
    /** 完整覆盖。 */
    COMPLETE("complete");

    private final String value;

    CoverageStatus(String value) {
        this.value = value;
    }

    /**
     * 返回契约中的序列化值。
     *
     * @return 序列化值
     */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * 按序列化值解析，未知值返回空。
     *
     * @param value 序列化值
     * @return 枚举值或空
     */
    public static Optional<CoverageStatus> parse(String value) {
        return Arrays.stream(values()).filter(item -> item.value.equals(value)).findFirst();
    }

    /**
     * JSON 反序列化入口，未知取值直接失败以保证发布内容严格合法。
     *
     * @param value 序列化值
     * @return 枚举值
     */
    @JsonCreator
    public static CoverageStatus fromJson(String value) {
        return parse(value).orElseThrow(() -> new IllegalArgumentException("unknownCoverageStatus"));
    }
}
