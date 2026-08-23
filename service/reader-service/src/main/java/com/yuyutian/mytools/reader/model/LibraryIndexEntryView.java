package com.yuyutian.mytools.reader.model;

import java.util.Map;
import java.util.UUID;

/**
 * 已发布书库索引条目。
 */
public record LibraryIndexEntryView(UUID assetId, String bookKey, String title, String author,
                                    String format, String storageUri, String contentSha256,
                                    Map<String, Object> metadata) {
}
