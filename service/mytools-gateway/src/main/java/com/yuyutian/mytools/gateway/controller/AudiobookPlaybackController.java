package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.service.AudiobookPlaybackTicketService;
import com.yuyutian.mytools.gateway.service.ReaderGatewayClient;
import com.yuyutian.mytools.gateway.web.GatewayRequestFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 使用短期票据向系统播放器提供无授权头的章节音频流。
 */
@RestController
@RequestMapping("/api/app/v1/audiobook-playback")
public class AudiobookPlaybackController {

    private final ReaderGatewayClient client;
    private final AudiobookPlaybackTicketService tickets;

    /**
     * 创建有声书播放控制器。
     *
     * @param client Reader 服务客户端
     * @param tickets 有声书播放票据服务
     */
    public AudiobookPlaybackController(ReaderGatewayClient client, AudiobookPlaybackTicketService tickets) {
        this.client = client;
        this.tickets = tickets;
    }

    /**
     * 使用已签发票据流式读取一个有声书章节。
     *
     * @param ticket 播放票据
     * @param request HTTP 请求
     * @param response HTTP 响应
     */
    @GetMapping("/tickets/{ticket}")
    public void play(@PathVariable String ticket, HttpServletRequest request, HttpServletResponse response) {
        var value = tickets.require(ticket);
        client.streamAudiobookChapter(value.ownerId(), value.generationId(), value.chapterIndex(),
                request.getHeader("Range"), response, correlation(request));
    }

    private String correlation(HttpServletRequest request) {
        Object value = request.getAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE);
        return value == null ? UUID.randomUUID().toString() : value.toString();
    }
}
