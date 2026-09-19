package com.yuyutian.mytools.reader.model.adaptation;

/** 冻结正文的证据角色，书籍边界必须显式标记。 */
public enum AdaptationContextRole {
    TARGET_ORIGINAL,
    BASE_INPUT,
    PREVIOUS_TAIL,
    NEXT_HEAD,
    BOOK_START_MARKER,
    BOOK_END_MARKER,
    CATALOG_METADATA
}
