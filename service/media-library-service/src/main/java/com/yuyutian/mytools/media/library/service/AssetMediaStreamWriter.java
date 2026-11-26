package com.yuyutian.mytools.media.library.service;

import com.yuyutian.mytools.media.library.common.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

/** 将单段读取范围传给存储网关，保持原片传输占用的内存有界。 */
final class AssetMediaStreamWriter {
    private AssetMediaStreamWriter() {}

    static void write(RestClient client, String location, String mime, long size, String range,
                      boolean head, HttpServletResponse response) {
        long first = 0;
        long last = size - 1;
        boolean partial = range != null && !range.isBlank();
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        if (partial) {
            try {
                var ranges = HttpRange.parseRanges(range);
                if (ranges.size() != 1) throw new IllegalArgumentException();
                first = ranges.getFirst().getRangeStart(size);
                last = ranges.getFirst().getRangeEnd(size);
                if (first < 0 || first > last || last >= size) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
                response.setContentLengthLong(0);
                return;
            }
        }
        URI uri = URI.create(location);
        if (!"storage".equals(uri.getScheme()) || uri.getHost() == null || uri.getPath().length() < 2) {
            throw invalidStorage();
        }
        // 使用模板变量编码对象路径，空格、加号和非 ASCII 文件名只编码一次。
        var request = client.get().uri(builder -> builder.path("/api/internal/v1/storage/objects/content")
                .queryParam("rootName", "{root}").queryParam("path", "{object}")
                .build(uri.getHost(), uri.getPath().substring(1)));
        String contentRange = "bytes " + first + "-" + last + "/" + size;
        long length = last - first + 1;
        // HEAD 只探测一个字节，避免为了报告长度而在内部读取整部视频。
        if (head || partial) request.header(HttpHeaders.RANGE, head ? "bytes=0-0" : "bytes=" + first + "-" + last);
        request.exchange((outgoing, upstream) -> {
            int expectedStatus = head || partial ? 206 : 200;
            String expectedRange = head ? "bytes 0-0/" + size : contentRange;
            if (upstream.getStatusCode().value() != expectedStatus
                    || upstream.getHeaders().getContentLength() != (head ? 1 : length)
                    || (head || partial) && !expectedRange.equals(upstream.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))) {
                throw invalidStorage();
            }
            response.setStatus(partial ? 206 : 200);
            response.setContentType(mime);
            response.setContentLengthLong(length);
            if (partial) response.setHeader(HttpHeaders.CONTENT_RANGE, contentRange);
            // 源流在回调结束时关闭；直接转发，不将大型原片物化为字节数组。
            if (!head) upstream.getBody().transferTo(response.getOutputStream());
            return null;
        });
    }

    private static ResponseStatusException invalidStorage() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, ErrorCode.CONTENT_STORAGE_INVALID.code());
    }
}
