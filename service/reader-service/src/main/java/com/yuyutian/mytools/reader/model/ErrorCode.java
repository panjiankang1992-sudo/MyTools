package com.yuyutian.mytools.reader.model;

/**
 * 阅读服务统一错误码。
 */
public enum ErrorCode {
    SEARCH_NOT_FOUND("READER_001", "Search request was not found"),
    DISCOVERY_NOT_FOUND("READER_002", "Source discovery request was not found"),
    INTERNAL_UNAUTHORIZED("READER_003", "Internal service token is invalid"),
    HEALTH_CHECK_NOT_FOUND("READER_004", "Source health check was not found"),
    HEALTH_SOURCE_LIMIT("READER_005", "Enabled source count exceeds health check limit"),
    EBOOK_IMPORT_NOT_FOUND("READER_006", "Ebook import request was not found"),
    EBOOK_SOURCE_NOT_FOUND("READER_007", "Enabled book source was not found"),
    EBOOK_CATALOG_NOT_READY("READER_008", "Ebook import catalog is not ready"),
    EBOOK_CATALOG_INVALID("READER_009", "Ebook catalog batch is invalid"),
    CHAPTER_PREFETCH_NOT_FOUND("READER_010", "Chapter prefetch request was not found"),
    CHAPTER_CACHE_NOT_FOUND("READER_011", "Chapter cache entry was not found"),
    CHAPTER_CACHE_INVALID("READER_012", "Chapter cache batch is invalid"),
    CACHE_MAINTENANCE_NOT_FOUND("READER_013", "Chapter cache maintenance was not found"),
    CACHE_MAINTENANCE_CONFLICT("READER_014", "Chapter cache maintenance conflicts with existing state"),
    LIBRARY_REBUILD_NOT_FOUND("READER_015", "Library rebuild was not found"),
    LIBRARY_REBUILD_CONFLICT("READER_016", "Library rebuild conflicts with existing state"),
    READER_STATE_NOT_FOUND("READER_017", "Reader state was not found"),
    READER_STATE_CONFLICT("READER_018", "Reader state version conflicts with existing state");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 返回稳定错误码。
     *
     * @return 错误码
     */
    public String code() {
        return code;
    }

    /**
     * 返回默认英文错误说明。
     *
     * @return 错误说明
     */
    public String message() {
        return message;
    }
}
