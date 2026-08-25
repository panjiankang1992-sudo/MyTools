package com.yuyutian.mytools.gateway.controller;

import com.yuyutian.mytools.gateway.service.DriveGatewayClient;
import com.yuyutian.mytools.gateway.service.DriveOpenTicketService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供无需额外请求头的短期网盘文件流。 */
@RestController
@RequestMapping("/api/app/v1/drive-tickets")
public class DriveTicketController {
    private final DriveGatewayClient client;
    private final DriveOpenTicketService tickets;

    /** 创建票据控制器。 @param client Drive 客户端 @param tickets 票据服务 */
    public DriveTicketController(DriveGatewayClient client, DriveOpenTicketService tickets) {
        this.client = client;
        this.tickets = tickets;
    }

    /** 读取短期票据绑定的文件。 @param token 票据 @param request HTTP 请求 @param response HTTP 响应 */
    @GetMapping("/{token}")
    public void content(@PathVariable String token, HttpServletRequest request, HttpServletResponse response) {
        var ticket = tickets.require(token);
        String correlation = java.util.Optional.ofNullable(request.getHeader("X-Correlation-Id"))
                .filter(value -> value.matches("^[A-Za-z0-9._:-]{1,128}$"))
                .orElseGet(() -> java.util.UUID.randomUUID().toString());
        long maximumBytes = Math.max(1L, ticket.sizeBytes() == 0 ? 100L * 1024 * 1024 * 1024 : ticket.sizeBytes());
        response.setHeader("Cache-Control", "no-store");
        client.stream(ticket.accountId(), ticket.ownerId(), ticket.path(), maximumBytes, response, correlation);
    }
}
