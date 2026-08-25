package com.yuyutian.mytools.storage.model;

import java.io.InputStream;

/** 受控远端对象流及可选范围元数据。 */
public record RemoteContent(InputStream stream, long contentLength, int statusCode,
                            String contentRange, String acceptRanges) {
    /** 创建普通完整对象流。 @param stream 流 @param contentLength 长度 */
    public RemoteContent(InputStream stream, long contentLength) {
        this(stream, contentLength, 200, null, null);
    }
}
