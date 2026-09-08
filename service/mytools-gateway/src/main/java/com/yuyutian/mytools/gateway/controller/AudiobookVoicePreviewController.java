package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.service.AudiobookVoicePreviewTicketService;
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
 * 使用短期票据向系统播放器提供无授权头的音色样音流。
 */
@RestController
@RequestMapping("/api/app/v1/audiobook-voice-preview")
public class AudiobookVoicePreviewController {

    private final ReaderGatewayClient client;
    private final AudiobookVoicePreviewTicketService tickets;

    /**
     * 创建音色样音播放控制器。
     *
     * @param client Reader 服务客户端
     * @param tickets 音色样音票据服务
     */
    public AudiobookVoicePreviewController(ReaderGatewayClient client, AudiobookVoicePreviewTicketService tickets) {
        this.client = client;
        this.tickets = tickets;
    }

    /**
     * 使用已签发票据流式读取一段音色样音。
     *
     * @param ticket 播放票据
     * @param request HTTP 请求
     * @param response HTTP 响应
     */
    @GetMapping("/tickets/{ticket}")
    public void play(@PathVariable String ticket, HttpServletRequest request, HttpServletResponse response) {
        var value = tickets.require(ticket);
        client.streamAudiobookVoicePreview(value.ownerId(), value.generationId(), value.provider(), value.voiceType(),
                request.getHeader("Range"), response, correlation(request));
    }

    private String correlation(HttpServletRequest request) {
        Object value = request.getAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE);
        return value == null ? UUID.randomUUID().toString() : value.toString();
    }
}
