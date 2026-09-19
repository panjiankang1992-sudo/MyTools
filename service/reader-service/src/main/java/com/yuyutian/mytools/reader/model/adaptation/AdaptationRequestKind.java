package com.yuyutian.mytools.reader.model.adaptation;

/** 用户操作类型，每次操作创建一个独立业务版本。 */
public enum AdaptationRequestKind {
    INITIAL,
    OPTIMIZE,
    REGENERATE
}
