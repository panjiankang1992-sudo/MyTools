package com.yuyutian.mytools.drive.controller;

import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.drive.infrastructure.rclone.RcloneContent;
import com.yuyutian.mytools.drive.infrastructure.rclone.RcloneGateway;
import com.yuyutian.mytools.drive.model.DriveOpenTarget;
import com.yuyutian.mytools.drive.service.DriveTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 使用短期票据代理读取 rclone 文件内容。
 */
@RestController
@RequiredArgsConstructor
public class DriveStreamController {

    private final DriveTicketService ticketService;
    private final RcloneGateway rcloneGateway;

    /**
     * 支持 Range 的统一网盘文件流。
     *
     * @param ticket 票据
     * @param requestHeaders 请求头
     * @return 文件流
     */
    @GetMapping("/api/app/v1/drive-tickets/{ticket}")
    public ResponseEntity<StreamingResponseBody> stream(@PathVariable String ticket,
                                                         @RequestHeader HttpHeaders requestHeaders) {
        DriveTicketService.TicketBinding binding = ticketService.resolve(ticket);
        if (binding == null) {
            throw new BusinessException(ErrorCode.DRIVE_005);
        }
        DriveOpenTarget target = binding.target();
        List<HttpRange> ranges = requestHeaders.getRange();
        if (!ranges.isEmpty()) {
            return streamRange(target, ranges.get(0));
        }
        RcloneContent content = rcloneGateway.open(target.remoteKey(), target.remotePath(), 0L, -1L);
        return response(HttpStatus.OK, target, content, 0L, target.sizeBytes() - 1L);
    }

    private ResponseEntity<StreamingResponseBody> streamRange(DriveOpenTarget target, HttpRange range) {
        long start = range.getRangeStart(target.sizeBytes());
        long end = range.getRangeEnd(target.sizeBytes());
        long count = end - start + 1L;
        RcloneContent content = rcloneGateway.open(target.remoteKey(), target.remotePath(), start, count);
        return response(HttpStatus.PARTIAL_CONTENT, target, content, start, end);
    }

    private ResponseEntity<StreamingResponseBody> response(HttpStatus status, DriveOpenTarget target,
                                                            RcloneContent content, long start, long end) {
        StreamingResponseBody body = outputStream -> {
            try (InputStream inputStream = content.inputStream()) {
                inputStream.transferTo(outputStream);
            }
        };
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(mediaType(target.mimeType()))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" +
                        URLEncoder.encode(target.name(), StandardCharsets.UTF_8));
        long length = content.contentLength() >= 0 ? content.contentLength() : end - start + 1L;
        if (length >= 0) {
            builder.contentLength(length);
        }
        if (status == HttpStatus.PARTIAL_CONTENT) {
            builder.header(HttpHeaders.CONTENT_RANGE,
                    "bytes " + start + "-" + end + "/" + target.sizeBytes());
        }
        return builder.body(body);
    }

    private MediaType mediaType(String value) {
        try {
            return value == null || value.isBlank() ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(value);
        } catch (IllegalArgumentException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
