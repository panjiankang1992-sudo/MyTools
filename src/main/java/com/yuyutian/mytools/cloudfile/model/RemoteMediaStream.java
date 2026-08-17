package com.yuyutian.mytools.cloudfile.model;

import java.io.InputStream;
import java.util.Optional;

/**
 * 远程媒体流及其可安全转发的响应元数据。
 *
 * @param body 远程响应流
 * @param statusCode 远程响应状态码
 * @param contentType 媒体类型
 * @param contentLength 响应体长度
 * @param contentRange 分段响应范围
 * @param acceptRanges 远端支持的范围单位
 * @param etag 实体标签
 * @param lastModified 最后修改时间
 */
public record RemoteMediaStream(
        InputStream body,
        int statusCode,
        Optional<String> contentType,
        Optional<String> contentLength,
        Optional<String> contentRange,
        Optional<String> acceptRanges,
        Optional<String> etag,
        Optional<String> lastModified) {
}
