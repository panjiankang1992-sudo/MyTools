package com.yuyutian.mytools.storage.controller;

import com.yuyutian.mytools.storage.service.InternalAuthorizer;
import com.yuyutian.mytools.storage.service.StorageObjectService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * 内部受管对象流式读取接口。
 */
@RestController
@RequestMapping("/api/internal/v1/storage/objects")
public class StorageObjectController {

    private final StorageObjectService objectService;
    private final InternalAuthorizer authorizer;

    /**
     * 创建对象读取控制器。
     *
     * @param objectService 对象服务
     * @param authorizer 内部鉴权器
     */
    public StorageObjectController(StorageObjectService objectService, InternalAuthorizer authorizer) {
        this.objectService = objectService;
        this.authorizer = authorizer;
    }

    /**
     * 流式读取一个受管对象。
     *
     * @param authorization 内部授权头
     * @param rootName 受管根名称
     * @param path 根内相对路径
     * @param range 可选的单段字节范围
     * @return 对象内容
     * @throws IOException 无法打开已验证文件
     */
    @GetMapping("/content")
    public ResponseEntity<InputStreamResource> content(
            @RequestHeader("Authorization") String authorization,
            @RequestParam String rootName,
            @RequestParam String path,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) throws IOException {
        authorizer.require(authorization);
        var object = objectService.requireReadable(rootName, path);
        if (range != null && !range.isBlank()) {
            ByteRange requested = parseRange(range, object.size());
            InputStream input = Files.newInputStream(object.path());
            input.skipNBytes(requested.start());
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + requested.start() + "-" + requested.end()
                            + "/" + object.size())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(requested.length())
                    .body(new InputStreamResource(new LimitedInputStream(input, requested.length())));
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentLength(object.size()).body(new InputStreamResource(Files.newInputStream(object.path())));
    }

    private ByteRange parseRange(String value, long size) {
        if (size <= 0 || !value.startsWith("bytes=") || value.indexOf(',') >= 0) {
            throw new IllegalArgumentException("invalid byte range");
        }
        String raw = value.substring("bytes=".length()).trim();
        int separator = raw.indexOf('-');
        if (separator < 0 || raw.indexOf('-', separator + 1) >= 0) {
            throw new IllegalArgumentException("invalid byte range");
        }
        try {
            String startValue = raw.substring(0, separator).trim();
            String endValue = raw.substring(separator + 1).trim();
            if (startValue.isEmpty()) {
                long suffixLength = Long.parseLong(endValue);
                if (suffixLength <= 0) {
                    throw new IllegalArgumentException("invalid byte range");
                }
                long length = Math.min(suffixLength, size);
                return new ByteRange(size - length, size - 1);
            }
            long start = Long.parseLong(startValue);
            long end = endValue.isEmpty() ? size - 1 : Long.parseLong(endValue);
            if (start < 0 || end < start || start >= size) {
                throw new IllegalArgumentException("invalid byte range");
            }
            return new ByteRange(start, Math.min(end, size - 1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid byte range", exception);
        }
    }

    private record ByteRange(long start, long end) {
        private long length() {
            return end - start + 1;
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        private LimitedInputStream(InputStream input, long remaining) {
            super(input);
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}
