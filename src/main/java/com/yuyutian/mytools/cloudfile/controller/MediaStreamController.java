package com.yuyutian.mytools.cloudfile.controller;

import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.auth.Model.Token;
import com.yuyutian.mytools.auth.mapper.TokenMapper;
import com.yuyutian.mytools.cloudfile.model.MediaPlaybackTicket;
import com.yuyutian.mytools.cloudfile.model.MediaPlaybackMetrics;
import com.yuyutian.mytools.cloudfile.model.RemoteMediaStream;
import com.yuyutian.mytools.cloudfile.service.CloudFileService;
import com.yuyutian.mytools.cloudfile.service.MediaPlaybackTicketService;
import com.yuyutian.mytools.common.BusinessException;
import com.yuyutian.mytools.common.ErrorCode;
import com.yuyutian.mytools.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;

/**
 * 为系统播放器提供短期票据和 Range 流式响应。
 */
@RestController
@RequiredArgsConstructor
public class MediaStreamController {

    private final CloudFileService cloudFileService;
    private final MediaPlaybackTicketService ticketService;
    private final JwtUtils jwtUtils;
    private final TokenMapper tokenMapper;

    /**
     * 为当前用户有权访问的远程文件签发短期播放票据。
     *
     * @param auth Authorization 请求头
     * @param accountId 远程账号ID
     * @param path 远程文件路径
     * @return 播放票据
     */
    @PostMapping("/api/app/v1/media/tickets")
    public ResponseEntity<Result<MediaPlaybackTicket>> issueTicket(
            @RequestHeader("Authorization") String auth,
            @RequestParam("accountId") Long accountId,
            @RequestParam("path") String path) {
        String accessToken = extractToken(auth);
        Long userId = jwtUtils.getUserIdFromToken(accessToken);
        Token session = tokenMapper.findByAccessToken(accessToken);
        if (session == null || !userId.equals(session.getUserId()) || !"ACTIVE".equals(session.getStatus())
                || session.getExpireTime() == null || session.getExpireTime() <= System.currentTimeMillis()) {
            throw new BusinessException(ErrorCode.AUTH_002);
        }
        // 访问父目录以验证账号归属和远端可用性。
        cloudFileService.listFiles(userId, accountId, parentPath(path), 0);
        return ResponseEntity.ok(Result.success(ticketService.issue(userId, session.getId(), accountId, path)));
    }

    /**
     * 使用短期票据流式读取远程媒体。
     *
     * @param ticket 随机播放票据
     * @param rangeHeader 单段字节范围
     * @return 流式媒体响应
     */
    @GetMapping("/api/app/v1/media/tickets/{ticket}")
    public ResponseEntity<StreamingResponseBody> streamByTicket(
            @PathVariable("ticket") String ticket,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        MediaPlaybackTicketService.TicketBinding binding = ticketService.resolve(ticket);
        if (binding == null) {
            throw new BusinessException(ErrorCode.MEDIA_004);
        }
        Token session = tokenMapper.findById(binding.sessionId());
        if (session == null || !binding.userId().equals(session.getUserId())
                || !"ACTIVE".equals(session.getStatus()) || session.getExpireTime() == null
                || session.getExpireTime() <= System.currentTimeMillis()) {
            ticketService.revokeSession(binding.sessionId());
            throw new BusinessException(ErrorCode.MEDIA_004);
        }
        RemoteMediaStream stream = cloudFileService.openMediaStream(
                binding.userId(), binding.accountId(), binding.path(), rangeHeader);
        StreamingResponseBody body = outputStream -> {
            ticketService.streamStarted(ticket);
            try (InputStream inputStream = stream.body()) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = inputStream.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    outputStream.write(buffer, 0, read);
                    ticketService.recordTransfer(ticket, read);
                }
            } finally {
                ticketService.streamFinished(ticket);
            }
        };
        ResponseEntity.BodyBuilder response = ResponseEntity.status(stream.statusCode())
                .header(HttpHeaders.ACCEPT_RANGES, stream.acceptRanges().orElse("bytes"))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff");
        stream.contentType().ifPresent(value -> response.header(HttpHeaders.CONTENT_TYPE, value));
        stream.contentLength().ifPresent(value -> response.header(HttpHeaders.CONTENT_LENGTH, value));
        stream.contentRange().ifPresent(value -> response.header(HttpHeaders.CONTENT_RANGE, value));
        stream.etag().ifPresent(value -> response.header(HttpHeaders.ETAG, value));
        stream.lastModified().ifPresent(value -> response.header(HttpHeaders.LAST_MODIFIED, value));
        return response.body(body);
    }

    /**
     * 获取当前登录用户播放票据的实时传输指标。
     *
     * @param ticket 随机播放票据
     * @param auth Authorization 请求头
     * @return 实际输出字节和活动流快照
     */
    @GetMapping("/api/app/v1/media/tickets/{ticket}/metrics")
    public ResponseEntity<Result<MediaPlaybackMetrics>> getTicketMetrics(
            @PathVariable("ticket") String ticket,
            @RequestHeader("Authorization") String auth) {
        String accessToken = extractToken(auth);
        Long userId = jwtUtils.getUserIdFromToken(accessToken);
        MediaPlaybackTicketService.TicketBinding binding = ticketService.resolve(ticket);
        if (binding == null || !binding.userId().equals(userId)) {
            throw new BusinessException(ErrorCode.MEDIA_004);
        }
        Token session = tokenMapper.findByAccessToken(accessToken);
        if (session == null || !binding.sessionId().equals(session.getId())
                || !binding.userId().equals(session.getUserId())
                || !"ACTIVE".equals(session.getStatus()) || session.getExpireTime() == null
                || session.getExpireTime() <= System.currentTimeMillis()) {
            throw new BusinessException(ErrorCode.MEDIA_004);
        }
        return ResponseEntity.ok(Result.success(ticketService.getMetrics(ticket)));
    }

    private String extractToken(String auth) {
        return auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : auth;
    }

    private String parentPath(String path) {
        if (path == null || path.isBlank()) {
            throw new BusinessException(ErrorCode.FILE_005);
        }
        int lastSlash = path.lastIndexOf('/');
        return lastSlash <= 0 ? "/" : path.substring(0, lastSlash);
    }
}
