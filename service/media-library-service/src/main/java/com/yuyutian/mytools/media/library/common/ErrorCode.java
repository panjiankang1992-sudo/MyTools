package com.yuyutian.mytools.media.library.common;

/** 媒体标签与预览业务错误码。 */
public enum ErrorCode {
    DOWNLOAD_MEDIA_NOT_FOUND("MEDIA_010"),
    DOWNLOAD_SOURCE_NOT_FOUND("MEDIA_011"),
    PREVIEW_INVALID_INPUT("MEDIA_012"),
    PREVIEW_UNAVAILABLE("MEDIA_013"),
    PREVIEW_BUSY("MEDIA_014"),
    PREVIEW_CONVERSION_FAILED("MEDIA_015"),
    PREVIEW_UNAUTHORIZED("MEDIA_016"),
    CONTENT_NOT_FOUND("MEDIA_017"),
    CONTENT_STORAGE_INVALID("MEDIA_018");

    private final String code;

    ErrorCode(String code) {
        this.code = code;
    }

    /** 返回稳定的业务错误码。 @return 错误码。 */
    public String code() {
        return code;
    }
}
