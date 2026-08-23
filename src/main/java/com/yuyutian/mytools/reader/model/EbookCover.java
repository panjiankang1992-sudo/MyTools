package com.yuyutian.mytools.reader.model;

/**
 * 受控电子书封面响应。
 *
 * @param content 图片内容
 * @param mediaType 图片媒体类型
 */
public record EbookCover(byte[] content, String mediaType) {
}
