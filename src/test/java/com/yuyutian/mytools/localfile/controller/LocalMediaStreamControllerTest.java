package com.yuyutian.mytools.localfile.controller;

import com.yuyutian.mytools.auth.Model.Token;
import com.yuyutian.mytools.auth.mapper.TokenMapper;
import com.yuyutian.mytools.auth.utils.JwtUtils;
import com.yuyutian.mytools.common.Result;
import com.yuyutian.mytools.localfile.service.LocalFileService;
import com.yuyutian.mytools.localfile.service.LocalMediaTicketService;
import com.yuyutian.mytools.reader.service.EbookArchiveNormalizationService;
import com.yuyutian.mytools.localfile.entity.LocalFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 本地媒体播放控制器契约测试。
 */
class LocalMediaStreamControllerTest {

    @TempDir
    Path temporaryDirectory;

    /**
     * 验证有效访问令牌可以为受管媒体签发短期票据。
     */
    @Test
    void shouldIssueTicketForActiveSession() throws Exception {
        LocalFileService fileService = mock(LocalFileService.class);
        JwtUtils jwtUtils = mock(JwtUtils.class);
        TokenMapper tokenMapper = mock(TokenMapper.class);
        Token session = activeSession(7L, 9L);
        Path file = Files.writeString(temporaryDirectory.resolve("video.mp4"), "media");
        when(jwtUtils.getUserIdFromToken("access-token")).thenReturn(7L);
        when(tokenMapper.findByAccessToken("access-token")).thenReturn(session);
        when(fileService.getReadableFilePath(11L)).thenReturn(file);
        LocalMediaStreamController controller = new LocalMediaStreamController(fileService,
                new LocalMediaTicketService(), mock(EbookArchiveNormalizationService.class), jwtUtils, tokenMapper);

        ResponseEntity<Result<LocalMediaTicketService.TicketResult>> response =
                controller.issueTicket("Bearer access-token", 11L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().getData());
        verify(fileService).getReadableFilePath(11L);
    }

    /**
     * 验证播放票据支持标准字节Range响应。
     */
    @Test
    void shouldStreamRequestedByteRange() throws Exception {
        LocalFileService fileService = mock(LocalFileService.class);
        TokenMapper tokenMapper = mock(TokenMapper.class);
        LocalMediaTicketService ticketService = new LocalMediaTicketService();
        Token session = activeSession(7L, 9L);
        Path file = Files.writeString(temporaryDirectory.resolve("sample.mp4"), "0123456789");
        LocalMediaTicketService.TicketResult ticket = ticketService.issue(7L, 9L, 11L);
        when(tokenMapper.findById(9L)).thenReturn(session);
        when(fileService.getReadableFilePath(11L)).thenReturn(file);
        LocalMediaStreamController controller = new LocalMediaStreamController(fileService, ticketService,
                mock(EbookArchiveNormalizationService.class), mock(JwtUtils.class), tokenMapper);
        HttpHeaders headers = new HttpHeaders();
        headers.setRange(java.util.List.of(org.springframework.http.HttpRange.createByteRange(2, 5)));

        ResponseEntity<?> response = controller.streamByTicket(ticket.ticket(), headers);

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        StreamingResponseBody body = (StreamingResponseBody) response.getBody();
        assertNotNull(body);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        body.writeTo(output);
        assertEquals("2345", output.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("bytes 2-5/10", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals(4L, response.getHeaders().getContentLength());
        assertEquals("bytes", response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
    }

    /**
     * 验证电子书专用票据只保存后端归一化服务返回的派生路径。
     */
    @Test
    void shouldIssueTicketForNormalizedEbookArchive() throws Exception {
        LocalFileService fileService = mock(LocalFileService.class);
        EbookArchiveNormalizationService normalizationService = mock(EbookArchiveNormalizationService.class);
        JwtUtils jwtUtils = mock(JwtUtils.class);
        TokenMapper tokenMapper = mock(TokenMapper.class);
        Token session = activeSession(7L, 9L);
        Path source = Files.writeString(temporaryDirectory.resolve("book.epub"), "source");
        Path normalized = Files.writeString(temporaryDirectory.resolve("normalized.epub"), "normalized");
        LocalFile file = new LocalFile();
        file.setId(11L);
        file.setExtension("epub");
        when(jwtUtils.getUserIdFromToken("access-token")).thenReturn(7L);
        when(tokenMapper.findByAccessToken("access-token")).thenReturn(session);
        when(fileService.getFileById(11L)).thenReturn(file);
        when(fileService.getReadableFilePath(11L)).thenReturn(source);
        when(normalizationService.prepareReadableArchive(file, source)).thenReturn(normalized);
        LocalMediaTicketService ticketService = new LocalMediaTicketService();
        LocalMediaStreamController controller = new LocalMediaStreamController(fileService, ticketService,
                normalizationService, jwtUtils, tokenMapper);

        ResponseEntity<Result<LocalMediaTicketService.TicketResult>> response =
                controller.issueEbookTicket("Bearer access-token", 11L);

        assertNotNull(response.getBody());
        LocalMediaTicketService.TicketResult result = response.getBody().getData();
        assertNotNull(result);
        assertEquals(normalized.toString(), ticketService.resolve(result.ticket()).derivedPath());
    }

    /**
     * 验证派生归档票据读取时会再次经过缓存目录边界校验。
     */
    @Test
    void shouldResolveNormalizedPathWhenStreamingEbookTicket() throws Exception {
        LocalFileService fileService = mock(LocalFileService.class);
        EbookArchiveNormalizationService normalizationService = mock(EbookArchiveNormalizationService.class);
        TokenMapper tokenMapper = mock(TokenMapper.class);
        LocalMediaTicketService ticketService = new LocalMediaTicketService();
        Token session = activeSession(7L, 9L);
        Path normalized = Files.writeString(temporaryDirectory.resolve("normalized.epub"), "0123456789");
        LocalMediaTicketService.TicketResult ticket =
                ticketService.issue(7L, 9L, 11L, normalized.toString());
        when(tokenMapper.findById(9L)).thenReturn(session);
        when(normalizationService.requireCachedArchive(normalized.toString())).thenReturn(normalized);
        LocalMediaStreamController controller = new LocalMediaStreamController(fileService, ticketService,
                normalizationService, mock(JwtUtils.class), tokenMapper);

        ResponseEntity<StreamingResponseBody> response =
                controller.streamByTicket(ticket.ticket(), new HttpHeaders());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertNotNull(response.getBody());
        response.getBody().writeTo(output);
        assertEquals("0123456789", output.toString(java.nio.charset.StandardCharsets.UTF_8));
        verify(normalizationService).requireCachedArchive(normalized.toString());
    }

    private Token activeSession(Long userId, Long sessionId) {
        Token session = new Token();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setStatus("ACTIVE");
        session.setExpireTime(System.currentTimeMillis() + 60_000L);
        return session;
    }
}
