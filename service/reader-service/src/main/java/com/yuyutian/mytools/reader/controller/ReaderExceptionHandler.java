package com.yuyutian.mytools.reader.controller;

import com.yuyutian.mytools.reader.model.ErrorCode;
import com.yuyutian.mytools.reader.service.SearchNotFoundException;
import com.yuyutian.mytools.reader.service.DiscoveryNotFoundException;
import com.yuyutian.mytools.reader.service.HealthCheckNotFoundException;
import com.yuyutian.mytools.reader.service.EbookImportNotFoundException;
import com.yuyutian.mytools.reader.service.EbookSourceNotFoundException;
import com.yuyutian.mytools.reader.service.EbookCatalogInvalidException;
import com.yuyutian.mytools.reader.service.EbookCatalogNotReadyException;
import com.yuyutian.mytools.reader.service.ChapterCacheInvalidException;
import com.yuyutian.mytools.reader.service.ChapterCacheNotFoundException;
import com.yuyutian.mytools.reader.service.ChapterPrefetchNotFoundException;
import com.yuyutian.mytools.reader.service.CacheMaintenanceConflictException;
import com.yuyutian.mytools.reader.service.CacheMaintenanceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 阅读服务异常响应转换器。
 */
@RestControllerAdvice
public class ReaderExceptionHandler {

    /**
     * 转换搜索请求不存在异常。
     *
     * @param exception 业务异常
     * @return 标准错误响应
     */
    @ExceptionHandler(SearchNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(SearchNotFoundException exception) {
        return Map.of("code", ErrorCode.SEARCH_NOT_FOUND.code(),
                "message", ErrorCode.SEARCH_NOT_FOUND.message());
    }

    /**
     * 转换书源发现请求不存在异常。
     *
     * @param exception 业务异常
     * @return 标准错误响应
     */
    @ExceptionHandler(DiscoveryNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleDiscoveryNotFound(DiscoveryNotFoundException exception) {
        return Map.of("code", ErrorCode.DISCOVERY_NOT_FOUND.code(),
                "message", ErrorCode.DISCOVERY_NOT_FOUND.message());
    }

    /**
     * 转换健康检查不存在异常。
     *
     * @param exception 业务异常
     * @return 标准错误响应
     */
    @ExceptionHandler(HealthCheckNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleHealthCheckNotFound(HealthCheckNotFoundException exception) {
        return Map.of("code", ErrorCode.HEALTH_CHECK_NOT_FOUND.code(),
                "message", ErrorCode.HEALTH_CHECK_NOT_FOUND.message());
    }

    /**
     * 转换电子书导入请求不存在异常。
     *
     * @param exception 业务异常
     * @return 标准错误响应
     */
    @ExceptionHandler(EbookImportNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleEbookImportNotFound(EbookImportNotFoundException exception) {
        return Map.of("code", ErrorCode.EBOOK_IMPORT_NOT_FOUND.code(),
                "message", ErrorCode.EBOOK_IMPORT_NOT_FOUND.message());
    }

    /**
     * 转换电子书书源不存在异常。
     *
     * @param exception 业务异常
     * @return 标准错误响应
     */
    @ExceptionHandler(EbookSourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleEbookSourceNotFound(EbookSourceNotFoundException exception) {
        return Map.of("code", ErrorCode.EBOOK_SOURCE_NOT_FOUND.code(),
                "message", ErrorCode.EBOOK_SOURCE_NOT_FOUND.message());
    }

    /**
     * 转换电子书目录尚未就绪异常。
     *
     * @param exception 业务异常
     * @return 标准错误响应
     */
    @ExceptionHandler(EbookCatalogNotReadyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleEbookCatalogNotReady(EbookCatalogNotReadyException exception) {
        return Map.of("code", ErrorCode.EBOOK_CATALOG_NOT_READY.code(),
                "message", ErrorCode.EBOOK_CATALOG_NOT_READY.message());
    }

    /**
     * 转换电子书目录批次无效异常。
     *
     * @param exception 业务异常
     * @return 标准错误响应
     */
    @ExceptionHandler(EbookCatalogInvalidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleEbookCatalogInvalid(EbookCatalogInvalidException exception) {
        return Map.of("code", ErrorCode.EBOOK_CATALOG_INVALID.code(),
                "message", ErrorCode.EBOOK_CATALOG_INVALID.message());
    }

    /**
     * 转换章节预取请求不存在异常。
     */
    @ExceptionHandler(ChapterPrefetchNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleChapterPrefetchNotFound(ChapterPrefetchNotFoundException exception) {
        return Map.of("code", ErrorCode.CHAPTER_PREFETCH_NOT_FOUND.code(),
                "message", ErrorCode.CHAPTER_PREFETCH_NOT_FOUND.message());
    }

    /**
     * 转换章节缓存不存在异常。
     */
    @ExceptionHandler(ChapterCacheNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleChapterCacheNotFound(ChapterCacheNotFoundException exception) {
        return Map.of("code", ErrorCode.CHAPTER_CACHE_NOT_FOUND.code(),
                "message", ErrorCode.CHAPTER_CACHE_NOT_FOUND.message());
    }

    /**
     * 转换章节缓存批次无效异常。
     */
    @ExceptionHandler(ChapterCacheInvalidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleChapterCacheInvalid(ChapterCacheInvalidException exception) {
        return Map.of("code", ErrorCode.CHAPTER_CACHE_INVALID.code(),
                "message", ErrorCode.CHAPTER_CACHE_INVALID.message());
    }

    /**
     * 转换缓存维护任务不存在异常。
     */
    @ExceptionHandler(CacheMaintenanceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleCacheMaintenanceNotFound(CacheMaintenanceNotFoundException exception) {
        return Map.of("code", ErrorCode.CACHE_MAINTENANCE_NOT_FOUND.code(),
                "message", ErrorCode.CACHE_MAINTENANCE_NOT_FOUND.message());
    }

    /**
     * 转换缓存维护冲突异常。
     */
    @ExceptionHandler(CacheMaintenanceConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleCacheMaintenanceConflict(CacheMaintenanceConflictException exception) {
        return Map.of("code", ErrorCode.CACHE_MAINTENANCE_CONFLICT.code(),
                "message", ErrorCode.CACHE_MAINTENANCE_CONFLICT.message());
    }
}
