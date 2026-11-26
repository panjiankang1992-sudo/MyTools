package com.yuyutian.mytools.toolbox.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/**
 * 册次：上册与下册。
 * 序列化值使用契约约定的小写英文标识。
 */
public enum Semester {

    /** 上册。 */
    FIRST("first"),
    /** 下册。 */
    SECOND("second");

    private final String value;

    Semester(String value) {
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
    public static Optional<Semester> parse(String value) {
        return Arrays.stream(values()).filter(item -> item.value.equals(value)).findFirst();
    }

    /**
     * JSON 反序列化入口，未知取值直接失败以保证发布内容严格合法。
     *
     * @param value 序列化值
     * @return 枚举值
     */
    @JsonCreator
    public static Semester fromJson(String value) {
        return parse(value).orElseThrow(() -> new IllegalArgumentException("unknownSemester"));
    }
}
