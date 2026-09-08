package com.yuyutian.mytools.reader.model;

/**
 * 可由内部网关流式读取的已完成导出归档定位信息。
 *
 * @param storageUri 受管归档地址
 * @param sizeBytes 归档大小
 * @param fileName 可信下载文件名
 */
public record AudiobookExportArchive(String storageUri, long sizeBytes, String fileName) {
}
