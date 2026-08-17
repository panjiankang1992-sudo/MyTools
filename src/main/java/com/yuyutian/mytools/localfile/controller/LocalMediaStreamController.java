package com.yuyutian.mytools.localfile.controller;

import com.yuyutian.mytools.auth.Model.Token;
import com.yuyutian.mytools.auth.mapper.TokenMapper;
import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.localfile.service.LocalFileService;
import com.yuyutian.mytools.localfile.service.LocalMediaTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/**
 * 为 App 提供 MyTools 本地媒体短期票据和 Range 流式读取。
 */
@RestController
@RequiredArgsConstructor
public class LocalMediaStreamController {

    private final LocalFileService localFileService;
    private final LocalMediaTicketService ticketService;
    private final JwtUtils jwtUtils;
    private final TokenMapper tokenMapper;

    /**
     * 为当前会话签发本地媒体短期票据。
     *
     * @param auth Authorization请求头。
     * @param fileId 文件ID。
     * @return 短期票据。
     */
    @PostMapping("/api/app/v1/local-media/tickets")
    public ResponseEntity<Result<LocalMediaTicketService.TicketResult>> issueTicket(
            @RequestHeader("Authorization") String auth,
            @RequestParam Long fileId) {
        String accessToken = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : auth;
        Long userId = jwtUtils.getUserIdFromToken(accessToken);
        Token session = tokenMapper.findByAccessToken(accessToken);
        if (!activeSession(session, userId)) {
            throw new BusinessException(ErrorCode.AUTH_002);
        }
        // 读取路径会验证文件仍存在且位于已配置目录中。
        localFileService.getReadableFilePath(fileId);
        return ResponseEntity.ok(Result.success(ticketService.issue(userId, session.getId(), fileId)));
    }

    /**
     * 使用短期票据读取支持Range的本地媒体。
     *
     * @param ticket 随机票据。
     * @param requestHeaders 请求头。
     * @return 媒体响应。
     * @throws IOException 文件读取失败。
     */
    @GetMapping("/api/app/v1/local-media/tickets/{ticket}")
    public ResponseEntity<StreamingResponseBody> streamByTicket(@PathVariable String ticket,
                                                                 @RequestHeader HttpHeaders requestHeaders)
            throws IOException {
        LocalMediaTicketService.TicketBinding binding = ticketService.resolve(ticket);
        if (binding == null) {
            throw new BusinessException(ErrorCode.MEDIA_004);
        }
        Token session = tokenMapper.findById(binding.sessionId());
        if (!activeSession(session, binding.userId())) {
            throw new BusinessException(ErrorCode.MEDIA_004);
        }
        Path path = localFileService.getReadableFilePath(binding.fileId());
        MediaType mediaType = resolveMediaType(path);
        List<org.springframework.http.HttpRange> ranges = requestHeaders.getRange();
        if (!ranges.isEmpty()) {
            return streamRange(path, mediaType, ranges.get(0));
        }
        return streamFile(path, mediaType);
    }

    private ResponseEntity<StreamingResponseBody> streamFile(Path path, MediaType mediaType) throws IOException {
        long fileSize = Files.size(path);
        StreamingResponseBody body = outputStream -> Files.copy(path, outputStream);
        return ResponseEntity.ok().contentType(mediaType).contentLength(fileSize)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(body);
    }

    private ResponseEntity<StreamingResponseBody> streamRange(Path path, MediaType mediaType,
                                                                HttpRange range) throws IOException {
        long fileSize = Files.size(path);
        long start = range.getRangeStart(fileSize);
        long end = range.getRangeEnd(fileSize);
        long count = end - start + 1;
        StreamingResponseBody body = outputStream -> {
            try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
                channel.position(start);
                ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
                long remaining = count;
                // 仅写出客户端申请的片段，避免大文件整段进入内存或越过Range边界。
                while (remaining > 0) {
                    buffer.clear();
                    buffer.limit((int) Math.min(buffer.capacity(), remaining));
                    int read = channel.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    outputStream.write(buffer.array(), 0, read);
                    remaining -= read;
                }
            }
        };
        return ResponseEntity.status(org.springframework.http.HttpStatus.PARTIAL_CONTENT)
                .contentType(mediaType).contentLength(count)
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + fileSize)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(body);
    }

    private boolean activeSession(Token session, Long userId) {
        return session != null && Objects.equals(userId, session.getUserId()) && "ACTIVE".equals(session.getStatus())
                && session.getExpireTime() != null && session.getExpireTime() > System.currentTimeMillis();
    }

    private MediaType resolveMediaType(Path path) throws IOException {
        String contentType = Files.probeContentType(path);
        return contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType);
    }
}
