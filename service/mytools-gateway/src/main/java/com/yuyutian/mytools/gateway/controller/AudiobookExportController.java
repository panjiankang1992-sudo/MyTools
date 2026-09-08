package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.service.AudiobookExportTicketService;
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
 * 使用短期票据向系统下载器提供无授权头的有声书 ZIP 归档。
 */
@RestController
@RequestMapping("/api/app/v1/audiobook-export")
public class AudiobookExportController {

    private final ReaderGatewayClient client;
    private final AudiobookExportTicketService tickets;

    /**
     * 创建有声书导出下载控制器。
     *
     * @param client Reader 服务客户端
     * @param tickets 有声书导出票据服务
     */
    public AudiobookExportController(ReaderGatewayClient client, AudiobookExportTicketService tickets) {
        this.client = client;
        this.tickets = tickets;
    }

    /**
     * 使用已签发票据下载一个已完成有声书 ZIP 归档。
     *
     * @param ticket 下载票据
     * @param request HTTP 请求
     * @param response HTTP 响应
     */
    @GetMapping("/tickets/{ticket}")
    public void download(@PathVariable String ticket, HttpServletRequest request, HttpServletResponse response) {
        var value = tickets.require(ticket);
        client.streamAudiobookExport(value.ownerId(), value.generationId(), value.exportId(), response,
                correlation(request));
    }

    private String correlation(HttpServletRequest request) {
        Object value = request.getAttribute(GatewayRequestFilter.CORRELATION_ATTRIBUTE);
        return value == null ? UUID.randomUUID().toString() : value.toString();
    }
}
